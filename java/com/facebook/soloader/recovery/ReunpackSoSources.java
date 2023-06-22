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

import com.facebook.soloader.LogUtil;
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoLoaderULError;
import com.facebook.soloader.SoSource;
import com.facebook.soloader.UnpackingSoSource;
import java.io.IOException;

/**
 * RecoveryStrategy that detects cases when SoLoader failed to load a corrupted library, case in
 * which we try to re-unpack the libraries.
 */
public class ReunpackSoSources implements RecoveryStrategy {

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    if (!(error instanceof SoLoaderULError)) {
      return false;
    }
    SoLoaderULError err = (SoLoaderULError) error;

    LogUtil.d(
        SoLoader.TAG,
        "Reunpacking UnpackingSoSources for " + err.getSoName() + " due to " + err.getMessage());

    try {
      for (SoSource soSource : soSources) {
        if (soSource instanceof UnpackingSoSource) {
          UnpackingSoSource uss = (UnpackingSoSource) soSource;
          uss.prepare(err.getSoName());
        }
      }
    } catch (IOException e) {
      LogUtil.e(SoLoader.TAG, "Encountered an IOException during unpacking " + e.getMessage());
      return false;
    }

    return true;
  }
}
