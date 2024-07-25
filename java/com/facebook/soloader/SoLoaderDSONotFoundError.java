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

@DoNotStripAny
public class SoLoaderDSONotFoundError extends SoLoaderULError {

  public SoLoaderDSONotFoundError(String soName) {
    super(soName);
  }

  public SoLoaderDSONotFoundError(String soName, String error) {
    super(soName, error);
  }

  public static SoLoaderDSONotFoundError create(
      String soName, @Nullable Context ctx, SoSource[] soSources) {
    StringBuilder sb = new StringBuilder("couldn't find DSO to load: ").append(soName);
    sb.append("\n\texisting SO sources: ");
    for (int i = 0; i < soSources.length; ++i) {
      sb.append("\n\t\tSoSource ").append(i).append(": ").append(soSources[i].toString());
    }
    if (ctx != null) {
      sb.append("\n\tNative lib dir: ")
          .append(ctx.getApplicationInfo().nativeLibraryDir)
          .append("\n");
    }
    return new SoLoaderDSONotFoundError(soName, sb.toString());
  }
}
