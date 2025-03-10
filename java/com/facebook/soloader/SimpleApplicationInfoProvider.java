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

import android.content.Context;
import android.content.pm.ApplicationInfo;

public class SimpleApplicationInfoProvider implements Provider<ApplicationInfo> {
  private final Context mApplicationContext;

  public SimpleApplicationInfoProvider(Context applicationContext) {
    mApplicationContext = applicationContext;
  }

  @Override
  public ApplicationInfo get() {
    return mApplicationContext.getApplicationInfo();
  }
}
