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
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import javax.annotation.Nullable;

public abstract class SoSource {

  /** This SoSource doesn't know how to provide the given library. */
  public static final int LOAD_RESULT_NOT_FOUND = 0;

  /** This SoSource loaded the given library. */
  public static final int LOAD_RESULT_LOADED = 1;

  /**
   * This SoSource did not load the library, but verified that the system loader will load it if
   * some other library depends on it. Returned only if LOAD_FLAG_ALLOW_IMPLICIT_PROVISION is
   * provided to loadLibrary.
   */
  public static final int LOAD_RESULT_IMPLICITLY_PROVIDED = 2;

  /** This SoSource tried to load the library but it seems that the file is corrupted. */
  public static final int LOAD_RESULT_CORRUPTED_LIB_FILE = 3;

  /** Allow loadLibrary to implicitly provide the library instead of actually loading it. */
  public static final int LOAD_FLAG_ALLOW_IMPLICIT_PROVISION = 1;

  /** Allow loadLibrary to reparse the so sources directories. */
  @Deprecated public static final int LOAD_FLAG_ALLOW_SOURCE_CHANGE = 1 << 1;

  /**
   * Min flag that can be used in customized {@link SoFileLoader#load(String, int)} implementation.
   * The custom flag value has to be greater than this.
   */
  public static final int LOAD_FLAG_MIN_CUSTOM_FLAG = 1 << 2;

  /** Allow prepare to spawn threads to do background work. */
  public static final int PREPARE_FLAG_ALLOW_ASYNC_INIT = (1 << 0);

  /** Force prepare to refresh libs. */
  public static final int PREPARE_FLAG_FORCE_REFRESH = (1 << 1);

  /** Prepare to install this SoSource in SoLoader. */
  protected void prepare(int flags) throws IOException {
    /* By default, do nothing */
  }

  /**
   * Load a shared library library into this process. This routine is independent of {@link
   * #loadLibrary}.
   *
   * @param soName Name of library to load
   * @param loadFlags Zero or more of the LOAD_FLAG_XXX constants.
   * @return One of the LOAD_RESULT_XXX constants.
   */
  public abstract int loadLibrary(
      String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy) throws IOException;

  /**
   * Ensure that a shared library exists on disk somewhere. This routine is independent of {@link
   * #loadLibrary}.
   *
   * @param soName Name of library to load
   * @return File if library found; {@code null} if not.
   */
  @Nullable
  public abstract File unpackLibrary(String soName) throws IOException;

  /**
   * Gets the full path of a library if it is found on this SoSource.
   *
   * @param soFileName the full file name of the library
   * @return the full path of a library if it is found on this SoSource, null otherwise.
   * @throws IOException if there is an error calculating {@code soFileName}'s canonical path
   */
  @Nullable
  public String getLibraryPath(String soFileName) throws IOException {
    return null;
  }

  /**
   * Gets the dependencies of a library if it is found on this SoSource
   *
   * @param soName Name of library to inspect
   * @return An array of library names upon which {@code soName} needs for linking
   * @throws IOException if {@code soName} is found but there is an error reading it
   */
  @Nullable
  public String[] getLibraryDependencies(String soName) throws IOException {
    return null;
  }

  /**
   * Add an element to an LD_LIBRARY_PATH under construction.
   *
   * @param paths Collection of paths to which to add
   */
  public void addToLdLibraryPath(Collection<String> paths) {
    /* By default, do nothing */
  }

  /**
   * Return an array of ABIs handled by this SoSource.
   *
   * @return ABIs supported by this SoSource
   */
  public String[] getSoSourceAbis() {
    /* By default, the same as the device */
    return SysUtil.getSupportedAbis();
  }

  /**
   * Return the class name of the actual instance. Useful for debugging.
   *
   * @return the instance class name
   */
  @Override
  public String toString() {
    return getClass().getName();
  }
}
