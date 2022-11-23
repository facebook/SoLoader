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

public interface SoFileLoader {

  /**
   * Load the so file from given path.
   *
   * @param pathToSoFile so file path
   * @param loadFlags loadFlags
   */
  void load(String pathToSoFile, int loadFlags);

  /**
   * Load the so from memory.
   *
   * @param pathName Name of the so file used to distinguish it from other loaded shared objects. If
   *     the file is compressed in a file, the compressed file can be used as a name.
   * @param bytes An elf byte channel containing the bytes representing the shared object to be
   *     loaded.
   * @param loadFlags SoLoader flags
   */
  void loadBytes(String pathName, ElfByteChannel bytes, int loadFlags);
}
