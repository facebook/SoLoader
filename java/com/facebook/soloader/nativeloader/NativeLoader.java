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

/** Facade to load native libraries for android */
public class NativeLoader {

  private static NativeLoaderDelegate sDelegate;

  /** Blocked default constructor */
  private NativeLoader() {}

  public static boolean loadLibrary(String shortName) {
    return loadLibrary(shortName, 0);
  }

  /**
   * Load a shared library, initializing any JNI binding it contains. Depending upon underlying
   * loader, behavior can be customized based on passed in flag
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @param flags 0 for default behavior, otherwise NativeLoaderDelegate defines other behaviors.
   * @return Whether the library was loaded as a result of this call (true), or was already loaded
   *     through a previous call (false).
   */
  public static boolean loadLibrary(String shortName, int flags) {
    synchronized (NativeLoader.class) {
      if (sDelegate == null) {
        throw new IllegalStateException(
            "NativeLoader has not been initialized.  "
                + "To use standard native library loading, call "
                + "NativeLoader.init(new SystemDelegate()).");
      }
    }
    return sDelegate.loadLibrary(shortName, flags);
  }

  /**
   * Get the path of a loaded shared library.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @return The path for the loaded library, null otherwise.
   */
  public static String getLibraryPath(String shortName) throws IOException {
    synchronized (NativeLoader.class) {
      if (sDelegate == null) {
        throw new IllegalStateException(
            "NativeLoader has not been initialized.  "
                + "To use standard native library loading, call "
                + "NativeLoader.init(new SystemDelegate()).");
      }
    }

    return sDelegate.getLibraryPath(shortName);
  }

  /**
   * Get the version of the loader used.
   *
   * @return The version number for the loader.
   */
  public static int getSoSourcesVersion() {
    synchronized (NativeLoader.class) {
      if (sDelegate == null) {
        throw new IllegalStateException(
            "NativeLoader has not been initialized.  "
                + "To use standard native library loading, call "
                + "NativeLoader.init(new SystemDelegate()).");
      }
    }

    return sDelegate.getSoSourcesVersion();
  }

  /**
   * Initializes native code loading for this app. Should be called only once, before any calls to
   * {@link #loadLibrary(String)}.
   *
   * @param delegate Delegate to use for all {@code loadLibrary} calls.
   */
  public static synchronized void init(NativeLoaderDelegate delegate) {
    if (sDelegate != null) {
      throw new IllegalStateException("Cannot re-initialize NativeLoader.");
    }
    sDelegate = delegate;
  }

  /**
   * Determine whether {@code NativeLoader} has already been initialized. This method should not
   * normally be used, because initialization should be performed only once during app startup.
   * However, libraries that want to provide a default initialization for {@code NativeLoader} to
   * hide its existence from the app can use this method to avoid re-initializing.
   *
   * @return True iff {@link #init(NativeLoaderDelegate)} has been called.
   */
  public static synchronized boolean isInitialized() {
    return sDelegate != null;
  }
}
