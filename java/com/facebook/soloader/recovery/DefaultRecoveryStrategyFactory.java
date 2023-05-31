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

public class DefaultRecoveryStrategyFactory implements RecoveryStrategyFactory {
  private final ContextHolder mContextHolder;
  private final BaseApkPathHistory mBaseApkPathHistory;

  public DefaultRecoveryStrategyFactory(ContextHolder contextHolder) {
    mContextHolder = contextHolder;
    mBaseApkPathHistory = new BaseApkPathHistory(5);
  }

  @Override
  public RecoveryStrategy get() {
    return new CompositeRecoveryStrategy(
        // The very first recovery strategy should be to just retry. It is possible that one of the
        // SoSources was recovered in a recursive call to load library dependencies and as a result
        // no recovery steps will succeed now, but another attempt to load the current library would
        // succeed. WaitForUnpackingSoSources is a strategy that always succeeds, so we don't need
        // an explicit SimpleRetry.
        new WaitForUnpackingSoSources(),
        new RefreshContext(mContextHolder, mBaseApkPathHistory),
        new CheckBaseApkExists(mContextHolder, mBaseApkPathHistory));
  }
}
