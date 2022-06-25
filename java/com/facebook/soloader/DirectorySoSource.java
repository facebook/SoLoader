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

import android.os.StrictMode;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.annotation.Nullable;

/** {@link SoSource} that finds shared libraries in a given directory. */
public class DirectorySoSource extends SoSource {

  public static final int RESOLVE_DEPENDENCIES = 1;
  public static final int ON_LD_LIBRARY_PATH = 2;

  protected final File soDirectory;
  protected final int flags;
  protected final List<String> denyList;

  /**
   * Make a new DirectorySoSource. If {@code flags} contains {@code RESOLVE_DEPENDENCIES},
   * recursively load dependencies for shared objects loaded from this directory. (We shouldn't need
   * to resolve dependencies for libraries loaded from system directories: the dynamic linker is
   * smart enough to do it on its own there.)
   */
  public DirectorySoSource(File soDirectory, int flags) {
    this(soDirectory, flags, new String[0]);
  }

  /**
   * This method is similar to {@link #DirectorySoSource(File, int)}, with the following
   * differences:
   *
   * @param denyList the soname list that we won't try to load from this source
   */
  public DirectorySoSource(File soDirectory, int flags, String[] denyList) {
    this.soDirectory = soDirectory;
    this.flags = flags;
    this.denyList = Arrays.asList(denyList);
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
    if (SoLoader.sSoFileLoader == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }

    if (denyList.contains(soName)) {
      Log.d(
          SoLoader.TAG,
          soName + " is on the denyList, skip loading from " + libDir.getCanonicalPath());
      return LOAD_RESULT_NOT_FOUND;
    }

    File soFile = getSoFileByName(soName);
    if (soFile == null) {
      Log.v(SoLoader.TAG, soName + " not found on " + libDir.getCanonicalPath());
      return LOAD_RESULT_NOT_FOUND;
    } else {
      Log.d(SoLoader.TAG, soName + " found on " + libDir.getCanonicalPath());
    }
    if ((loadFlags & LOAD_FLAG_ALLOW_IMPLICIT_PROVISION) != 0
        && (flags & ON_LD_LIBRARY_PATH) != 0) {
      Log.d(SoLoader.TAG, soName + " loaded implicitly");
      return LOAD_RESULT_IMPLICITLY_PROVIDED;
    }

    ElfByteChannel bc = null;
    boolean shouldLoadDependencies = (flags & RESOLVE_DEPENDENCIES) != 0;
    boolean shouldLoadFromFile = soFile.getName().equals(soName);
    try {
      if (shouldLoadDependencies || !shouldLoadFromFile) {
        bc = getChannel(soFile);
      }

      if (shouldLoadDependencies) {
        loadDependencies(soName, bc, loadFlags, threadPolicy);
      } else {
        Log.d(SoLoader.TAG, "Not resolving dependencies for " + soName);
      }

      try {
        if (shouldLoadFromFile) {
          SoLoader.sSoFileLoader.load(soFile.getAbsolutePath(), loadFlags);
        } else {
          // The shared object does not exist in the file system, only in memory
          SoLoader.sSoFileLoader.loadBytes(soFile.getAbsolutePath(), bc, loadFlags);
        }

      } catch (UnsatisfiedLinkError e) {
        if (e.getMessage().contains("bad ELF magic")) {
          Log.d(SoLoader.TAG, "Corrupted lib file detected");
          // Swallow exception. Higher layers will try again from a backup source
          return LOAD_RESULT_CORRUPTED_LIB_FILE;
        } else {
          throw e;
        }
      }
    } finally {
      if (bc != null) {
        bc.close();
      }
    }

    return LOAD_RESULT_LOADED;
  }

  @Override
  @Nullable
  protected File getSoFileByName(String soName) throws IOException {
    File soFile = new File(soDirectory, soName);
    if (soFile.exists()) {
      return soFile;
    }
    return null;
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    File soFile = getSoFileByName(soName);
    if (soFile == null) {
      return null;
    }
    return soFile.getCanonicalPath();
  }

  @Nullable
  @Override
  public String[] getLibraryDependencies(String soName) throws IOException {
    File soFile = getSoFileByName(soName);
    if (soFile == null) {
      return null;
    }

    try (ElfByteChannel bc = getChannel(soFile)) {
      return getDependencies(soName, bc);
    }
  }

  private void loadDependencies(
      String soName, ElfByteChannel bc, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    String[] dependencies = getDependencies(soName, bc);
    Log.d(SoLoader.TAG, "Loading " + soName + "'s dependencies: " + Arrays.toString(dependencies));
    for (String dependency : dependencies) {
      if (dependency.startsWith("/")) {
        continue;
      }

      SoLoader.loadLibraryBySoName(
          dependency, loadFlags | LOAD_FLAG_ALLOW_IMPLICIT_PROVISION, threadPolicy);
    }
  }

  protected ElfByteChannel getChannel(File soFile) throws IOException {
    return new ElfFileChannel(soFile);
  }

  protected String[] getDependencies(String soName, ElfByteChannel bc) throws IOException {
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

  @Override
  @Nullable
  public File unpackLibrary(String soName) throws IOException {
    return getSoFileByName(soName);
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
