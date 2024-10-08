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
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import javax.annotation.Nullable;

/** {@link SoSource} that finds shared libraries in a given directory. */
public class DirectorySoSource extends SoSource {

  public static final int RESOLVE_DEPENDENCIES = 1;
  public static final int ON_LD_LIBRARY_PATH = 2;

  protected final File soDirectory;
  protected int flags;

  /**
   * Make a new DirectorySoSource. If {@code flags} contains {@link
   * DirectorySoSource#RESOLVE_DEPENDENCIES}, recursively load dependencies for shared objects
   * loaded from this directory. (We shouldn't need to resolve dependencies for libraries loaded
   * from system directories: the dynamic linker is smart enough to do it on its own there.)
   *
   * @param soDirectory the dir that contains the so files
   * @param flags load flags
   */
  public DirectorySoSource(File soDirectory, int flags) {
    this.soDirectory = soDirectory;
    this.flags = flags;
  }

  public void setExplicitDependencyResolution() {
    flags |= RESOLVE_DEPENDENCIES;
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

    File soFile = getSoFileByName(soName);
    if (soFile == null) {
      LogUtil.v(SoLoader.TAG, soName + " file not found on " + libDir.getCanonicalPath());
      return LOAD_RESULT_NOT_FOUND;
    }

    String soFileCanonicalPath = soFile.getCanonicalPath();
    LogUtil.d(SoLoader.TAG, soName + " file found at " + soFileCanonicalPath);
    if ((loadFlags & LOAD_FLAG_ALLOW_IMPLICIT_PROVISION) != 0
        && (flags & ON_LD_LIBRARY_PATH) != 0) {
      LogUtil.d(SoLoader.TAG, soName + " loaded implicitly");
      return LOAD_RESULT_IMPLICITLY_PROVIDED;
    }

    boolean shouldLoadDependencies = (flags & RESOLVE_DEPENDENCIES) != 0;
    if (shouldLoadDependencies) {
      try (ElfByteChannel bc = new ElfFileChannel(soFile)) {
        NativeDeps.loadDependencies(soName, bc, loadFlags, threadPolicy);
      }
    } else {
      LogUtil.d(SoLoader.TAG, "Not resolving dependencies for " + soName);
    }

    try {
      SoLoader.sSoFileLoader.load(soFileCanonicalPath, loadFlags);
    } catch (UnsatisfiedLinkError e) {
      throw SoLoaderULErrorFactory.create(soName, e);
    }
    return LOAD_RESULT_LOADED;
  }

  @Override
  @Nullable
  public File getSoFileByName(String soName) throws IOException {
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

    try (ElfFileChannel bc = new ElfFileChannel(soFile)) {
      return NativeDeps.getDependencies(soName, bc);
    }
  }

  @Override
  @Nullable
  public File unpackLibrary(String soName) throws IOException {
    return getSoFileByName(soName);
  }

  @Override
  public void addToLdLibraryPath(Collection<String> paths) {
    try {
      paths.add(soDirectory.getCanonicalPath());
    } catch (IOException ex) {
      LogUtil.e(
          SoLoader.TAG,
          "Failed to get canonical path for "
              + soDirectory.getName()
              + " due to "
              + ex.toString()
              + ", falling to the absolute one");
      paths.add(soDirectory.getAbsolutePath());
    }
  }

  @Override
  public String getName() {
    return "DirectorySoSource";
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
        .append(getName())
        .append("[root = ")
        .append(path)
        .append(" flags = ")
        .append(flags)
        .append(']')
        .toString();
  }
}
