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
import android.os.Parcel;
import java.io.File;
import java.io.IOException;
import java.util.zip.ZipEntry;

/** {@link SoSource} that extracts libraries from an APK to the filesystem. */
public class ApkSoSource extends ExtractFromZipSoSource {

  private static final String TAG = "ApkSoSource";

  /**
   * If this flag is given, do not extract libraries that appear to be correctly extracted to the
   * application libs directory.
   */
  public static final int PREFER_ANDROID_LIBS_DIRECTORY = (1 << 0);

  private static final byte APK_SO_SOURCE_SIGNATURE_VERSION = 2;
  private static final byte LIBS_DIR_DONT_CARE = 0;
  private static final byte LIBS_DIR_DOESNT_EXIST = 1;
  private static final byte LIBS_DIR_SNAPSHOT = 2;

  private final int mFlags;

  public ApkSoSource(Context context, String name, int flags) {
    this(context, new File(context.getApplicationInfo().sourceDir), name, flags);
  }

  public ApkSoSource(Context context, File apkPath, String name, int flags) {
    super(
        context,
        name,
        apkPath,
        // The regular expression matches libraries that would ordinarily be unpacked
        // during installation.
        "^lib/([^/]+)/([^/]+\\.so)$");
    mFlags = flags;
  }

  @Override
  public String getName() {
    return "ApkSoSource";
  }

  @Override
  protected Unpacker makeUnpacker(boolean forceUnpacking) throws IOException {
    return new ApkUnpacker(this, forceUnpacking);
  }

  protected class ApkUnpacker extends ZipUnpacker {

    private final boolean mForceUnpacking;
    private final File mLibDir;
    private final int mFlags;

    ApkUnpacker(ExtractFromZipSoSource soSource, boolean forceUnpacking) throws IOException {
      super(soSource);
      mForceUnpacking = forceUnpacking;
      mLibDir = new File(mContext.getApplicationInfo().nativeLibraryDir);
      mFlags = ApkSoSource.this.mFlags;
    }

    @Override
    protected ZipDso[] getExtractableDsosFromZip() {
      if (mDsos != null) {
        return mDsos;
      }

      ZipDso[] dsos = computeDsosFromZip();
      mDsos = dsos;

      if (mForceUnpacking) {
        LogUtil.w(TAG, "Unconditonally extracting all DSOs from zip");
        return mDsos;
      }

      if ((mFlags & PREFER_ANDROID_LIBS_DIRECTORY) == 0) {
        LogUtil.w(TAG, "Self-extraction preferred (PREFER_ANDROID_LIBS_DRIECTORY not set)");
        return mDsos;
      }

      for (ZipDso zd : dsos) {
        if (shouldExtract(zd.backingEntry, zd.name)) {
          // If one library is corrupted, extract all of them to simplify the logic of computing
          // dependencies. By default, the application so source (/data/app) relies on the bionic
          // linker to resolve dependencies.
          // If there's 2 /data/app libraries with the same corrupted depdendency, we might end up
          // facing multiple load failures that can be avoided:
          //    A depends on C
          //    B depends on C
          //    C is corrupted
          //    Try to load A (from app so source - data/app) -> load C first (from /data/app) -> C
          // fails, hence unpack A and C to /data/data
          //    Try to load B (from app so source - data/app) -> load C first (from /data/app) ->
          // fail to load, even though C has previously been unpacked to /data/data and can be used
          return mDsos;
        }
      }

      // No DSO should be extracted
      mDsos = new ZipDso[0];
      return mDsos;
    }

    // This is a lightweight fast check that runs during the first coldstart after a fresh install.
    boolean shouldExtract(ZipEntry ze, String soName) {
      String zipPath = ze.getName();
      File sysLibFile = new File(mLibDir, soName);
      try {
        if (!sysLibFile.getCanonicalPath().startsWith(mLibDir.getCanonicalPath())) {
          LogUtil.w(
              TAG, "Not allowing consideration of " + zipPath + ": " + soName + " not in lib dir.");
          return false;
        }
      } catch (IOException e) {
        LogUtil.w(
            TAG,
            "Not allowing consideration of "
                + zipPath
                + ": "
                + soName
                + ", IOException when constructing path: "
                + e.toString());
        return false;
      }

      if (!sysLibFile.isFile()) {
        LogUtil.w(
            TAG, "Allowing consideration of " + zipPath + ": " + soName + " not in system lib dir");
        return true;
      }

      long sysLibLength = sysLibFile.length();
      long apkLibLength = ze.getSize();

      if (sysLibLength != apkLibLength) {
        LogUtil.w(
            TAG,
            "Allowing consideration of "
                + sysLibFile
                + ": sysdir file length is "
                + sysLibLength
                + ", but the file is "
                + apkLibLength
                + " bytes long in the APK");
        return true;
      }

      LogUtil.w(TAG, "Not allowing consideration of " + zipPath + ": deferring to libdir");
      return false;
    }
  }

  @Override
  protected byte[] getDepsBlock() throws IOException {
    File apkFile = mZipFileName.getCanonicalFile();
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeByte(APK_SO_SOURCE_SIGNATURE_VERSION);
      parcel.writeString(apkFile.getPath());
      parcel.writeLong(apkFile.lastModified());
      parcel.writeInt(SysUtil.getAppVersionCode(mContext));

      if ((mFlags & PREFER_ANDROID_LIBS_DIRECTORY) == 0) {
        parcel.writeByte(LIBS_DIR_DONT_CARE);
        return parcel.marshall();
      }

      String nativeLibraryDir = mContext.getApplicationInfo().nativeLibraryDir;
      if (nativeLibraryDir == null) {
        parcel.writeByte(LIBS_DIR_DOESNT_EXIST);
        return parcel.marshall();
      }

      File canonicalFile = new File(nativeLibraryDir).getCanonicalFile();
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
}
