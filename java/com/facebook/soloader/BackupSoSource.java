/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.soloader;

import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import android.os.StrictMode;
import com.facebook.soloader.ExtractFromZipSoSource.ZipUnpacker;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@link SoSource} that extracts zipped libraries from an APK to the filesystem. This is a
 * workaround for a known OS bug where the unpacking of non-asset zipped libraries at install time
 * results in corrupted libraries (e.g. bad elf magic, or truncated files).
 */
public class BackupSoSource extends UnpackingSoSource implements RecoverableSoSource {

  private static final String TAG = "BackupSoSource";

  private static final byte APK_SO_SOURCE_SIGNATURE_VERSION = 3;
  private static final byte LIBS_DIR_DOESNT_EXIST = 1;
  private static final byte LIBS_DIR_SNAPSHOT = 2;
  private static final String ZIP_SEARCH_PATTERN = "^lib/([^/]+)/([^/]+\\.so)$";

  private final ArrayList<ExtractFromZipSoSource> mZipSources = new ArrayList<>();
  protected boolean mInitialized = false;

  public BackupSoSource(Context context, String name, boolean resolveDependencies) {
    super(context, name, resolveDependencies);
    mZipSources.add(
        new ExtractFromZipSoSource(
            context,
            name,
            new File(context.getApplicationInfo().sourceDir),
            // The regular expression matches libraries that would ordinarily be unpacked
            // during installation.
            ZIP_SEARCH_PATTERN));
    addBackupsFromSplitApks(context, name);
  }

  public BackupSoSource(Context context, String name) {
    this(context, name, true);
  }

  private void addBackupsFromSplitApks(Context context, String name) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
        || context.getApplicationInfo().splitSourceDirs == null) {
      return;
    }
    try {
      for (String splitApkDir : context.getApplicationInfo().splitSourceDirs) {
        ExtractFromZipSoSource splitApkSource =
            new ExtractFromZipSoSource(context, name, new File(splitApkDir), ZIP_SEARCH_PATTERN);
        if (splitApkSource.hasZippedLibs()) {
          LogUtil.w(TAG, "adding backup source from split: " + splitApkSource.toString());
          mZipSources.add(splitApkSource);
        }
      }
    } catch (IOException ioException) {
      LogUtil.w(TAG, "failed to read split apks", ioException);
    }
  }

  @Override
  public String getName() {
    return "BackupSoSource";
  }

  @Override
  protected Unpacker makeUnpacker() throws IOException {
    return new ApkUnpacker();
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (!mInitialized) {
      return LOAD_RESULT_NOT_FOUND;
    }
    return super.loadLibrary(soName, loadFlags, threadPolicy);
  }

  @Override
  public void prepare(int flags) throws IOException {
    if ((flags & SoSource.PREPARE_FLAG_SKIP_BACKUP_SO_SOURCE) != 0) {
      return;
    }
    super.prepare(flags);
    mInitialized = true;
  }

  public boolean peekAndPrepareSoSource(String soName, int prepareFlags) throws IOException {
    boolean found = false;
    try (Unpacker u = makeUnpacker()) {
      Dso[] dsos = u.getDsos();
      for (Dso dso : dsos) {
        if (dso.name.equals(soName)) {
          LogUtil.e(SoLoader.TAG, "Found " + soName + " in " + getName());
          found = true;
          break;
        }
      }
    }
    if (!found) {
      return false;
    }
    LogUtil.e(SoLoader.TAG, "Preparing " + getName());
    prepare(prepareFlags);
    return true;
  }

  protected class ApkUnpacker extends Unpacker {

    @Override
    public Dso[] getDsos() throws IOException {
      ArrayList<Dso> dsos = new ArrayList<>();
      for (ExtractFromZipSoSource zipSource : mZipSources) {
        try (Unpacker u = zipSource.makeUnpacker()) {
          dsos.addAll(Arrays.asList(u.getDsos()));
        }
      }
      return dsos.toArray(new Dso[dsos.size()]);
    }

    @Override
    public void unpack(File soDirectory) throws IOException {
      // Delete all files in the directory before unpacking
      for (ExtractFromZipSoSource zipSource : mZipSources) {
        try (ZipUnpacker u = (ZipUnpacker) zipSource.makeUnpacker()) {
          u.unpack(soDirectory);
        }
      }
    }
  }

  @Override
  protected byte[] getDepsBlock() throws IOException {
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeByte(APK_SO_SOURCE_SIGNATURE_VERSION);
      parcel.writeInt(SysUtil.getAppVersionCode(mContext));
      parcel.writeInt(mZipSources.size());
      for (ExtractFromZipSoSource zipSource : mZipSources) {
        parcel.writeByteArray(zipSource.getDepsBlock());
      }

      String sourceDir = mContext.getApplicationInfo().sourceDir;
      if (sourceDir == null) {
        parcel.writeByte(LIBS_DIR_DOESNT_EXIST);
        return parcel.marshall();
      }

      File canonicalFile = new File(sourceDir).getCanonicalFile();
      if (!canonicalFile.exists()) {
        parcel.writeByte(LIBS_DIR_DOESNT_EXIST);
        return parcel.marshall();
      }

      parcel.writeByte(LIBS_DIR_SNAPSHOT);
      parcel.writeString(canonicalFile.getPath());
      parcel.writeLong(canonicalFile.lastModified());
      return parcel.marshall();
    } finally {
      parcel.recycle();
    }
  }

  @Override
  public SoSource recover(Context context) {
    BackupSoSource recovered = new BackupSoSource(context, soDirectory.getName());
    try {
      recovered.prepare(0);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return recovered;
  }

  @Override
  public String toString() {
    String path;
    try {
      path = String.valueOf(soDirectory.getCanonicalPath());
    } catch (IOException e) {
      path = soDirectory.getName();
    }

    return new StringBuilder()
        .append(getName())
        .append("[root = ")
        .append(path)
        .append(" flags = ")
        .append(flags)
        .append(" apks = ")
        .append(mZipSources.toString())
        .append("]")
        .toString();
  }
}
