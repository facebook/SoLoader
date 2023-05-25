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
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoSource;

public class RefreshContext implements RecoveryStrategy {
  private final ContextHolder mContextHolder;

  public RefreshContext(ContextHolder contextHolder) {
    mContextHolder = contextHolder;
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    Context oldContext = mContextHolder.get();

    if (isBaseApkPathStale(oldContext)) {
      LogUtil.w(SoLoader.TAG, "Application info was updated dynamically");
      updateContext(oldContext, soSources);
      return true;
    }

    try {
      Context newContext = getUpdatedContext();
      if (isBaseApkPathStale(newContext)) {
        updateContext(newContext, soSources);
        return true;
      }
    } catch (PackageManager.NameNotFoundException e) {
      LogUtil.w(SoLoader.TAG, "Can not find the package ", e);
    }

    return false;
  }

  private boolean isBaseApkPathStale(Context newContext) {
    return !mContextHolder.getCachedBaseApkPath().equals(newContext.getApplicationInfo().sourceDir);
  }

  private void updateContext(Context newContext, SoSource[] soSources) {
    LogUtil.w(
        SoLoader.TAG,
        "Updating context, base apk path changes from "
            + mContextHolder.getCachedBaseApkPath()
            + " to "
            + newContext.getApplicationInfo().sourceDir);
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
}
