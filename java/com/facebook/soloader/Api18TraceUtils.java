/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

import android.annotation.TargetApi;
import android.os.Trace;
import javax.annotation.Nullable;

/**
 * Encapsulate Trace calls introduced in API18 into an independent class so that, we don't fail
 * preverification down level on versions below API 18.
 */
@DoNotOptimize
@TargetApi(18)
class Api18TraceUtils {
  private static final int MAX_SECTION_NAME_LENGTH = 127;

  public static void beginTraceSection(String prefix, @Nullable String middle, String suffix) {
    String sectionName = prefix + middle + suffix;
    if (sectionName.length() > MAX_SECTION_NAME_LENGTH && middle != null) {
      int remaining = MAX_SECTION_NAME_LENGTH - prefix.length() - suffix.length();
      sectionName = prefix + middle.substring(0, remaining) + suffix;
    }
    Trace.beginSection(sectionName);
  }

  public static void endSection() {
    Trace.endSection();
  }
}
