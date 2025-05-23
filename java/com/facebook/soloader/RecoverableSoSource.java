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

package com.facebook.soloader;

import android.content.pm.ApplicationInfo;

public interface RecoverableSoSource {

  // Called by SoLoader when an invariant such as /data/app directory changes. Allows
  // implementations of SoSources to recover by either fixing own state and returning
  // reference to this, or by returning new SoSource that should be used from now on instead.
  SoSource recover(ApplicationInfo aInfo);
}
