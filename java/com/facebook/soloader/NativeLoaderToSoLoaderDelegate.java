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

import com.facebook.soloader.nativeloader.NativeLoaderDelegate;
import java.io.IOException;

/** Class that connects SoLoader to NativeLoader */
public class NativeLoaderToSoLoaderDelegate implements NativeLoaderDelegate {
  @Override
  public boolean loadLibrary(String shortName, int flags) {
    int soLoaderFlags = 0;
    soLoaderFlags |=
        ((flags & SKIP_MERGED_JNI_ONLOAD) != 0) ? SoLoader.SOLOADER_SKIP_MERGED_JNI_ONLOAD : 0;
    return SoLoader.loadLibrary(shortName, soLoaderFlags);
  }

  @Override
  public String getLibraryPath(String libName) throws IOException {
    return SoLoader.getLibraryPath(libName);
  }

  @Override
  public int getSoSourcesVersion() {
    return SoLoader.getSoSourcesVersion();
  }
}
