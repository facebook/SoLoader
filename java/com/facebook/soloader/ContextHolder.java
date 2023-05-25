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
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A mutable reference to a Context with caching for some properties that might change dynamically.
 */
@ThreadSafe
public class ContextHolder {
  @GuardedBy("this")
  @Nullable
  private Context mContext = null;

  @GuardedBy("this")
  @Nullable
  private String mBaseApkPath = null;

  public ContextHolder() {}

  public ContextHolder(Context context) {
    set(context);
  }

  public synchronized void set(@Nullable Context context) {
    mContext = context;
    mBaseApkPath = context == null ? null : context.getApplicationInfo().sourceDir;
  }

  public synchronized Context get() {
    if (mContext == null) {
      throw new IllegalStateException("ContextHolder not initialized, cannot get context");
    }
    return mContext;
  }

  public synchronized String getCachedBaseApkPath() {
    if (mBaseApkPath == null) {
      throw new IllegalStateException("ContextHolder not initialized, cannot get base apk path");
    }
    return mBaseApkPath;
  }
}
