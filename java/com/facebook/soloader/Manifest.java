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
import javax.annotation.Nullable;

public class Manifest {
  public static final class Library {
    public static final int FLAG_UNKNOWN_DEPS = 1 << 0;
    public final String name;
    public final int flags;

    public Library(String name, int flags) {
      this.name = name;
      this.flags = flags;
    }

    public boolean hasUnknownDeps() {
      return (flags & FLAG_UNKNOWN_DEPS) != 0;
    }

    private static Library read(DataInputStream data) throws IOException {
      int nameLength = ((int) data.readShort()) & 0xFFFF;
      byte[] name = new byte[nameLength];
      data.readFully(name);
      int flags = ((int) data.readByte()) & 0xFF;
      return new Library(new String(name, StandardCharsets.UTF_8), flags);
    }

    @Override
    public boolean equals(@Nullable Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Library)) {
        return false;
      }

      Library library = (Library) o;

      if (flags != library.flags) {
        return false;
      }
      return name.equals(library.name);
    }

    @Override
    public int hashCode() {
      int result = name.hashCode();
      result = 31 * result + flags;
      return result;
    }
  }

  public final String abi;
  public final List<Library> libs;

  Manifest(String abi, List<Library> libs) {
    this.abi = abi;
    this.libs = Collections.unmodifiableList(libs);
  }

  public static Manifest read(InputStream input) throws IOException {
    return read(new DataInputStream(input));
  }

  public static Manifest read(DataInputStream data) throws IOException {
    String abi = readAbi(data);

    int numOfLibs = ((int) data.readShort()) & 0xFFFF;
    ArrayList<Library> libs = new ArrayList<>();
    for (int i = 0; i < numOfLibs; ++i) {
      libs.add(Library.read(data));
    }

    return new Manifest(abi, libs);
  }

  private static String readAbi(DataInputStream data) throws IOException {
    int abi = data.readByte();
    switch (abi) {
      case 1:
        return MinElf.ISA.AARCH64;
      case 2:
        return MinElf.ISA.ARM;
      case 3:
        return MinElf.ISA.X86_64;
      case 4:
        return MinElf.ISA.X86;
    }
    throw new RuntimeException("Unrecognized abi id: " + abi);
  }
}
