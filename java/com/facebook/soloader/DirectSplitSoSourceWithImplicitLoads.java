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
import android.content.Context;

public class DirectSplitSoSourceWithImplicitLoads extends DirectSplitSoSource
    implements RecoverableSoSource {
  public DirectSplitSoSourceWithImplicitLoads(String splitName) {
    super(splitName);
  }

  @Override
  @SuppressLint("MissingSoLoaderLibrary")
  protected int loadLibraryImpl(String soName, int loadFlags) {
    if ((loadFlags & LOAD_FLAG_ALLOW_IMPLICIT_PROVISION) != 0) {
      return LOAD_RESULT_IMPLICITLY_PROVIDED;
    }

    System.loadLibrary(soName.substring(3, soName.length() - 3));
    return LOAD_RESULT_LOADED;
  }

  @Override
  public String getName() {
    return "DirectSplitSoSourceWithImplicitLoads";
  }

  @Override
  public SoSource recover(Context context) {
    return new DirectSplitSoSourceWithStrictPathControl(mSplitName, mManifest, mLibs);
  }
}
