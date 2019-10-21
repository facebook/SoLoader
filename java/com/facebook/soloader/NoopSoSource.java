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

import android.os.StrictMode;
import java.io.File;

/** {@link SoSource} that does nothing and pretends to successfully load all libraries. */
public class NoopSoSource extends SoSource {
  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy) {
    return LOAD_RESULT_LOADED;
  }

  @Override
  public File unpackLibrary(String soName) {
    throw new UnsupportedOperationException("unpacking not supported in test mode");
  }
}
