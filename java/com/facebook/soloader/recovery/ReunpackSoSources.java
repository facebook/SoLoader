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

package com.facebook.soloader.recovery;

import com.facebook.soloader.ApkSoSource;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoLoaderULError;
import com.facebook.soloader.SoSource;
import com.facebook.soloader.UnpackingSoSource;
import java.io.IOException;
import java.util.ArrayList;
import javax.annotation.Nullable;

/**
 * RecoveryStrategy that detects cases when SoLoader failed to load a corrupted library, case in
 * which we try to re-unpack the libraries.
 */
public class ReunpackSoSources implements RecoveryStrategy {

  private void tryReunpacking(@Nullable String soName, UnpackingSoSource uss) throws IOException {
    if (soName != null) {
      uss.prepare(soName);
    } else {
      uss.prepareForceRefresh();
    }
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    String soName = null;
    if (error instanceof SoLoaderULError) {
      SoLoaderULError err = (SoLoaderULError) error;

      soName = err.getSoName();
    }

    LogUtil.d(
        SoLoader.TAG,
        "Reunpacking UnpackingSoSources due to "
            + error.getMessage()
            + ((soName == null) ? "" : (", retrying for specific library " + soName)));

    // Since app-critical code can be found inside ApkSoSources, let's make sure that code gets
    // re-unpacked first.
    ArrayList<UnpackingSoSource> nonApkUnpackingSoSources = new ArrayList<>(soSources.length);
    try {
      for (SoSource soSource : soSources) {
        if (!(soSource instanceof UnpackingSoSource)) {
          continue;
        }
        UnpackingSoSource uss = (UnpackingSoSource) soSource;
        if (!(uss instanceof ApkSoSource)) {
          nonApkUnpackingSoSources.add(uss);
          continue;
        }
        // Re-unpack the ApkSoSource libraries first
        tryReunpacking(soName, uss);
      }
      for (UnpackingSoSource uss : nonApkUnpackingSoSources) {
        // Re-unpack from other UnpackingSoSources as well
        tryReunpacking(soName, uss);
      }
    } catch (Exception e) {
      // Catch a general error and log it, rather than failing during recovery and crashing the app
      // in a different way.
      LogUtil.e(SoLoader.TAG, "Encountered an Exception during unpacking ", e);
      return false;
    }

    return true;
  }
}
