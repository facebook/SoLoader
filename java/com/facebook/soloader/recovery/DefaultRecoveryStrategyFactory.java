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

public class DefaultRecoveryStrategyFactory implements RecoveryStrategyFactory {
  private final Context mContext;
  private final BaseApkPathHistory mBaseApkPathHistory;
  private final int mRecoveryFlags;

  public DefaultRecoveryStrategyFactory(Context context, int recoveryFlags) {
    mContext = context;
    mRecoveryFlags = recoveryFlags;
    mBaseApkPathHistory = new BaseApkPathHistory(5);
    mBaseApkPathHistory.recordPathIfNew(context.getApplicationInfo().sourceDir);
  }

  @Override
  public RecoveryStrategy get() {
    return new CompositeRecoveryStrategy(
        new DetectDataAppMove(mContext, mBaseApkPathHistory),
        new CheckBaseApkExists(mContext, mBaseApkPathHistory),
        new WaitForAsyncInit(),
        new CheckOnDiskStateDataApp(mContext),
        new ReunpackBackupSoSources(mRecoveryFlags),
        new CheckOnDiskStateDataData(),
        new ReunpackNonBackupSoSources(),
        new WaitForAsyncInit());
  }
}
