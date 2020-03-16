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

import android.os.StrictMode;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import javax.annotation.Nullable;

/** {@link SoSource} that finds shared libraries in a given directory. */
public class DirectorySoSource extends SoSource {

  public static final int RESOLVE_DEPENDENCIES = 1;
  public static final int ON_LD_LIBRARY_PATH = 2;

  protected final File soDirectory;
  protected final int flags;

  /**
   * Make a new DirectorySoSource. If {@code flags} contains {@code RESOLVE_DEPENDENCIES},
   * recursively load dependencies for shared objects loaded from this directory. (We shouldn't need
   * to resolve dependencies for libraries loaded from system directories: the dynamic linker is
   * smart enough to do it on its own there.)
   */
  public DirectorySoSource(File soDirectory, int flags) {
    this.soDirectory = soDirectory;
    this.flags = flags;
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    return loadLibraryFrom(soName, loadFlags, soDirectory, threadPolicy);
  }

  // Abstracted this logic in another method so subclasses can take advantage of it.
  protected int loadLibraryFrom(
      String soName, int loadFlags, File libDir, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    File soFile = new File(libDir, soName);
    if (!soFile.exists()) {
      Log.d(SoLoader.TAG, soName + " not found on " + libDir.getCanonicalPath());
      return LOAD_RESULT_NOT_FOUND;
    } else {
      Log.d(SoLoader.TAG, soName + " found on " + libDir.getCanonicalPath());
    }
    if ((loadFlags & LOAD_FLAG_ALLOW_IMPLICIT_PROVISION) != 0
        && (flags & ON_LD_LIBRARY_PATH) != 0) {
      Log.d(SoLoader.TAG, soName + " loaded implicitly");
      return LOAD_RESULT_IMPLICITLY_PROVIDED;
    }

    if ((flags & RESOLVE_DEPENDENCIES) != 0) {
      loadDependencies(soFile, loadFlags, threadPolicy);
    } else {
      Log.d(SoLoader.TAG, "Not resolving dependencies for " + soName);
    }

    try {
      SoLoader.sSoFileLoader.load(soFile.getAbsolutePath(), loadFlags);
    } catch (UnsatisfiedLinkError e) {
      if (e.getMessage().contains("bad ELF magic")) {
        Log.d(SoLoader.TAG, "Corrupted lib file detected");
        // Swallow exception. Higher layers will try again from a backup source
        return LOAD_RESULT_CORRUPTED_LIB_FILE;
      } else {
        throw e;
      }
    }

    return LOAD_RESULT_LOADED;
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    File soFile = new File(soDirectory, soName);
    if (soFile.exists()) {
      return soFile.getCanonicalPath();
    }
    return null;
  }

  @Nullable
  @Override
  public String[] getLibraryDependencies(String soName) throws IOException {
    File soFile = new File(soDirectory, soName);
    if (soFile.exists()) {
      return getDependencies(soFile);
    } else {
      return null;
    }
  }

  private static void loadDependencies(
      File soFile, int loadFlags, StrictMode.ThreadPolicy threadPolicy) throws IOException {
    String[] dependencies = getDependencies(soFile);
    Log.d(SoLoader.TAG, "Loading lib dependencies: " + Arrays.toString(dependencies));
    for (String dependency : dependencies) {
      if (dependency.startsWith("/")) {
        continue;
      }

      SoLoader.loadLibraryBySoName(
          dependency, loadFlags | LOAD_FLAG_ALLOW_IMPLICIT_PROVISION, threadPolicy);
    }
  }

  private static String[] getDependencies(File soFile) throws IOException {
    if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
      Api18TraceUtils.beginTraceSection("SoLoader.getElfDependencies[", soFile.getName(), "]");
    }
    try {
      return MinElf.extract_DT_NEEDED(soFile);
    } finally {
      if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
        Api18TraceUtils.endSection();
      }
    }
  }

  @Override
  @Nullable
  public File unpackLibrary(String soName) throws IOException {
    File soFile = new File(soDirectory, soName);
    if (soFile.exists()) {
      return soFile;
    }

    return null;
  }

  @Override
  public void addToLdLibraryPath(Collection<String> paths) {
    paths.add(soDirectory.getAbsolutePath());
  }

  @Override
  public String toString() {
    String path;
    try {
      path = String.valueOf(soDirectory.getCanonicalPath());
    } catch (IOException e) {
      path = soDirectory.getName();
    }
    return new StringBuilder()
        .append(getClass().getName())
        .append("[root = ")
        .append(path)
        .append(" flags = ")
        .append(flags)
        .append(']')
        .toString();
  }
}
