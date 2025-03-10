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

import android.content.pm.ApplicationInfo;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.Provider;
import com.facebook.soloader.RecoverableSoSource;
import com.facebook.soloader.SoSource;
import java.io.File;

public class DetectDataAppMove implements RecoveryStrategy {
  private static final String TAG = "soloader.recovery.DetectDataAppMove";

  private final BaseApkPathHistory mBaseApkPathHistory;
  private final int mInitialHistorySize;
  private final Provider<ApplicationInfo> mApplicationInfoProvider;

  public DetectDataAppMove(
      BaseApkPathHistory baseApkPathHistory, Provider<ApplicationInfo> applicationInfoProvider) {
    mBaseApkPathHistory = baseApkPathHistory;
    mInitialHistorySize = baseApkPathHistory.size();
    mApplicationInfoProvider = applicationInfoProvider;
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    ApplicationInfo aInfo = mApplicationInfoProvider.get();
    if (detectMove(aInfo)) {
      recoverSoSources(soSources, aInfo);
      return true;
    }

    if (mInitialHistorySize != mBaseApkPathHistory.size()) {
      LogUtil.w(TAG, "Context was updated (perhaps by another thread)");
      return true;
    }

    return false;
  }

  private boolean detectMove(ApplicationInfo aInfo) {
    String baseApkPath = aInfo.sourceDir;
    return new File(baseApkPath).exists() && mBaseApkPathHistory.recordPathIfNew(baseApkPath);
  }

  private void recoverSoSources(SoSource[] soSources, ApplicationInfo aInfo) {
    for (int i = 0; i < soSources.length; ++i) {
      if (soSources[i] instanceof RecoverableSoSource) {
        RecoverableSoSource soSource = (RecoverableSoSource) soSources[i];
        soSources[i] = soSource.recover(aInfo);
      }
    }
  }
}
