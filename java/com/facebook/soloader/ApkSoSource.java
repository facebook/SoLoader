/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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
import android.util.Log;
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
  protected Unpacker makeUnpacker() throws IOException {
    return new ApkUnpacker(this);
  }

  protected class ApkUnpacker extends ZipUnpacker {

    private File mLibDir;
    private final int mFlags;

    ApkUnpacker(ExtractFromZipSoSource soSource) throws IOException {
      super(soSource);
      mLibDir = new File(mContext.getApplicationInfo().nativeLibraryDir);
      mFlags = ApkSoSource.this.mFlags;
    }

    @Override
    protected boolean shouldExtract(ZipEntry ze, String soName) {
      final boolean result;
      String zipPath = ze.getName();
      final String msg;
      if (soName.equals(mCorruptedLib)) {
        mCorruptedLib = null;
        msg = String.format("allowing consideration of corrupted lib %s", soName);
        result = true;
      } else if ((mFlags & PREFER_ANDROID_LIBS_DIRECTORY) == 0) {
        msg = "allowing consideration of " + zipPath + ": self-extraction preferred";
        result = true;
      } else {
        File sysLibFile = new File(mLibDir, soName);
        if (!sysLibFile.isFile()) {
          msg =
              String.format(
                  "allowing considering of %s: %s not in system lib dir", zipPath, soName);
          result = true;
        } else {
          long sysLibLength = sysLibFile.length();
          long apkLibLength = ze.getSize();

          if (sysLibLength != apkLibLength) {
            msg =
                String.format(
                    "allowing consideration of %s: sysdir file length is %s, but "
                        + "the file is %s bytes long in the APK",
                    sysLibFile, sysLibLength, apkLibLength);
            result = true;
          } else {
            msg = "not allowing consideration of " + zipPath + ": deferring to libdir";
            result = false;
          }
        }
      }
      Log.d(TAG, msg);
      return result;
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
