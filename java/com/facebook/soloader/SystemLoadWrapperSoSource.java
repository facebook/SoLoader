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

import android.annotation.SuppressLint;
import android.os.StrictMode;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * A {@link SoSource} that uses the system's {@code System.loadLibrary()} method to load a library
 */
public class SystemLoadWrapperSoSource extends SoSource {

  @SuppressLint("CatchGeneralException")
  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    try {
      System.loadLibrary(soName.substring("lib".length(), soName.length() - ".so".length()));
    } catch (Exception e) {
      LogUtil.e(SoLoader.TAG, "Error loading library: " + soName, e);
      return LOAD_RESULT_NOT_FOUND;
    }
    return LOAD_RESULT_LOADED;
  }

  @Nullable
  @Override
  public File unpackLibrary(String soName) throws IOException {
    return null;
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    final String ldPaths = SysUtil.getClassLoaderLdLoadLibrary();
    if (TextUtils.isEmpty(ldPaths)) {
      return null;
    }

    for (String ldPath : ldPaths.split(":")) {
      if (SysUtil.isDisabledExtractNativeLibs(SoLoader.sApplicationContext)
          && ldPath.contains(".apk!")) {
        return ldPath + File.separator + soName;
      } else {
        File file = new File(ldPath, soName);
        if (file.exists()) {
          return file.getCanonicalPath();
        }
      }
    }
    return null;
  }

  @Override
  public String getName() {
    return "SystemLoadWrapperSoSource";
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append(getName())
        .append("[")
        .append(SysUtil.getClassLoaderLdLoadLibrary())
        .append("]")
        .toString();
  }
}
