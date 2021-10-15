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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Class for getting native libraries dependencies for a DSO. Dependencies are read either from a
 * file specifying all dependencies, or from the ELF file itself.
 */
public final class NativeDeps {

  private static final int LIB_PREFIX_LEN = "lib".length();
  private static final int LIB_SUFFIX_LEN = ".so".length();
  private static final int LIB_PREFIX_SUFFIX_LEN = LIB_PREFIX_LEN + LIB_SUFFIX_LEN;
  private static final int DEFAULT_LIBS_CAPACITY = 512;

  private static boolean sInitialized = false;
  private static @Nullable byte[] sEncodedDeps;
  private static List<Integer> sPrecomputedLibs;
  private static Map<Integer, List<Integer>> sPrecomputedDeps;

  public static String[] getDependencies(String soName, File elfFile) throws IOException {
    if (sInitialized) {
      String[] deps = tryGetDepsFromPrecomputedDeps(soName);
      if (deps != null) {
        return deps;
      }
    }

    return MinElf.extract_DT_NEEDED(elfFile);
  }

  public static String[] getDependencies(String soName, ElfByteChannel bc) throws IOException {
    if (sPrecomputedDeps != null) {
      String[] deps = tryGetDepsFromPrecomputedDeps(soName);
      if (deps != null) {
        return deps;
      }
    }

    return MinElf.extract_DT_NEEDED(bc);
  }

  /**
   * Enables fetching dependencies from a deps file and specifies the path to the deps file. If
   * enabled, dependencies will be looked up in the deps file instead of extracting them from the
   * ELF file. The file is read only once when getDependencies is first called. If dependencies for
   * a specific library are not found or the file is corrupt, we fall back to extracting the
   * dependencies from the ELF file.
   */
  public static boolean useDepsFile(byte[] apkId, String depsFilePath) throws IOException {
    if (!sInitialized) {
      synchronized (NativeDeps.class) {
        if (!sInitialized) {
          return readDepsFromFile(apkId, depsFilePath);
        }
      }
    }

    throw new IllegalStateException(
        "Trying to initialize NativeDeps but it was already initialized");
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
          libHash = 5381;
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
      if (inLibName) {
        indexLib(libHash, libNameBegin);
      }
    }
  }

  private static int verifyBytesAndGetOffset(byte[] apkId, byte[] bytes) {
    if (apkId == null || apkId.length == 0) {
      return -1;
    }

    if (bytes.length < apkId.length + 4) {
      return -1;
    }

    int depsLen = ByteBuffer.wrap(bytes, apkId.length, 4).getInt();
    if (bytes.length != apkId.length + 4 + depsLen) {
      return -1;
    }

    for (int i = 0; i < apkId.length; ++i) {
      if (apkId[i] != bytes[i]) {
        return -1;
      }
    }

    return apkId.length + 4;
  }

  // Reads deps file and builds indexes to quickly get deps from libraries.
  private static boolean readDepsFromFile(byte[] apkId, String depsFilePath) throws IOException {
    try (FileInputStream in = new FileInputStream(depsFilePath)) {
      sEncodedDeps = new byte[in.available()];
      in.read(sEncodedDeps);
      int offset = verifyBytesAndGetOffset(apkId, sEncodedDeps);
      if (offset == -1) {
        sEncodedDeps = null;
        return false;
      }
      sPrecomputedDeps = new HashMap<>(DEFAULT_LIBS_CAPACITY);
      sPrecomputedLibs = new ArrayList<>(DEFAULT_LIBS_CAPACITY);
      indexDepsBytes(sEncodedDeps, offset);
    } catch (IOException e) {
      // Release bytes that are not needed anymore and propagate exception
      sEncodedDeps = null;
      throw e;
    }

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
    int libHash = 5381;
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

    String[] depsArray = new String[deps.size()];
    return deps.toArray(depsArray);
  }

  @Nullable
  private static String[] tryGetDepsFromPrecomputedDeps(String soName) {
    if (!sInitialized) {
      return null;
    }

    if (soName.length() <= LIB_PREFIX_SUFFIX_LEN) {
      // soName must start with "lib" prefix and end with ".so" prefix, so
      // the length of the string must be at least 7.
      return null;
    }

    int offset = getOffsetForLib(soName);
    if (offset == -1) {
      return null;
    }

    return getDepsForLibAtOffset(offset, soName.length());
  }
}
