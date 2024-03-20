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

import android.os.Build;
import android.os.StrictMode;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

public abstract class DirectSplitSoSource extends SoSource {
  protected final String mSplitName;

  protected @Nullable Manifest mManifest = null;
  protected @Nullable Set<String> mLibs = null;

  public DirectSplitSoSource(String splitName) {
    mSplitName = splitName;
  }

  public DirectSplitSoSource(String splitName, Manifest manifest) {
    mSplitName = splitName;
    mManifest = manifest;
    mLibs = new HashSet<String>(manifest.libs);
  }

  public DirectSplitSoSource(
      String splitName, @Nullable Manifest manifest, @Nullable Set<String> libs) {
    mSplitName = splitName;
    mManifest = manifest;
    mLibs = libs;
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
        SoLoader.sApplicationContext.getAssets().open(mSplitName + ".soloader-manifest")) {
      mManifest = Manifest.read(is);
    }

    mLibs = new HashSet<String>(mManifest.libs);
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy) {
    if (mLibs == null) {
      throw new IllegalStateException("prepare not called");
    }

    if (mLibs.contains(soName)) {
      return loadLibraryImpl(soName, loadFlags);
    }

    return LOAD_RESULT_NOT_FOUND;
  }

  protected abstract int loadLibraryImpl(String soName, int loadFlags);

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
    if (mLibs == null || mManifest == null) {
      throw new IllegalStateException("prepare not called");
    }

    if (mLibs.contains(soName)) {
      return getSplitPath(mSplitName) + "!/lib/" + mManifest.arch + "/" + soName;
    }

    return null;
  }

  static String getSplitPath(String splitName) {
    if ("base".equals(splitName)) {
      return SoLoader.sApplicationContext.getApplicationInfo().sourceDir;
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      throw new IllegalStateException("Splits are not supported before Android L");
    }

    String[] splits = SoLoader.sApplicationContext.getApplicationInfo().splitSourceDirs;
    if (splits == null) {
      throw new IllegalStateException("No splits avaiable");
    }

    String fileName = "split_" + splitName + ".apk";
    for (String split : splits) {
      if (split.endsWith(fileName)) {
        return split;
      }
    }

    throw new IllegalStateException("Could not find " + splitName + " split");
  }

  @Override
  @Nullable
  public String[] getLibraryDependencies(String soName) {
    if (mLibs == null) {
      throw new IllegalStateException("prepare not called");
    }

    if (mLibs.contains(soName)) {
      // No dependencies to report, the split is assumed to be registered with
      // the linker namespace and no explicit dependency loading should happen.
      return new String[0];
    }

    return null;
  }

  @Override
  public String[] getSoSourceAbis() {
    if (mManifest == null) {
      throw new IllegalStateException("prepare not called");
    }
    return new String[] {mManifest.arch};
  }
}
