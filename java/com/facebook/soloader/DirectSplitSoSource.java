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
import android.annotation.TargetApi;
import android.os.Build;
import android.os.StrictMode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class DirectSplitSoSource extends SoSource {

  protected final String mSplitName;

  protected final @Nullable String mFeatureName;
  protected @Nullable Map<String, Manifest.Library> mLibs = null;

  public DirectSplitSoSource(String featureName) {
    this(featureName, Splits.findAbiSplit(featureName));
  }

  public DirectSplitSoSource(String featureName, String splitName) {
    mFeatureName = featureName;
    mSplitName = splitName;
  }

  public DirectSplitSoSource(String splitName, Manifest manifest) {
    mFeatureName = null;
    mSplitName = splitName;
    installManifest(manifest);
  }

  @Override
  protected void prepare(int flags) throws IOException {
    if (mLibs != null) {
      // Constructed with manifest, library data is already initialized.
      return;
    }

    if (mFeatureName == null) {
      throw new NullPointerException();
    }
    try (InputStream is =
        SoLoader.sApplicationContext.getAssets().open(mFeatureName + ".soloader-manifest")) {
      installManifest(Manifest.read(is));
    }
  }

  protected void installManifest(Manifest manifest) {
    if (!manifest.abi.equals(SoLoader.getPrimaryAbi())) {
      throw new IllegalStateException(
          "DirectSplitSoSource only supports primary abi: "
              + SoLoader.getPrimaryAbi()
              + "; manifest abi: "
              + manifest.abi);
    }

    mLibs = new HashMap<String, Manifest.Library>();
    for (Manifest.Library lib : manifest.libs) {
      mLibs.put(lib.name, lib);
    }
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (mLibs == null) {
      throw new IllegalStateException("prepare not called");
    }

    Manifest.Library library = mLibs.get(soName);
    if (library != null) {
      return loadLibraryImpl(library, loadFlags, threadPolicy);
    }

    return LOAD_RESULT_NOT_FOUND;
  }

  @SuppressLint("MissingSoLoaderLibrary")
  protected int loadLibraryImpl(
      Manifest.Library lib, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (lib.hasUnknownDeps()) {
      loadDependencies(lib, loadFlags, threadPolicy);
    }

    SoLoader.sSoFileLoader.load(getLibraryPath(lib), loadFlags);
    return LOAD_RESULT_LOADED;
  }

  private void loadDependencies(
      Manifest.Library lib, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    try (ZipFile apk = new ZipFile(getSplitPath())) {
      try (ElfByteChannel bc = getElfByteChannel(apk, lib)) {
        NativeDeps.loadDependencies(lib.name, bc, loadFlags, threadPolicy);
      }
    }
  }

  @Override
  @Nullable
  public File unpackLibrary(String soName) {
    return getSoFileByName(soName);
  }

  @Override
  @Nullable
  protected File getSoFileByName(String soName) {
    String path = getLibraryPath(soName);
    return path == null ? null : new File(path);
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) {
    if (mLibs == null) {
      throw new IllegalStateException("prepare not called");
    }

    Manifest.Library library = mLibs.get(soName);
    if (library != null) {
      return getLibraryPath(library);
    }

    return null;
  }

  private String getLibraryPath(Manifest.Library lib) {
    return getSplitPath() + "!/lib/" + SoLoader.getPrimaryAbi() + "/" + lib.name;
  }

  protected String getSplitPath() {
    return Splits.getSplitPath(mSplitName);
  }

  @Override
  @Nullable
  public String[] getLibraryDependencies(String soName) throws IOException {
    if (mLibs == null) {
      throw new IllegalStateException("prepare not called");
    }

    Manifest.Library library = mLibs.get(soName);
    if (library != null) {
      return getLibraryDependencies(library);
    }

    return null;
  }

  protected String[] getLibraryDependencies(Manifest.Library lib) throws IOException {
    try (ZipFile apk = new ZipFile(getSplitPath())) {
      try (ElfByteChannel bc = getElfByteChannel(apk, lib)) {
        return NativeDeps.getDependencies(lib.name, bc);
      }
    }
  }

  private ElfByteChannel getElfByteChannel(ZipFile apk, Manifest.Library lib) throws IOException {
    final ZipEntry entry = apk.getEntry("lib/" + SoLoader.getPrimaryAbi() + "/" + lib.name);
    return new ElfZipFileChannel(apk, entry);
  }

  @Override
  public String[] getSoSourceAbis() {
    return new String[] {SoLoader.getPrimaryAbi()};
  }

  @Override
  public void addToLdLibraryPath(Collection<String> paths) {
    paths.add(getSplitPath() + "!/lib/" + SoLoader.getPrimaryAbi() + "/");
  }

  @Override
  public String getName() {
    return "DirectSplitSoSource[" + mSplitName + "]";
  }
}
