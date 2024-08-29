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

import com.facebook.soloader.BackupSoSource;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoLoaderULError;
import com.facebook.soloader.SoSource;
import com.facebook.soloader.UnpackingSoSource;
import com.facebook.soloader.UnpackingSoSource.Dso;

public class CheckOnDiskStateDataData implements RecoveryStrategy {
  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    if (!(error instanceof SoLoaderULError)) {
      // Only recover from SoLoaderULE errors
      return false;
    }

    LogUtil.e(SoLoader.TAG, "Checking /data/data missing libraries.");

    boolean recovered = false;
    for (SoSource soSource : soSources) {
      if (!(soSource instanceof UnpackingSoSource)) {
        continue;
      }
      if (soSource instanceof BackupSoSource) {
        continue;
      }
      UnpackingSoSource uss = (UnpackingSoSource) soSource;
      try {
        Dso[] dsosFromArchive = uss.getDsosBaseApk();
        for (Dso dso : dsosFromArchive) {
          if (uss.getSoFileByName(dso.name) == null) {
            LogUtil.e(
                SoLoader.TAG,
                "Missing " + dso.name + " from " + uss.getName() + ", will force prepare.");
            uss.prepare(SoSource.PREPARE_FLAG_FORCE_REFRESH);
            recovered = true;
            break;
          }
        }
      } catch (Exception e) {
        LogUtil.e(
            SoLoader.TAG, "Encountered an exception while recovering from /data/data failure ", e);
        return false;
      }
    }

    if (recovered) {
      LogUtil.e(SoLoader.TAG, "Successfully recovered from /data/data disk failure.");
      return true;
    }

    LogUtil.e(
        SoLoader.TAG,
        "No libraries missing from unpacking so paths while recovering /data/data failure");
    return false;
  }
}
