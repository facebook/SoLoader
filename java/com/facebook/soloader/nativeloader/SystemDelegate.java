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

package com.facebook.soloader.nativeloader;

/** Class which connects system's native library loader to NativeLoader */
public class SystemDelegate implements NativeLoaderDelegate {
  @Override
  public boolean loadLibrary(String shortName, int flags) {
    // System.loadLibrary don't support flags so ignore them.
    System.loadLibrary(shortName);
    // System.loadLibrary doesn't indicate whether this was a first-time load,
    // so let's just always assume it was.
    return true;
  }

  @Override
  public String getLibraryPath(String libName) {
    return null;
  }

  @Override
  public int getSoSourcesVersion() {
    return 0;
  }
}
