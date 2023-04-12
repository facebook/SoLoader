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

// Java structures that contain the offsets of fields in various ELF64 ABI structures.
// com.facebook.soloader.MinElf uses these structures while parsing ELF64 files.
final class Elf64 {
  static class Dyn { // Dynamic section
    public static final int D_TAG = 0x0; // Tag, controls the interpretation of d_un
    public static final int D_UN = 0x8; // Union that contains the actual value of the dynamic entry
  }

  static class Ehdr { // ELF header
    public static final int E_IDENT = 0x0; // ELF identification bytes
    public static final int E_TYPE = 0x10; // Object file type
    public static final int E_MACHINE = 0x12; // Architecture type
    public static final int E_VERSION = 0x14; // Object file version
    public static final int E_ENTRY = 0x18; // Entry point address
    public static final int E_PHOFF = 0x20; // Program header offset
    public static final int E_SHOFF = 0x28; // Section header offset
    public static final int E_FLAGS = 0x30; // Processor-specific flags
    public static final int E_EHSIZE = 0x34; // ELF header size
    public static final int E_PHENTSIZE = 0x36; // Size of program header entry
    public static final int E_PHNUM = 0x38; // Number of program header entries
    public static final int E_SHENTSIZE = 0x3a; // Size of section header entry
    public static final int E_SHNUM = 0x3c; // Number of section header entries
    public static final int E_SHSTRNDX = 0x3e; // Section name string table index
  }

  static class Phdr { // Program Header
    public static final int P_TYPE = 0x0; // Segment type
    public static final int P_FLAGS = 0x4; // Segment flags
    public static final int P_OFFSET = 0x8; // Segment file offset
    public static final int P_VADDR = 0x10; // Segment virtual address
    public static final int P_PADDR = 0x18; // Segment physical address
    public static final int P_FILESZ = 0x20; // Segment size in file
    public static final int P_MEMSZ = 0x28; // Segment size in memory
    public static final int P_ALIGN = 0x30; // Segment alignment
  }

  static class Shdr { // Section Header
    public static final int SH_NAME = 0x0; // Section name (string table index)
    public static final int SH_TYPE = 0x4; // Section type
    public static final int SH_FLAGS = 0x8; // Section flags
    public static final int SH_ADDR = 0x10; // Section virtual address
    public static final int SH_OFFSET = 0x18; // Section file offset
    public static final int SH_SIZE = 0x20; // Section size in bytes
    public static final int SH_LINK = 0x28; // Section header table index link
    public static final int SH_INFO = 0x2c; // Section extra information
    public static final int SH_ADDRALIGN = 0x30; // Section address alignment
    public static final int SH_ENTSIZE = 0x38; // Section entry size
  }
}
