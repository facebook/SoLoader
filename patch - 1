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

import java.io.IOException;

/** Interface used to connect chosen loader of native libraries to NativeLoader */
public interface NativeLoaderDelegate {
  /**
   * Skip calling JNI_OnLoad if the library is merged. This is necessary for libraries that don't
   * define JNI_OnLoad and are only loaded for their side effects (like static constructors
   * registering callbacks). DO NOT use this to allow implicit JNI registration (by naming your
   * methods Java_com_facebook_whatever) because that is buggy on Android.
   */
  int SKIP_MERGED_JNI_ONLOAD = 1;
  /** @see com.facebook.soloader.nativeloader.NativeLoader#loadLibrary(String, int) */
  boolean loadLibrary(String shortName, int flags);
  /** @see com.facebook.soloader.nativeloader.NativeLoader#getLibraryPath(String) */
  String getLibraryPath(String libName) throws IOException;
  /** @see com.facebook.soloader.nativeloader.NativeLoader#getSoSourcesVersion() */
  int getSoSourcesVersion();
}
