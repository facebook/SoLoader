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

import android.content.res.AssetFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Represents an APK split that contains native libraries. There are two kinds:
 *
 * <ul>
 *   <li>{@link Installed} — an installed split whose name is known but whose path must be resolved
 *       from application info at runtime.
 *   <li>{@link StaticPathArchive} — an archive with a known, hardcoded path.
 * </ul>
 */
public abstract class Split {

  /** An InputStream that closes an associated ZipFile when the stream is closed. */
  private static class ZipEntryInputStream extends InputStream {
    private final InputStream mDelegate;
    private final ZipFile mZipFile;

    ZipEntryInputStream(InputStream delegate, ZipFile zipFile) {
      mDelegate = delegate;
      mZipFile = zipFile;
    }

    @Override
    public int read() throws IOException {
      return mDelegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      return mDelegate.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
      return mDelegate.available();
    }

    @Override
    public void close() throws IOException {
      try {
        mDelegate.close();
      } finally {
        mZipFile.close();
      }
    }
  }

  /** Returns the file system path to the split APK. */
  public abstract String getPath();

  /** Returns the path to an entry inside this split: {@code <path>!/<entryPath>}. */
  public String getEntryPath(String entryPath) {
    return getPath() + "!/" + entryPath;
  }

  /** Returns the full path to a library inside this split: {@code <path>!/lib/<abi>/<soName>}. */
  public String getLibraryPath(String abi, String soName) {
    return getEntryPath("lib/" + abi + "/" + soName);
  }

  /** Returns the library directory path inside this split: {@code <path>!/lib/<abi>/}. */
  public String getLibraryDirectory(String abi) {
    return getEntryPath("lib/" + abi + "/");
  }

  /**
   * Opens a library from this split and returns an {@link InputStream} to read its contents. The
   * library is looked up under {@code lib/<abi>/<soName>} inside the archive. Uses the primary ABI.
   */
  public InputStream openLib(String soName) throws IOException {
    return openLib(SoLoader.getPrimaryAbi(), soName);
  }

  /**
   * Opens a library from this split and returns an {@link InputStream} to read its contents. The
   * library is looked up under {@code lib/<abi>/<soName>} inside the archive.
   */
  public InputStream openLib(String abi, String soName) throws IOException {
    ZipFile zipFile = new ZipFile(getPath());
    String entryName = "lib/" + abi + "/" + soName;
    ZipEntry entry = zipFile.getEntry(entryName);
    if (entry == null) {
      zipFile.close();
      throw new FileNotFoundException("Entry not found: " + entryName + " in " + getPath());
    }
    InputStream is = zipFile.getInputStream(entry);
    if (is == null) {
      zipFile.close();
      throw new IOException("Failed to open entry: " + entryName + " in " + getPath());
    }
    return new ZipEntryInputStream(is, zipFile);
  }

  /** Finds the installed ABI split for the given feature. */
  public static Installed findAbiSplit(String feature) {
    return new Installed(Splits.findNameOfAbiSplit(feature));
  }

  /** Returns the installed base split for the given feature. */
  public static Installed findMasterSplit(String feature) {
    return new Installed(feature);
  }

  /** Creates a Split from a file path. Uses Installed if the file is an application split. */
  public static Split fromPath(File path) throws IOException {
    if (Splits.isApplicationSplit(path)) {
      String splitName = Splits.getSplitName(path);
      if (splitName == null) {
        throw new NullPointerException("getSplitName returned null for application split: " + path);
      }
      return new Installed(splitName);
    }
    return new StaticPathArchive(path);
  }

  /** An installed split whose path is resolved from application info at runtime. */
  public static class Installed extends Split {
    private final String mSplitName;

    @GuardedBy("this")
    @Nullable
    private String mCachedPath;

    public Installed(String splitName) {
      mSplitName = splitName;
    }

    @Override
    public synchronized String getPath() {
      if (mCachedPath == null || !new File(mCachedPath).exists()) {
        String newPath = Splits.findSplitPath(mSplitName);
        if (newPath == null) {
          throw new IllegalStateException("Could not find " + mSplitName + " split");
        }
        mCachedPath = newPath;
      }
      return mCachedPath;
    }

    private boolean isBaseFeature() {
      return "base".equals(mSplitName) || mSplitName.startsWith("config.");
    }

    @Override
    public InputStream openLib(String abi, String soName) throws IOException {
      if (isBaseFeature()) {
        AssetFileDescriptor afd =
            SoLoader.getApplicationContext()
                .getAssets()
                .openNonAssetFd("lib/" + abi + "/" + soName);
        return afd.createInputStream();
      }
      return super.openLib(abi, soName);
    }

    @Override
    public String toString() {
      return "installed split:" + mSplitName;
    }
  }

  /** An archive with a known, hardcoded path. */
  public static class StaticPathArchive extends Split {
    private final File mPath;

    public StaticPathArchive(File path) {
      mPath = path;
    }

    @Override
    public String getPath() {
      return mPath.getPath();
    }

    @Override
    public String toString() {
      return "static path archive:" + mPath;
    }
  }
}
