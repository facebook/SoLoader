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
  @Nullable private final Runtime mRuntime;
  @Nullable private final Method mNativeLoadRuntimeMethod;
  @Nullable private final String mLocalLdLibraryPath;
  @Nullable private final String mLocalLdLibraryPathNoZips;

  public SoFileLoaderImpl() {
    if (Build.VERSION.SDK_INT >= 24) {
      mRuntime = null;
      mNativeLoadRuntimeMethod = null;
      mLocalLdLibraryPath = null;
      mLocalLdLibraryPathNoZips = null;
      return;
    }
    mRuntime = Runtime.getRuntime();
    mNativeLoadRuntimeMethod = SysUtil.getNativeLoadRuntimeMethod();
    mLocalLdLibraryPath =
        (mNativeLoadRuntimeMethod != null) ? SysUtil.getClassLoaderLdLoadLibrary() : null;
    mLocalLdLibraryPathNoZips = SysUtil.makeNonZipPath(mLocalLdLibraryPath);
  }

  @Override
  public void loadBytes(String pathName, ElfByteChannel bytes, int loadFlags) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void load(final String pathToSoFile, final int loadFlags) {
    if (mNativeLoadRuntimeMethod == null) {
      // nativeLoad() version with LD_LIBRARY_PATH override is not available,
      // or not needed (Android 7+), fallback to standard loader.
      System.load(pathToSoFile);
      return;
    }

    String errorMessage = null;
    boolean inZip = (loadFlags & SoLoader.SOLOADER_LOOK_IN_ZIP) == SoLoader.SOLOADER_LOOK_IN_ZIP;
    String ldLibraryPath = inZip ? mLocalLdLibraryPath : mLocalLdLibraryPathNoZips;
    try {
      // nativeLoad should be synchronized so there's only one
      // LD_LIBRARY_PATH in use regardless of how many ClassLoaders are in the system
      synchronized (mRuntime) {
        errorMessage =
            (String)
                mNativeLoadRuntimeMethod.invoke(
                    mRuntime, pathToSoFile, SoLoader.class.getClassLoader(), ldLibraryPath);
        if (errorMessage != null) {
          errorMessage = "nativeLoad() returned error for " + pathToSoFile + ": " + errorMessage;
          throw new SoLoaderULError(pathToSoFile, errorMessage);
        }
      }
    } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
      // Throw an SoLoaderULError to try to recover from the state
      errorMessage = "nativeLoad() error during invocation for " + pathToSoFile + ": " + e;
      throw new RuntimeException(errorMessage);
    } finally {
      if (errorMessage != null) {
        LogUtil.e(
            TAG,
            "Error when loading library: "
                + errorMessage
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
