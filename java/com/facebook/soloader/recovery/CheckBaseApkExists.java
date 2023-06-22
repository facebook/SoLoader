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

import com.facebook.soloader.ContextHolder;
import com.facebook.soloader.NoBaseApkException;
import com.facebook.soloader.SoSource;
import java.io.File;

/**
 * RecoveryStrategy that detects cases when SoLoader is unable to reconstruct path to the base apk.
 * There is not much that can be done then apart from rethrowing a more specific exception for
 * tracking purposes.
 */
public class CheckBaseApkExists implements RecoveryStrategy {
  private final ContextHolder mContextHolder;
  private final BaseApkPathHistory mBaseApkPathHistory;

  public CheckBaseApkExists(ContextHolder contextHolder, BaseApkPathHistory pathHistory) {
    mContextHolder = contextHolder;
    mBaseApkPathHistory = pathHistory;
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    String baseApkPath = mContextHolder.get().getApplicationInfo().sourceDir;
    if (!new File(baseApkPath).exists()) {
      StringBuilder sb =
          new StringBuilder("Base apk does not exist: ").append(baseApkPath).append(". ");
      mBaseApkPathHistory.report(sb);
      throw new NoBaseApkException(sb.toString(), error);
    }

    return false;
  }
}
