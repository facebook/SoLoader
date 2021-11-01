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

import android.content.Context;
import android.os.Build;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@link SoSource} that directly finds shared libraries in a APK. The native libraries must be page
 * aligned and stored uncompressed in the APK.
 *
 * @see <a
 *     href="https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs">
 *     android:extractNativeLibs</a>
 */
@ThreadSafe
public class DirectApkSoSource extends SoSource {

  private final Set<String> mLibsInApk = Collections.synchronizedSet(new HashSet<String>());
  private final @Nullable String mDirectApkLdPath;
  private final File mApkFile;

  public DirectApkSoSource(Context context) {
    super();
    mDirectApkLdPath = getDirectApkLdPath("");
    mApkFile = new File(context.getApplicationInfo().sourceDir);
  }

  public DirectApkSoSource(File apkFile) {
    super();
    mDirectApkLdPath = getDirectApkLdPath(SysUtil.getBaseName(apkFile.getName()));
    mApkFile = apkFile;
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (SoLoader.sSoFileLoader == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }
    if (!mLibsInApk.contains(soName) || TextUtils.isEmpty(mDirectApkLdPath)) {
      Log.d(SoLoader.TAG, soName + " not found on " + mDirectApkLdPath);
      return LOAD_RESULT_NOT_FOUND;
    }

    loadDependencies(soName, loadFlags, threadPolicy);

    try {
      loadFlags |= SoLoader.SOLOADER_LOOK_IN_ZIP;
      SoLoader.sSoFileLoader.load(mDirectApkLdPath + File.separator + soName, loadFlags);
    } catch (UnsatisfiedLinkError e) {
      Log.w(SoLoader.TAG, soName + " not found on DirectAPKSoSource: " + loadFlags, e);
      return LOAD_RESULT_NOT_FOUND;
    }
    Log.d(SoLoader.TAG, soName + " found on DirectAPKSoSource: " + loadFlags);
    return LOAD_RESULT_LOADED;
  }

  @Override
  public File unpackLibrary(String soName) throws IOException {
    throw new UnsupportedOperationException("DirectAPKSoSource doesn't support unpackLibrary");
  }

  private @Nullable static String getDirectApkLdPath(String apkName) {
    final String classLoaderLdLibraryPath =
        Build.VERSION.SDK_INT >= 14 ? SoLoader.Api14Utils.getClassLoaderLdLoadLibrary() : null;

    if (classLoaderLdLibraryPath != null) {
      final String[] paths = classLoaderLdLibraryPath.split(":");
      for (final String path : paths) {
        if (path.contains(apkName + ".apk!/")) {
          return path;
        }
      }
    }
    return null;
  }

  private static String[] getDependencies(String soName, ElfByteChannel bc) throws IOException {
    if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
      Api18TraceUtils.beginTraceSection("SoLoader.getElfDependencies[", soName, "]");
    }
    try {
      return NativeDeps.getDependencies(soName, bc);
    } finally {
      if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
        Api18TraceUtils.endSection();
      }
    }
  }

  private void loadDependencies(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    try (ZipFile mZipFile = new ZipFile(mApkFile)) {
      Enumeration<? extends ZipEntry> entries = mZipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry != null && entry.getName().endsWith("/" + soName)) {
          try (ElfByteChannel bc = new ElfZipFileChannel(mZipFile, entry)) {
            for (String dependency : getDependencies(soName, bc)) {
              if (mLibsInApk.contains(dependency) || dependency.startsWith("/")) {
                // Bionic dynamic linker could correctly resolving dependencies, we don't need
                // load them by ourselves.
                continue;
              }

              SoLoader.loadLibraryBySoName(
                  dependency, loadFlags | LOAD_FLAG_ALLOW_IMPLICIT_PROVISION, threadPolicy);
            }
          }
          break;
        }
      }
    }
  }

  @Override
  protected void prepare(int flags) throws IOException {
    String subDir = null;
    if (!TextUtils.isEmpty(mDirectApkLdPath)) {
      final int i = mDirectApkLdPath.indexOf('!');
      if (i >= 0 && i + 2 < mDirectApkLdPath.length()) {
        // Exclude `!` and `/` in the path
        subDir = mDirectApkLdPath.substring(i + 2);
      }
    }
    if (subDir == null) {
      return;
    }

    try (ZipFile mZipFile = new ZipFile(mApkFile)) {
      Enumeration<? extends ZipEntry> entries = mZipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry != null
            && entry.getName().startsWith(subDir)
            && entry.getName().endsWith(".so")
            && entry.getMethod() == ZipEntry.STORED) {
          final String soName = entry.getName().substring(subDir.length() + 1);
          mLibsInApk.add(soName);
        }
      }
    }
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append(getClass().getName())
        .append("[root = ")
        .append(mDirectApkLdPath)
        .append(']')
        .toString();
  }
}
