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
import android.content.pm.PackageManager;
import com.facebook.soloader.ContextHolder;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.RecoverableSoSource;
import com.facebook.soloader.SoSource;
import java.io.File;

public class RefreshContext implements RecoveryStrategy {
  private static final String TAG = "soloader.recovery.RefereshContext";

  private final ContextHolder mContextHolder;
  private final BaseApkPathHistory mBaseApkPathHistory;
  private final int mInitialHistorySize;

  public RefreshContext(ContextHolder contextHolder, BaseApkPathHistory baseApkPathHistory) {
    mContextHolder = contextHolder;
    mBaseApkPathHistory = baseApkPathHistory;
    mInitialHistorySize = baseApkPathHistory.size();
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    Context oldContext = mContextHolder.get();
    if (tryRecover(oldContext, soSources, "dynamic-update")) {
      return true;
    }

    try {
      Context newContext = getUpdatedContext();
      if (tryRecover(newContext, soSources, "pm-context")) {
        return true;
      }
    } catch (PackageManager.NameNotFoundException e) {
      LogUtil.w(TAG, "Can not find the package ", e);
    }

    if (mInitialHistorySize != mBaseApkPathHistory.size()) {
      LogUtil.w(TAG, "Context was updated (perhaps by another thread)");
      return true;
    }

    return false;
  }

  private boolean tryRecover(Context candidate, SoSource[] soSources, String tag) {
    String baseApkPath = getBaseApkPath(candidate);
    if (new File(baseApkPath).exists() && mBaseApkPathHistory.recordPathIfNew(baseApkPath, tag)) {
      LogUtil.w(TAG, "Updating context using " + tag + " strategy");
      updateContext(candidate, soSources);
      return true;
    }
    return false;
  }

  private void updateContext(Context newContext, SoSource[] soSources) {
    mContextHolder.set(newContext);
    for (int i = 0; i < soSources.length; ++i) {
      if (soSources[i] instanceof RecoverableSoSource) {
        RecoverableSoSource soSource = (RecoverableSoSource) soSources[i];
        soSources[i] = soSource.recover(newContext);
      }
    }
  }

  private Context getUpdatedContext() throws PackageManager.NameNotFoundException {
    Context oldContext = mContextHolder.get();
    return oldContext.createPackageContext(oldContext.getPackageName(), 0);
  }

  private static String getBaseApkPath(Context context) {
    return context.getApplicationInfo().sourceDir;
  }
}
