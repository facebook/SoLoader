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

import android.annotation.SuppressLint;
import java.util.Set;
import javax.annotation.Nullable;

public class DirectSplitSoSourceLoadByPath extends DirectSplitSoSource {
  public DirectSplitSoSourceLoadByPath(
      String splitName, @Nullable Manifest manifest, @Nullable Set<String> libs) {
    super(splitName, manifest, libs);
  }

  @Override
  @SuppressLint("MissingSoLoaderLibrary")
  protected int loadLibraryImpl(String soName, int loadFlags) {
    System.load(getLibraryPath(soName));
    return LOAD_RESULT_LOADED;
  }

  @Override
  public String getName() {
    return "DirectSplitSoSourceLoadByPath";
  }
}
