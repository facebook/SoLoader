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

package com.facebook.soloader.observer;

import com.facebook.soloader.SoFileLoader;
import com.facebook.soloader.SoSource;
import com.facebook.soloader.recovery.RecoveryStrategy;
import javax.annotation.Nullable;

public interface Observer {
  void onLoadLibraryStart(String library, int flags);

  void onLoadLibraryEnd(@Nullable Throwable t);

  void onLoadDependencyStart(String library, int flags);

  void onLoadDependencyEnd(@Nullable Throwable t);

  void onSoSourceLoadLibraryStart(SoSource source);

  void onSoSourceLoadLibraryEnd(@Nullable Throwable t);

  void onRecoveryStart(RecoveryStrategy recovery);

  void onRecoveryEnd(@Nullable Throwable t);

  void onGetDependenciesStart();

  void onGetDependenciesEnd(@Nullable Throwable t);

  void onSoFileLoaderLoadStart(SoFileLoader soFileLoader, String method, int flags);

  void onSoFileLoaderLoadEnd(@Nullable Throwable t);
}
