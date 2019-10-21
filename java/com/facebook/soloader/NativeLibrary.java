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

import android.util.Log;
import java.util.List;
import javax.annotation.Nullable;

/**
 * This is the base class for all the classes representing certain native library. For loading
 * native libraries we should always inherit from this class and provide relevant information
 * (libraries to load, code to test native call, dependencies?).
 *
 * <p>This instances should be singletons provided by DI.
 *
 * <p>This is a basic template but could be improved if we find the need.
 */
public abstract class NativeLibrary {
  private static final String TAG = NativeLibrary.class.getName();

  private final Object mLock;
  private @Nullable List<String> mLibraryNames;
  private Boolean mLoadLibraries;
  private boolean mLibrariesLoaded;
  private volatile @Nullable UnsatisfiedLinkError mLinkError;

  protected NativeLibrary(List<String> libraryNames) {
    mLock = new Object();
    mLoadLibraries = true;
    mLibrariesLoaded = false;
    mLinkError = null;
    mLibraryNames = libraryNames;
  }

  /**
   * safe loading of native libs
   *
   * @return true if native libs loaded properly, false otherwise
   */
  @Nullable
  public boolean loadLibraries() {
    synchronized (mLock) {
      if (mLoadLibraries == false) {
        return mLibrariesLoaded;
      }
      try {
        if (mLibraryNames != null) {
          for (String name : mLibraryNames) {
            SoLoader.loadLibrary(name);
          }
        }
        initialNativeCheck();
        mLibrariesLoaded = true;
        mLibraryNames = null;
      } catch (UnsatisfiedLinkError error) {
        Log.e(TAG, "Failed to load native lib (initial check): ", error);
        mLinkError = error;
        mLibrariesLoaded = false;
      } catch (Throwable other) {
        Log.e(TAG, "Failed to load native lib (other error): ", other);
        mLinkError = new UnsatisfiedLinkError("Failed loading libraries");
        mLinkError.initCause(other);
        mLibrariesLoaded = false;
      }
      mLoadLibraries = false;
      return mLibrariesLoaded;
    }
  }

  /**
   * loads libraries (if not loaded yet), throws on failure
   *
   * @throws UnsatisfiedLinkError
   */
  public void ensureLoaded() throws UnsatisfiedLinkError {
    if (!loadLibraries()) {
      throw mLinkError;
    }
  }

  /**
   * Override this method to make some concrete (quick and harmless) native call. This avoids
   * lazy-loading some phones (LG) use when we call loadLibrary. If there's a problem we'll face an
   * UnsupportedLinkError when first using the feature instead of here. This check force a check
   * right when intended. This way clients of this library can know if it's loaded for sure or not.
   *
   * @throws UnsatisfiedLinkError if there was an error loading native library
   */
  protected void initialNativeCheck() throws UnsatisfiedLinkError {}

  public @Nullable UnsatisfiedLinkError getError() {
    return mLinkError;
  }
}
