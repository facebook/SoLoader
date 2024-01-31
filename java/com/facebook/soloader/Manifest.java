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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Manifest {
  public final String arch;
  public final List<String> libs;

  Manifest(String arch, List<String> libs) {
    this.arch = arch;
    this.libs = Collections.unmodifiableList(libs);
  }

  public static Manifest read(InputStream input) throws IOException {
    return read(new DataInputStream(input));
  }

  public static Manifest read(DataInputStream data) throws IOException {
    String arch = readArch(data);

    int numOfLibs = ((int) data.readShort()) & 0xFFFF;
    ArrayList<String> libs = new ArrayList<>();
    for (int i = 0; i < numOfLibs; ++i) {
      libs.add(readLib(data));
    }

    return new Manifest(arch, libs);
  }

  private static String readArch(DataInputStream data) throws IOException {
    int arch = data.readByte();
    switch (arch) {
      case 1:
        return MinElf.ISA.AARCH64;
      case 2:
        return MinElf.ISA.ARM;
      case 3:
        return MinElf.ISA.X86_64;
      case 4:
        return MinElf.ISA.X86;
    }
    throw new RuntimeException("Unrecognized arch id: " + arch);
  }

  private static String readLib(DataInputStream data) throws IOException {
    int nameLength = ((int) data.readShort()) & 0xFFFF;
    byte[] name = new byte[nameLength];
    data.readFully(name);
    return new String(name, StandardCharsets.UTF_8);
  }
}
