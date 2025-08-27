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
import android.content.Context;
import android.content.pm.ApplicationInfo;
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
  private static final String BASE = "base";
  private static final String BASE_APK = "base.apk";

  protected final String mFeatureName;

  protected @Nullable Manifest mManifest = null;
  protected @Nullable Map<String, Manifest.Library> mLibs = null;
  protected @Nullable String mSplitFileName = null;

  public DirectSplitSoSource(String featureName) {
    mFeatureName = featureName;
  }

  Manifest getManifest() {
    if (mManifest == null) {
      throw new IllegalStateException("prepare not called");
    }
    return mManifest;
  }

  @Override
  protected void prepare(int flags) throws IOException {
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

    mManifest = manifest;
    mLibs = new HashMap<String, Manifest.Library>();
    for (Manifest.Library lib : mManifest.libs) {
      mLibs.put(lib.name, lib);
    }

    mSplitFileName = findSplitFileName(manifest);
  }

  protected String findSplitFileName(Manifest manifest) {
    Context context = SoLoader.sApplicationContext;
    if (context == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }

    return findSplitFileName(mFeatureName, context.getApplicationInfo());
  }

  protected static String findSplitFileName(String feature, ApplicationInfo aInfo) {
    @Nullable String[] splitSourceDirs = aInfo.splitSourceDirs;
    if (splitSourceDirs == null) {
      return BASE_APK;
    }

    final String featureSplit;
    final String configSplit;
    if (BASE.equals(feature)) {
      featureSplit = BASE_APK;
      configSplit = "split_config." + SoLoader.getPrimaryAbi().replace("-", "_") + ".apk";
    } else {
      featureSplit = "split_" + feature + ".apk";
      configSplit =
          "split_" + feature + ".config." + SoLoader.getPrimaryAbi().replace("-", "_") + ".apk";
    }

    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(configSplit)) {
        return configSplit;
      }
    }

    if (BASE.equals(feature)) {
      return BASE_APK;
    }

    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(featureSplit)) {
        return featureSplit;
      }
    }

    return BASE_APK;
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
    if (mSplitFileName == null) {
      throw new IllegalStateException("prepare not called");
    }
    return getSplitPath(mSplitFileName);
  }

  static String getSplitPath(String splitFileName) {
    if (SoLoader.sApplicationInfoProvider == null) {
      throw new IllegalStateException("SoLoader not initialized");
    }
    ApplicationInfo aInfo = SoLoader.sApplicationInfoProvider.get();

    if (BASE_APK.equals(splitFileName)) {
      return aInfo.sourceDir;
    }

    @Nullable String[] splitsSourceDirs = aInfo.splitSourceDirs;
    if (splitsSourceDirs == null) {
      throw new IllegalStateException("No splits avaiable");
    }

    for (String splitSourceDir : splitsSourceDirs) {
      if (splitSourceDir.endsWith(splitFileName)) {
        return splitSourceDir;
      }
    }

    throw new IllegalStateException("Could not find " + splitFileName + " split");
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
    if (mSplitFileName == null) {
      return "DirectSplitSoSource";
    } else {
      return "DirectSplitSoSource[" + mSplitFileName + "]";
    }
  }
}
