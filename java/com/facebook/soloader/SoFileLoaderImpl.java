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

import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.annotation.Nullable;

public class SoFileLoaderImpl implements SoFileLoader {

  private static final String TAG = "SoFileLoaderImpl";
  private final Runtime mRuntime;
  @Nullable private final Method mNativeLoadRuntimeMethod;
  private final boolean mHasNativeLoadMethod;
  @Nullable private final String mLocalLdLibraryPath;
  @Nullable private final String mLocalLdLibraryPathNoZips;

  public SoFileLoaderImpl() {
    mRuntime = Runtime.getRuntime();
    mNativeLoadRuntimeMethod = getNativeLoadRuntimeMethod();
    mHasNativeLoadMethod = mNativeLoadRuntimeMethod != null;
    mLocalLdLibraryPath =
        mHasNativeLoadMethod ? SysUtil.Api14Utils.getClassLoaderLdLoadLibrary() : null;
    mLocalLdLibraryPathNoZips = SoLoader.makeNonZipPath(mLocalLdLibraryPath);
  }

  private static @Nullable Method getNativeLoadRuntimeMethod() {
    // For API level 23+ dlopen looks through ZIP files on the LD_LIBRARY_PATH. This is unnecessary
    // and dramatically slows down SO loading. Android supports loading different
    // LD_LIBRARY_PATHs for each ClassLoader so it allows us to pass in a local override by calling
    // into the "nativeLoad" native method that Android calls internally.
    // We really don't need any of the internal Java logic for System.load() and this is a perf
    // improvement.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return null;
    }

    // However, the nativeLoad API is not available in the format we need (i.e. the one that allows
    // us to
    // override LD_LIBRARY_PATH) since API 28.
    // https://android.googlesource.com/platform/libcore/+/refs/tags/android-9.0.0_r61/ojluni/src/main/java/java/lang/Runtime.java#1071
    if (Build.VERSION.SDK_INT > 27) {
      return null;
    }

    try {
      // https://android.googlesource.com/platform/libcore/+/refs/tags/android-8.0.0_r45/ojluni/src/main/java/java/lang/Runtime.java#1103
      final Method method =
          Runtime.class.getDeclaredMethod(
              "nativeLoad", String.class, ClassLoader.class, String.class);
      method.setAccessible(true);
      return method;
    } catch (final NoSuchMethodException | SecurityException e) {
      LogUtil.w(TAG, "Cannot get nativeLoad method", e);
      return null;
    }
  }

  @Override
  public void loadBytes(String pathName, ElfByteChannel bytes, int loadFlags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void load(final String pathToSoFile, final int loadFlags) {
    if (!mHasNativeLoadMethod) {
      // nativeLoad() version with LD_LIBRARY_PATH override is not available, fallback to standard
      // loader.
      System.load(pathToSoFile);
      return;
    }

    String error = null;
    boolean inZip = (loadFlags & SoLoader.SOLOADER_LOOK_IN_ZIP) == SoLoader.SOLOADER_LOOK_IN_ZIP;
    String ldLibraryPath = inZip ? mLocalLdLibraryPath : mLocalLdLibraryPathNoZips;
    try {
      // nativeLoad should be synchronized so there's only one
      // LD_LIBRARY_PATH in use regardless of how many ClassLoaders are in the system
      // https://android.googlesource.com/platform/libcore/+/refs/tags/android-8.0.0_r45/ojluni/src/main/java/java/lang/Runtime.java#1103
      synchronized (mRuntime) {
        error =
            (String)
                mNativeLoadRuntimeMethod.invoke(
                    mRuntime, pathToSoFile, SoLoader.class.getClassLoader(), ldLibraryPath);
        if (error != null) {
          throw new UnsatisfiedLinkError(error);
        }
      }
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      error = "Error: Cannot load " + pathToSoFile;
      throw new RuntimeException(error, e);
    } finally {
      if (error != null) {
        LogUtil.e(
            TAG,
            "Error when loading library: "
                + error
                + ", library hash is "
                + getLibHash(pathToSoFile)
                + ", LD_LIBRARY_PATH is "
                + ldLibraryPath);
      }
    }
  }

  /** * Logs MD5 of lib that failed loading */
  private String getLibHash(String libPath) {
    String digestStr;
    try {
      File libFile = new File(libPath);
      MessageDigest digest = MessageDigest.getInstance("MD5");
      try (InputStream libInStream = new FileInputStream(libFile)) {
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = libInStream.read(buffer)) > 0) {
          digest.update(buffer, 0, bytesRead);
        }
        digestStr = String.format("%32x", new BigInteger(1, digest.digest()));
      }
    } catch (IOException | SecurityException | NoSuchAlgorithmException e) {
      digestStr = e.toString();
    }
    return digestStr;
  }
}
