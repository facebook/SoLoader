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

import android.content.Context;
import android.os.Build;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

  private final Map<String, Set<String>> mLibsInApkMap = new HashMap<>();
  private final Set<String> mDirectApkLdPaths;
  private final File mApkFile;

  public DirectApkSoSource(Context context) {
    super();
    mDirectApkLdPaths = getDirectApkLdPaths("");
    mApkFile = new File(context.getApplicationInfo().sourceDir);
  }

  public DirectApkSoSource(File apkFile) {
    super();
    mDirectApkLdPaths = getDirectApkLdPaths(SysUtil.getBaseName(apkFile.getName()));
    mApkFile = apkFile;
  }

  public DirectApkSoSource(File apkFile, Set<String> directApkLdPaths) {
    super();
    mDirectApkLdPaths = directApkLdPaths;
    mApkFile = apkFile;
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (SoLoader.sSoFileLoader == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }

    for (String directApkLdPath : mDirectApkLdPaths) {
      Set<String> libsInApk = mLibsInApkMap.get(directApkLdPath);
      if (TextUtils.isEmpty(directApkLdPath) || libsInApk == null || !libsInApk.contains(soName)) {
        Log.v(SoLoader.TAG, soName + " not found on " + directApkLdPath);
        continue;
      }

      loadDependencies(soName, loadFlags, threadPolicy);

      try {
        final String soPath = directApkLdPath + File.separator + soName;
        loadFlags |= SoLoader.SOLOADER_LOOK_IN_ZIP;
        SoLoader.sSoFileLoader.load(soPath, loadFlags);
      } catch (UnsatisfiedLinkError e) {
        Log.w(SoLoader.TAG, soName + " not found on " + directApkLdPath + " flag: " + loadFlags, e);
        continue;
      }

      Log.d(SoLoader.TAG, soName + " found on " + directApkLdPath);
      return LOAD_RESULT_LOADED;
    }
    return LOAD_RESULT_NOT_FOUND;
  }

  @Override
  public File unpackLibrary(String soName) throws IOException {
    throw new UnsupportedOperationException("DirectAPKSoSource doesn't support unpackLibrary");
  }

  /*package*/ static Set<String> getDirectApkLdPaths(String apkName) {
    Set<String> directApkPathSet = new HashSet<>();
    final String classLoaderLdLibraryPath =
        Build.VERSION.SDK_INT >= 14 ? SysUtil.Api14Utils.getClassLoaderLdLoadLibrary() : null;

    if (classLoaderLdLibraryPath != null) {
      final String[] paths = classLoaderLdLibraryPath.split(":");
      for (final String path : paths) {
        if (path.contains(apkName + ".apk!/")) {
          directApkPathSet.add(path);
        }
      }
    }
    return directApkPathSet;
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
              if (dependency.startsWith("/")) {
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
    for (String directApkLdPath : mDirectApkLdPaths) {
      if (!TextUtils.isEmpty(directApkLdPath)) {
        final int i = directApkLdPath.indexOf('!');
        if (i >= 0 && i + 2 < directApkLdPath.length()) {
          // Exclude `!` and `/` in the path
          subDir = directApkLdPath.substring(i + 2);
        }
      }
      if (TextUtils.isEmpty(subDir)) {
        continue;
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
            append(directApkLdPath, soName);
          }
        }
      }
    }
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append(getClass().getName())
        .append("[root = ")
        .append(LdPathsToString())
        .append(']')
        .toString();
  }

  private synchronized void append(String ldPath, String soName) {
    if (!mLibsInApkMap.containsKey(ldPath)) {
      mLibsInApkMap.put(ldPath, new HashSet<String>());
    }
    mLibsInApkMap.get(ldPath).add(soName);
  }

  private String LdPathsToString() {
    StringBuilder sb = new StringBuilder();
    sb.append('(');
    for (String path : mDirectApkLdPaths) {
      sb.append(path).append(", ");
    }
    sb.append(')');
    return sb.toString();
  }
}
