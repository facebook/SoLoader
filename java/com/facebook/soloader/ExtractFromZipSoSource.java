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

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.Checksum;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

/** {@link SoSource} that extracts libraries from a zip file to the filesystem. */
public class ExtractFromZipSoSource extends UnpackingSoSource {
  private static final String TAG = "soloader.ExtractFromZipSoSource";

  protected final File mZipFileName;
  protected final String mZipSearchPattern;

  /**
   * @param context Application context
   * @param name Name of the DSO store
   * @param zipFileName Name of the zip file from which we extract; opened only on demand
   * @param zipSearchPattern Regular expression string matching DSOs in the zip file; subgroup 1
   *     must be an ABI (as from Build.CPU_ABI) and subgroup 2 must be the shared library basename.
   */
  public ExtractFromZipSoSource(
      Context context, String name, File zipFileName, String zipSearchPattern) {
    super(context, name);
    mZipFileName = zipFileName;
    mZipSearchPattern = zipSearchPattern;
  }

  public ExtractFromZipSoSource(
      Context context, File storePath, File zipFileName, String zipSearchPattern) {
    super(context, storePath);
    mZipFileName = zipFileName;
    mZipSearchPattern = zipSearchPattern;
  }

  @Override
  public String getName() {
    return "ExtractFromZipSoSource";
  }

  public boolean hasZippedLibs() throws IOException {
    try (ZipUnpacker u = new ZipUnpacker(this)) {
      return u.computeDsosFromZip().length != 0;
    }
  }

  @Override
  protected Unpacker makeUnpacker() throws IOException {
    return new ZipUnpacker(this);
  }

  protected class ZipUnpacker extends Unpacker {

    protected @Nullable ZipDso[] mDsos;
    private final ZipFile mZipFile;
    private final UnpackingSoSource mSoSource;

    ZipUnpacker(final UnpackingSoSource soSource) throws IOException {
      mZipFile = new ZipFile(mZipFileName);
      mSoSource = soSource;
    }

    ZipDso[] computeDsosFromZip() {
      LinkedHashSet<String> librariesAbiSet = new LinkedHashSet<>();
      HashMap<String, ZipDso> providedLibraries = new HashMap<>();
      Pattern zipSearchPattern = Pattern.compile(mZipSearchPattern);
      String[] supportedAbis = SysUtil.getSupportedAbis();
      Enumeration<? extends ZipEntry> entries = mZipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        Matcher m = zipSearchPattern.matcher(entry.getName());
        if (!m.matches()) {
          continue;
        }
        int soNameIdx = m.groupCount();
        String libraryAbi = m.group(soNameIdx - 1);
        String soName = m.group(soNameIdx);
        int abiScore = SysUtil.findAbiScore(supportedAbis, libraryAbi);
        if (abiScore < 0) {
          continue;
        }
        librariesAbiSet.add(libraryAbi);
        ZipDso so = providedLibraries.get(soName);
        if (so == null || abiScore < so.abiScore) {
          providedLibraries.put(soName, new ZipDso(soName, entry, abiScore));
        }
      }

      mSoSource.setSoSourceAbis(librariesAbiSet.toArray(new String[librariesAbiSet.size()]));

      ZipDso[] dsos = providedLibraries.values().toArray(new ZipDso[providedLibraries.size()]);
      Arrays.sort(dsos);
      return dsos;
    }

    /**
     * If the list of zip DSOs is not created, generate that by iterating through all zip entries
     * and doing pattern matching against a zipped libs pattern.
     *
     * @return mDsos
     */
    ZipDso[] getExtractableDsosFromZip() {
      if (mDsos != null) {
        return mDsos;
      }

      mDsos = computeDsosFromZip();
      return mDsos;
    }

    @Override
    public void close() throws IOException {
      mZipFile.close();
    }

    @Override
    public final Dso[] getDsos() throws IOException {
      return getExtractableDsosFromZip();
    }

    @Override
    public void unpack(File soDirectory) throws IOException {
      ZipDso[] dsos = getExtractableDsosFromZip();
      byte[] ioBuffer = new byte[32 * 1024];
      for (ZipDso zipDso : dsos) {
        InputStream is = mZipFile.getInputStream(zipDso.backingEntry);
        try (InputDso inputDso = new InputDso(zipDso, is)) {
          is = null; // Transfer ownership to inputDso
          extractDso(inputDso, ioBuffer, soDirectory);
        } finally {
          if (is != null) {
            is.close();
          }
        }
      }
    }
  }

  @Override
  protected String computeFileHash(File file) {
    try (InputStream inputStream = new FileInputStream(file)) {
      Checksum checksum = new CRC32();
      byte[] buffer = new byte[1024];
      int length;
      while ((length = inputStream.read(buffer)) != -1) {
        checksum.update(buffer, 0, length);
      }
      return String.valueOf(checksum.getValue());
    } catch (IOException e) {
      LogUtil.w(TAG, "Failed to compute file hash ", e);
      return "";
    }
  }

  protected static final class ZipDso extends Dso implements Comparable<ZipDso> {

    final ZipEntry backingEntry;
    final int abiScore;

    ZipDso(String name, ZipEntry backingEntry, int abiScore) {
      super(name, String.valueOf(backingEntry.getCrc()));
      this.backingEntry = backingEntry;
      this.abiScore = abiScore;
    }

    @Override
    public int compareTo(ZipDso other) {
      return name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (other == null || getClass() != other.getClass()) {
        return false;
      }

      ZipDso that = (ZipDso) other;

      return backingEntry.equals(that.backingEntry) && abiScore == that.abiScore;
    }

    @Override
    public int hashCode() {
      int result = abiScore;
      return 31 * result + backingEntry.hashCode();
    }
  }

  @Override
  public String toString() {
    try {
      return mZipFileName.getCanonicalPath();
    } catch (IOException e) {
      return mZipFileName.getName();
    }
  }
}
