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

import android.content.Context;
import com.facebook.soloader.BackupSoSource;
import com.facebook.soloader.DirectorySoSource;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoLoaderULError;
import com.facebook.soloader.SoSource;
import com.facebook.soloader.UnpackingSoSource.Dso;
import java.io.File;
import java.util.ArrayList;

public class CheckOnDiskStateDataApp implements RecoveryStrategy {

  private final Context mContext;

  public CheckOnDiskStateDataApp(Context context) {
    mContext = context;
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    if (!(error instanceof SoLoaderULError)) {
      // Only recover from SoLoaderULE errors
      return false;
    }

    LogUtil.e(SoLoader.TAG, "Checking /data/app missing libraries.");

    File nativeLibStandardDir = new File(mContext.getApplicationInfo().nativeLibraryDir);
    if (!nativeLibStandardDir.exists()) {
      for (SoSource soSource : soSources) {
        if (!(soSource instanceof BackupSoSource)) {
          continue;
        }
        BackupSoSource uss = (BackupSoSource) soSource;
        try {

          LogUtil.e(
              SoLoader.TAG,
              "Native library directory "
                  + nativeLibStandardDir
                  + " does not exist, will unpack everything under /data/data.");
          uss.prepare(0);
          break;
        } catch (Exception e) {
          LogUtil.e(
              SoLoader.TAG, "Encountered an exception while recovering from /data/app failure ", e);
          return false;
        }
      }
    } else {
      ArrayList<String> missingLibs = new ArrayList<>();
      for (SoSource soSource : soSources) {
        if (!(soSource instanceof BackupSoSource)) {
          continue;
        }
        BackupSoSource uss = (BackupSoSource) soSource;
        try {
          Dso[] dsosFromArchive = uss.getDsosBaseApk();
          for (Dso dso : dsosFromArchive) {
            File soFile = new File(nativeLibStandardDir, dso.name);
            if (soFile.exists()) {
              continue;
            }
            missingLibs.add(dso.name);
          }

          if (missingLibs.isEmpty()) {
            LogUtil.e(SoLoader.TAG, "No libraries missing from " + nativeLibStandardDir);
            return false;
          }

          LogUtil.e(
              SoLoader.TAG,
              "Missing libraries from "
                  + nativeLibStandardDir
                  + ": "
                  + missingLibs.toString()
                  + ", will run prepare on tbe backup so source");
          uss.prepare(0);
          break;
        } catch (Exception e) {
          LogUtil.e(
              SoLoader.TAG, "Encountered an exception while recovering from /data/app failure ", e);
          return false;
        }
      }
    }

    for (SoSource soSource : soSources) {
      if (!(soSource instanceof DirectorySoSource)) {
        continue;
      }
      if (soSource instanceof BackupSoSource) {
        continue;
      }
      DirectorySoSource directorySoSource = (DirectorySoSource) soSource;
      // We need to explicitly resolve dependencies, as dlopen() cannot do
      // so for dependencies at non-standard locations.
      directorySoSource.setExplicitDependencyResolution();
    }

    LogUtil.e(SoLoader.TAG, "Successfully recovered from /data/app disk failure.");
    return true;
  }
}
