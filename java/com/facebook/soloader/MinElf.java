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

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedByInterruptException;

/**
 * Extract SoLoader bootstrap information from an ELF file. This is not a general purpose ELF
 * library.
 *
 * <p>See specification at http://www.sco.com/developers/gabi/latest/contents.html. You will not be
 * able to verify the operation of the functions below without having read the ELF specification.
 */
public final class MinElf {

  private static final String TAG = "MinElf";

  public static interface ISA {
    public static final String X86 = "x86";
    public static final String ARM = "armeabi-v7a";
    public static final String X86_64 = "x86_64";
    public static final String AARCH64 = "arm64-v8a";
  }

  public static final int ELF_MAGIC = 0x464c457f;

  public static final int DT_NULL = 0;
  public static final int DT_NEEDED = 1;
  public static final int DT_STRTAB = 5;

  public static final int PT_LOAD = 1;
  public static final int PT_DYNAMIC = 2;

  public static final int PN_XNUM = 0xFFFF;

  public static String[] extract_DT_NEEDED(File elfFile) throws IOException {
    try (ElfFileChannel fc = new ElfFileChannel(elfFile)) {
      return extract_DT_NEEDED(fc);
    }
  }

  /**
   * Treating {@code fc} as an ELF file, extract all the DT_NEEDED entries from its dynamic section.
   *
   * @param fc ElfFileChannel referring to ELF file
   * @return Array of strings, one for each DT_NEEDED entry, in file order
   */
  private static String[] extract_DT_NEEDED_with_retries(ElfFileChannel fc) throws IOException {
    // An ElfFileChannel can be interrupted since it uses a FileChannel.
    // If it's interrupted, we will retry, we just need to reopen the FileChannel
    int failureCount = 0;
    while (true) {
      try {
        return extract_DT_NEEDED_no_retries(fc);
      } catch (ClosedByInterruptException e) {
        // Make sure we don't loop infinitely
        if (++failureCount > 4) {
          throw e;
        }

        // Some other thread interrupted us. We need to clear the interrupt
        // flag (via calling Thread.interrupted()) and try again. This is
        // especially important since this is often used within the context of
        // a static initializer. A failure here will get memoized resulting in
        // all future attempts to load the same class to fail.
        Thread.interrupted();
        LogUtil.e(TAG, "retrying extract_DT_NEEDED due to ClosedByInterruptException", e);
        // FileChannel gets closed after an interruption, we need to reopen the
        // channel.
        fc.openChannel();
      }
    }
  }

  /**
   * Treating {@code bc} as an ELF file, extract all the DT_NEEDED entries from its dynamic section.
   *
   * @param bc ElfByteChannel referring to ELF file
   * @return Array of strings, one for each DT_NEEDED entry, in file order
   * @throws IOException IOException
   */
  public static String[] extract_DT_NEEDED(ElfByteChannel bc) throws IOException {
    if (bc instanceof ElfFileChannel) {
      return extract_DT_NEEDED_with_retries((ElfFileChannel) bc);
    }
    return extract_DT_NEEDED_no_retries(bc);
  }

  private static String[] extract_DT_NEEDED_no_retries(ElfByteChannel bc) throws IOException {
    //
    // All constants below are fixed by the ELF specification and are the offsets of fields within
    // the elf.h data structures.
    //

    ByteBuffer bb = ByteBuffer.allocate(8 /* largest read unit */);

    // Read ELF header.

    bb.order(ByteOrder.LITTLE_ENDIAN);
    long magic = getu32(bc, bb, Elf32.Ehdr.E_IDENT);
    if (magic != ELF_MAGIC) {
      throw new ElfError(
          "file is not ELF: magic is 0x"
              + Long.toHexString(magic)
              + ", it should be "
              + Long.toHexString(ELF_MAGIC));
    }

    boolean is32 = (getu8(bc, bb, Elf32.Ehdr.E_IDENT + 0x4) == 1);
    if (getu8(bc, bb, Elf32.Ehdr.E_IDENT + 0x5) == 2) {
      bb.order(ByteOrder.BIG_ENDIAN);
    }

    // Offsets above are identical in 32- and 64-bit cases.

    // Find the offset of the dynamic linking information.

    long e_phoff = is32 ? getu32(bc, bb, Elf32.Ehdr.E_PHOFF) : get64(bc, bb, Elf64.Ehdr.E_PHOFF);

    long e_phnum = is32 ? getu16(bc, bb, Elf32.Ehdr.E_PHNUM) : getu16(bc, bb, Elf64.Ehdr.E_PHNUM);

    int e_phentsize =
        is32 ? getu16(bc, bb, Elf32.Ehdr.E_PHENTSIZE) : getu16(bc, bb, Elf64.Ehdr.E_PHENTSIZE);

    if (e_phnum == PN_XNUM) { // Overflowed into section[0].sh_info

      long e_shoff = is32 ? getu32(bc, bb, Elf32.Ehdr.E_SHOFF) : get64(bc, bb, Elf64.Ehdr.E_SHOFF);

      long sh_info =
          is32
              ? getu32(bc, bb, e_shoff + Elf32.Shdr.SH_INFO)
              : getu32(bc, bb, e_shoff + Elf64.Shdr.SH_INFO);

      e_phnum = sh_info;
    }

    long dynStart = 0;
    long phdr = e_phoff;

    for (long i = 0; i < e_phnum; ++i) {
      long p_type =
          is32
              ? getu32(bc, bb, phdr + Elf32.Phdr.P_TYPE)
              : getu32(bc, bb, phdr + Elf64.Phdr.P_TYPE);

      if (p_type == PT_DYNAMIC) {
        long p_offset =
            is32
                ? getu32(bc, bb, phdr + Elf32.Phdr.P_OFFSET)
                : get64(bc, bb, phdr + Elf64.Phdr.P_OFFSET);

        dynStart = p_offset;
        break;
      }

      phdr += e_phentsize;
    }

    if (dynStart == 0) {
      throw new ElfError("ELF file does not contain dynamic linking information");
    }

    // Walk the items in the dynamic section, counting the DT_NEEDED entries.  Also remember where
    // the string table for those entries lives.  That table is a pointer, which we translate to an
    // offset below.

    long d_tag;
    int nr_DT_NEEDED = 0;
    long dyn = dynStart;
    long ptr_DT_STRTAB = 0;

    do {
      d_tag = is32 ? getu32(bc, bb, dyn + Elf32.Dyn.D_TAG) : get64(bc, bb, dyn + Elf64.Dyn.D_TAG);

      if (d_tag == DT_NEEDED) {
        if (nr_DT_NEEDED == Integer.MAX_VALUE) {
          throw new ElfError("malformed DT_NEEDED section");
        }

        nr_DT_NEEDED += 1;
      } else if (d_tag == DT_STRTAB) {
        ptr_DT_STRTAB =
            is32 ? getu32(bc, bb, dyn + Elf32.Dyn.D_UN) : get64(bc, bb, dyn + Elf64.Dyn.D_UN);
      }

      dyn += is32 ? 8 : 16;
    } while (d_tag != DT_NULL);

    if (ptr_DT_STRTAB == 0) {
      throw new ElfError("Dynamic section string-table not found");
    }

    // Translate the runtime string table pointer we found above to a file offset.

    long off_DT_STRTAB = 0;
    phdr = e_phoff;

    for (int i = 0; i < e_phnum; ++i) {
      long p_type =
          is32
              ? getu32(bc, bb, phdr + Elf32.Phdr.P_TYPE)
              : getu32(bc, bb, phdr + Elf64.Phdr.P_TYPE);

      if (p_type == PT_LOAD) {
        long p_vaddr =
            is32
                ? getu32(bc, bb, phdr + Elf32.Phdr.P_VADDR)
                : get64(bc, bb, phdr + Elf64.Phdr.P_VADDR);

        long p_memsz =
            is32
                ? getu32(bc, bb, phdr + Elf32.Phdr.P_MEMSZ)
                : get64(bc, bb, phdr + Elf64.Phdr.P_MEMSZ);

        if (p_vaddr <= ptr_DT_STRTAB && ptr_DT_STRTAB < p_vaddr + p_memsz) {
          long p_offset =
              is32
                  ? getu32(bc, bb, phdr + Elf32.Phdr.P_OFFSET)
                  : get64(bc, bb, phdr + Elf64.Phdr.P_OFFSET);

          off_DT_STRTAB = p_offset + (ptr_DT_STRTAB - p_vaddr);
          break;
        }
      }

      phdr += e_phentsize;
    }

    if (off_DT_STRTAB == 0) {
      throw new ElfError("did not find file offset of DT_STRTAB table");
    }

    String[] needed = new String[nr_DT_NEEDED];

    nr_DT_NEEDED = 0;
    dyn = dynStart;

    do {
      d_tag = is32 ? getu32(bc, bb, dyn + Elf32.Dyn.D_TAG) : get64(bc, bb, dyn + Elf64.Dyn.D_TAG);

      if (d_tag == DT_NEEDED) {
        long d_val =
            is32 ? getu32(bc, bb, dyn + Elf32.Dyn.D_UN) : get64(bc, bb, dyn + Elf64.Dyn.D_UN);

        needed[nr_DT_NEEDED] = getSz(bc, bb, off_DT_STRTAB + d_val);
        if (nr_DT_NEEDED == Integer.MAX_VALUE) {
          throw new ElfError("malformed DT_NEEDED section");
        }

        nr_DT_NEEDED += 1;
      }

      dyn += is32 ? 8 : 16;
    } while (d_tag != DT_NULL);

    if (nr_DT_NEEDED != needed.length) {
      throw new ElfError("malformed DT_NEEDED section");
    }

    return needed;
  }

  private static String getSz(ElfByteChannel bc, ByteBuffer bb, long offset) throws IOException {
    StringBuilder sb = new StringBuilder();
    short b;
    while ((b = getu8(bc, bb, offset++)) != 0) {
      sb.append((char) b);
    }

    return sb.toString();
  }

  private static void read(ElfByteChannel bc, ByteBuffer bb, int sz, long offset)
      throws IOException {
    bb.position(0);
    bb.limit(sz);

    while (bb.remaining() > 0) {
      int numBytesRead = bc.read(bb, offset);
      if (numBytesRead == -1) {
        break; // Reached end of channel
      }
      offset += numBytesRead;
    }

    if (bb.remaining() > 0) {
      throw new ElfError("ELF file truncated");
    }

    bb.position(0);
  }

  private static long get64(ElfByteChannel bc, ByteBuffer bb, long offset) throws IOException {
    read(bc, bb, 8, offset);
    return bb.getLong();
  }

  private static long getu32(ElfByteChannel bc, ByteBuffer bb, long offset) throws IOException {
    read(bc, bb, 4, offset);
    return bb.getInt() & 0xFFFFFFFFL; // signed -> unsigned
  }

  private static int getu16(ElfByteChannel bc, ByteBuffer bb, long offset) throws IOException {
    read(bc, bb, 2, offset);
    return bb.getShort() & (int) 0xFFFF; // signed -> unsigned
  }

  private static short getu8(ElfByteChannel bc, ByteBuffer bb, long offset) throws IOException {
    read(bc, bb, 1, offset);
    return (short) (bb.get() & 0xFF); // signed -> unsigned
  }

  @DoNotStripAny
  protected static class ElfError extends UnsatisfiedLinkError {
    ElfError(String error) {
      super(error);
    }
  }
}
