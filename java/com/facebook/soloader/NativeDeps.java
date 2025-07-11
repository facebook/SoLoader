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

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.StrictMode;
import com.facebook.soloader.observer.ObserverHolder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;

/**
 * Class for getting native libraries dependencies for a DSO. Dependencies are read either from a
 * file specifying all dependencies, or from the ELF file itself.
 */
public final class NativeDeps {

  private static final int LIB_PREFIX_LEN = "lib".length();
  private static final int LIB_SUFFIX_LEN = ".so".length();
  private static final int LIB_PREFIX_SUFFIX_LEN = LIB_PREFIX_LEN + LIB_SUFFIX_LEN;
  private static final float HASHMAP_LOAD_FACTOR = 1f;
  private static final int INITIAL_HASH = 5381;
  private static final int WAITING_THREADS_WARNING_THRESHOLD = 3;
  private static final String LOG_TAG = "SoLoader[NativeDeps]";

  private static volatile boolean sInitialized = false;
  private static @Nullable byte[] sEncodedDeps;
  private static List<Integer> sPrecomputedLibs;
  private static Map<Integer, List<Integer>> sPrecomputedDeps;

  private static volatile boolean sUseDepsFileAsync = false;
  private static final ReentrantReadWriteLock sWaitForDepsFileLock = new ReentrantReadWriteLock();

  private static final HashSet<String> STANDARD_SYSTEM_LIBS =
      new HashSet<String>() {
        {
          add("libEGL.so");
          add("libGLESv2.so");
          add("libGLESv3.so");
          add("libOpenSLES.so");
          add("libandroid.so");
          add("libc.so");
          add("libdl.so");
          add("libjnigraphics.so");
          add("liblog.so");
          add("libm.so");
          add("libstdc++.so");
          add("libz.so");
        }
      };

  public static void loadDependencies(
      String soName, ElfByteChannel bc, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    String[] dependencies = getDependencies(soName, bc);
    LogUtil.d(
        SoLoader.TAG, "Loading " + soName + "'s dependencies: " + Arrays.toString(dependencies));
    for (String dependency : dependencies) {
      if (dependency.startsWith("/")) {
        continue;
      }

      if (STANDARD_SYSTEM_LIBS.contains(dependency)) {
        // The linker will implicitly load these by itself.
        continue;
      }

      SoLoader.loadDependency(dependency, loadFlags, threadPolicy);
    }
  }

  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  public static String[] getDependencies(String soName, ElfByteChannel bc) throws IOException {
    @Nullable Throwable failure = null;
    if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
      Api18TraceUtils.beginTraceSection("soloader.NativeDeps.getDependencies[", soName, "]");
    }
    ObserverHolder.onGetDependenciesStart();
    try {
      String[] deps = awaitGetDepsFromPrecomputedDeps(soName);
      if (deps != null) {
        return deps;
      }
      LogUtil.w(
          LOG_TAG,
          "Falling back to custom ELF parsing when loading " + soName + ", this can be slow");
      return MinElf.extract_DT_NEEDED(bc);
    } catch (MinElf.ElfError err) {
      UnsatisfiedLinkError ule = SoLoaderULErrorFactory.create(soName, err);
      failure = ule;
      throw ule;
    } catch (Error | RuntimeException t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onGetDependenciesEnd(failure);
      if (SoLoader.SYSTRACE_LIBRARY_LOADING) {
        Api18TraceUtils.endSection();
      }
    }
  }

  @Nullable
  private static String[] awaitGetDepsFromPrecomputedDeps(String soName) {
    if (sInitialized) {
      return tryGetDepsFromPrecomputedDeps(soName);
    }
    if (!sUseDepsFileAsync) {
      return null;
    }
    sWaitForDepsFileLock.readLock().lock();
    try {
      return tryGetDepsFromPrecomputedDeps(soName);
    } finally {
      sWaitForDepsFileLock.readLock().unlock();
    }
  }

  /**
   * Enables fetching dependencies from a deps file expected to be in the APK. If enabled,
   * dependencies will be looked up in the deps file instead of extracting them from the ELF file.
   * The file is read only once when getDependencies is first called. If dependencies for a specific
   * library are not found or the file is corrupt, we fall back to extracting the dependencies from
   * the ELF file.
   *
   * @param context Application context, used to find apk file and data directory.
   * @return true if initialization succeeded, false otherwise. If async, always returns true.
   */
  public static boolean useDepsFile(final Context context) {
    boolean success;
    try {
      success = initDeps(context);
    } catch (IOException e) {
      // File does not exist or reading failed for some reason. We need to make
      // sure the file was extracted from the APK.
      success = false;
    }

    if (!success) {
      LogUtil.w(
          LOG_TAG,
          "Failed to extract native deps from APK, falling back to using MinElf to get library"
              + " dependencies.");
    }

    return success;
  }

  public static boolean useDepsFileWithAssetManager(final Context context) {
    try {
      verifyUninitialized();
      byte[] depsBytes = NativeDepsReader.readNativeDepsFromApk(context);
      if (depsBytes == null) {
        LogUtil.w(LOG_TAG, "depsBytes is null");
      }

      return processDepsBytes(depsBytes);
    } catch (IOException e) {
      LogUtil.w(
          LOG_TAG,
          "Failed to use native deps file in APK, falling back to using MinElf to get library"
              + " dependencies:"
              + e.getMessage());
      return false;
    }
  }

  private static boolean initDeps(final Context context) throws IOException {
    verifyUninitialized();
    byte[] depsBytes = NativeDepsUnpacker.readNativeDepsFromApk(context);

    return processDepsBytes(depsBytes);
  }

  // Given the offset where the dependencies for a library begin in
  // sEncodedDeps, stores indices for mapping from lib name hash to offset,
  // and from lib index to offset.
  private static void indexLib(int hash, int nameBegin) {
    sPrecomputedLibs.add(nameBegin);
    List<Integer> bucket = sPrecomputedDeps.get(hash);
    if (bucket == null) {
      bucket = new ArrayList<Integer>();
      sPrecomputedDeps.put(hash, bucket);
    }
    bucket.add(nameBegin);
  }

  // Preprocess bytes from deps file. The deps file should be ascii encoded,
  // with the format:
  // <lib1_name> [dep_index1 dep_index2 ...]\n
  // <lib2_name> [dep_index1 dep_index2 ...]\n
  //
  // library names must not be prefixed with "lib" or suffixed with ".so".
  // dep_index is the 0-based index of the dependency in the deps file.
  //
  // We want to process the deps fast since the deps file can be large.
  // To do this fast, we read the bytes into memory, we store the
  // offsets in which each library starts, and we hash each library name
  // and map it to its offset.
  private static void indexDepsBytes(byte[] bytes, int offset) {
    boolean inLibName = true;
    int byteOffset = offset;
    int libHash = 0;
    int libNameBegin = 0;
    try {
      while (true) {
        if (inLibName) {
          libNameBegin = byteOffset;
          int nextByte;
          libHash = INITIAL_HASH;
          while ((nextByte = bytes[byteOffset]) > ' ') {
            libHash = ((libHash << 5) + libHash) + nextByte;
            ++byteOffset;
          }
          indexLib(libHash, libNameBegin);
          inLibName = nextByte != ' ';
        } else {
          while (bytes[byteOffset] != '\n') {
            ++byteOffset;
          }
          inLibName = true;
        }
        ++byteOffset;
      }
    } catch (IndexOutOfBoundsException e) {
      if (inLibName && libNameBegin != bytes.length) {
        indexLib(libHash, libNameBegin);
      }
    }
  }

  private static int findNextLine(byte[] bytes, int offset) {
    while (offset < bytes.length && bytes[offset] != '\n') {
      offset++;
    }

    if (offset < bytes.length) {
      offset++;
    }

    return offset;
  }

  private static int parseLibCount(byte[] data, int offset, int length) {
    try {
      return Integer.parseInt(new String(data, offset, length));
    } catch (NumberFormatException e) {
      // Invalid data
      return -1;
    }
  }

  static boolean processDepsBytes(byte[] deps) throws IOException {
    int offset = 0;

    int deps_offset = findNextLine(deps, offset);
    if (deps_offset >= deps.length) {
      LogUtil.w(
          LOG_TAG,
          "Invalid native deps file, deps_offset ("
              + deps_offset
              + ") >= length ("
              + deps.length
              + ")");
      return false;
    }

    int libsCount = parseLibCount(deps, offset, deps_offset - offset - 1);
    if (libsCount <= 0) {
      LogUtil.w(LOG_TAG, "Invalid native deps file, libsCount=" + libsCount);
      return false;
    }

    sPrecomputedDeps =
        new HashMap<>((int) (libsCount / HASHMAP_LOAD_FACTOR) + 1, HASHMAP_LOAD_FACTOR);
    sPrecomputedLibs = new ArrayList<>(libsCount);
    indexDepsBytes(deps, deps_offset);

    if (sPrecomputedLibs.size() != libsCount) {
      LogUtil.w(
          LOG_TAG,
          "Invalid native deps file, precomputed libs size ("
              + sPrecomputedLibs.size()
              + ") != libsCount ("
              + libsCount
              + ")");
      return false;
    }

    sEncodedDeps = deps;
    sInitialized = true;
    return true;
  }

  // Returns whether soName is encoded at a specified offset in sEncodedDeps
  private static boolean libIsAtOffset(String soName, int offset) {
    int i, j;
    for (i = LIB_PREFIX_LEN, j = offset;
        i < soName.length() - LIB_SUFFIX_LEN && j < sEncodedDeps.length;
        i++, j++) {
      if ((soName.codePointAt(i) & 0xFF) != sEncodedDeps[j]) {
        break;
      }
    }
    return i == soName.length() - LIB_SUFFIX_LEN;
  }

  // Hashes the name of a library
  private static int hashLib(String soName) {
    int libHash = INITIAL_HASH;
    for (int i = LIB_PREFIX_LEN; i < soName.length() - LIB_SUFFIX_LEN; ++i) {
      libHash = ((libHash << 5) + libHash) + soName.codePointAt(i);
    }
    return libHash;
  }

  // Returns the byte offset in sEncodedDeps for where dependencies for
  // soName start.
  // Returns -1 if soName does not exist in the deps file
  private static int getOffsetForLib(String soName) {
    int libHash = hashLib(soName);
    List<Integer> bucket = sPrecomputedDeps.get(libHash);
    if (bucket == null) {
      return -1;
    }

    for (int offset : bucket) {
      if (libIsAtOffset(soName, offset)) {
        return offset;
      }
    }

    return -1;
  }

  // Given the index of a library in the dependency file, returns a String with
  // the name of that library. The string is prefixed with "lib" and suffixed
  // with ".so".
  private static @Nullable String getLibString(int depIndex) {
    if (depIndex >= sPrecomputedLibs.size()) {
      return null;
    }

    int initialOffset = sPrecomputedLibs.get(depIndex);
    int byteOffset = initialOffset;
    while (byteOffset < sEncodedDeps.length && sEncodedDeps[byteOffset] > ' ') {
      ++byteOffset;
    }

    int libNameLength = (byteOffset - initialOffset) + LIB_PREFIX_SUFFIX_LEN;
    char[] libBytes = new char[libNameLength];
    libBytes[0] = 'l';
    libBytes[1] = 'i';
    libBytes[2] = 'b';
    for (int i = 0; i < libNameLength - LIB_PREFIX_SUFFIX_LEN; ++i) {
      libBytes[LIB_PREFIX_LEN + i] = (char) sEncodedDeps[initialOffset + i];
    }
    libBytes[libNameLength - 3] = '.';
    libBytes[libNameLength - 2] = 's';
    libBytes[libNameLength - 1] = 'o';

    return new String(libBytes);
  }

  private static @Nullable String[] getDepsForLibAtOffset(int offset, int libNameLength) {
    List<String> deps = new ArrayList<>();
    int depIndex = 0;
    boolean hasDep = false;
    int depsOffset = offset + libNameLength - LIB_PREFIX_SUFFIX_LEN;
    int nextByte;
    for (int byteOffset = depsOffset;
        byteOffset < sEncodedDeps.length && ((nextByte = sEncodedDeps[byteOffset]) != '\n');
        byteOffset++) {
      if (nextByte == ' ') {
        if (hasDep) {
          String dep = getLibString(depIndex);
          if (dep == null) {
            return null;
          }
          deps.add(dep);
          hasDep = false;
          depIndex = 0;
        }
        continue;
      }

      if (nextByte < '0' || nextByte > '9') {
        // Deps are corrupt
        return null;
      }

      hasDep = true;
      depIndex = depIndex * 10 + (nextByte - '0');
    }

    if (hasDep) {
      String dep = getLibString(depIndex);
      if (dep == null) {
        return null;
      }
      deps.add(dep);
    }

    if (deps.isEmpty()) {
      // If a library has no dependencies, then we don't know its
      // dependencies, it was just listed in the native deps file because
      // another library depends on this library.
      return null;
    }

    String[] depsArray = new String[deps.size()];
    return deps.toArray(depsArray);
  }

  @Nullable
  static String[] tryGetDepsFromPrecomputedDeps(String soName) {
    if (!sInitialized) {
      return null;
    }

    if (soName.length() <= LIB_PREFIX_SUFFIX_LEN) {
      LogUtil.w(LOG_TAG, "Invalid soName: " + soName);
      // soName must start with "lib" prefix and end with ".so" prefix, so
      // the length of the string must be at least 7.
      return null;
    }

    int offset = getOffsetForLib(soName);
    if (offset == -1) {
      LogUtil.w(LOG_TAG, "Couldn't find " + soName + " in native deps file");
      return null;
    }

    return getDepsForLibAtOffset(offset, soName.length());
  }

  private static void verifyUninitialized() {
    if (!sInitialized) {
      return;
    }

    synchronized (NativeDeps.class) {
      if (sInitialized) {
        throw new IllegalStateException(
            "Trying to initialize NativeDeps but it was already initialized");
      }
    }
  }
}
