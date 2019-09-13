/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.soloader.nativeloader;

import android.content.Context;
import com.facebook.soloader.nativeloader.delegate.NativeLoaderDelegate;
import com.facebook.soloader.nativeloader.delegate.SystemDelegateImpl;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Fasade to load native libraries for android */
public class NativeLoader {

  /** Initialization status flag */
  private static final AtomicBoolean sAlreadyInitialized = new AtomicBoolean(false);

  private static NativeLoaderDelegate sDelegate;
  /** Blocked default constructor */
  private NativeLoader() {}

  /**
   * Set which delegator will be used to load native libraries.
   *
   * @param delegate Delegator to use.
   */
  public static void set(NativeLoaderDelegate delegate) {
    sAlreadyInitialized.set(false);
    sDelegate = delegate;
  }

  /**
   * Load a shared library, initializing any JNI binding it contains.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @return Whether the library was loaded as a result of this call (true), or loading wasn't
   *     successful (false).
   */
  public static boolean loadLibrary(String shortName) {
    if (!sAlreadyInitialized.get()) {
      throw new RuntimeException("NativeLoader.init() not yet called");
    }

    return sDelegate.loadLibrary(shortName);
  }

  /**
   * Initializes native code loading for this app; this class's other static facilities cannot be
   * used until this {@link #init} is called. This method is idempotent: calls after the first are
   * ignored.
   *
   * @param context application context.
   */
  public static void init(Context context) throws IOException {
    synchronized (sAlreadyInitialized) {
      if (sDelegate == null) {
        sDelegate = new SystemDelegateImpl();
      }
      if (sAlreadyInitialized.compareAndSet(false, true)) {
        sDelegate.init(context);
      }
    }
  }

  /**
   * Set value of the initialization status. You should use this method instead of calling {@link
   * #init} in case your native loader is initialized outside of NativeLoader.
   *
   * @param value true if the native loader has been initialized.
   */
  public static void setInitialized(boolean value) {
    sAlreadyInitialized.set(value);
  }
}
