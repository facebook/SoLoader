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

package com.facebook.soloader.nativeloader;

import java.io.IOException;
import java.util.Optional;

/**
 * Facade to manage loading of native libraries for Android.
 */
public final class NativeLoader {

    private static NativeLoaderDelegate sDelegate;

    // Private constructor to prevent instantiation
    private NativeLoader() {}

    /**
     * Loads a shared library with default behavior.
     *
     * @param shortName Name of the library, without "lib" prefix or ".so" suffix.
     * @return {@code true} if the library was loaded by this call; {@code false} if it was already loaded.
     */
    public static boolean loadLibrary(String shortName) {
        return loadLibrary(shortName, 0);
    }

    /**
     * Loads a shared library with custom behavior defined by flags.
     *
     * @param shortName Name of the library, without "lib" prefix or ".so" suffix.
     * @param flags     Flags for customization; default is 0.
     * @return {@code true} if the library was loaded by this call; {@code false} if it was already loaded.
     */
    public static boolean loadLibrary(String shortName, int flags) {
        ensureInitialized();
        return sDelegate.loadLibrary(shortName, flags);
    }

    /**
     * Retrieves the path of a loaded shared library.
     *
     * @param shortName Name of the library, without "lib" prefix or ".so" suffix.
     * @return An {@link Optional} containing the library path if found; empty otherwise.
     * @throws IOException if an error occurs while retrieving the path.
     */
    public static Optional<String> getLibraryPath(String shortName) throws IOException {
        ensureInitialized();
        return Optional.ofNullable(sDelegate.getLibraryPath(shortName));
    }

    /**
     * Retrieves the version of the loader being used.
     *
     * @return The version number of the loader.
     */
    public static int getSoSourcesVersion() {
        ensureInitialized();
        return sDelegate.getSoSourcesVersion();
    }

    /**
     * Initializes the NativeLoader with a specified delegate.
     * Should be called once during app startup.
     *
     * @param delegate The delegate to handle library loading operations.
     */
    public static synchronized void init(NativeLoaderDelegate delegate) {
        if (sDelegate != null) {
            throw new IllegalStateException("NativeLoader has already been initialized.");
        }
        sDelegate = delegate;
    }

    /**
     * Checks if the NativeLoader has been initialized.
     *
     * @return {@code true} if initialized; {@code false} otherwise.
     */
    public static synchronized boolean isInitialized() {
        return sDelegate != null;
    }

    /**
     * Initializes the NativeLoader only if it hasn't already been initialized.
     *
     * @param delegate The delegate to handle library loading operations.
     */
    public static void initIfUninitialized(NativeLoaderDelegate delegate) {
        synchronized (NativeLoader.class) {
            if (!isInitialized()) {
                init(delegate);
            }
        }
    }

    /**
     * Ensures that the NativeLoader has been initialized; otherwise, throws an exception.
     */
    private static void ensureInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException(
                "NativeLoader is not initialized. Call NativeLoader.init(new SystemDelegate()) before using it.");
        }
    }
}
