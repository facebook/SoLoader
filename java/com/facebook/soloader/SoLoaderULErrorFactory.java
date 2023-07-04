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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SoLoaderULErrorFactory {
  public static SoLoaderULError create(String soName, UnsatisfiedLinkError e) {
    SoLoaderULError err;
    if (e.getMessage() != null && e.getMessage().contains("ELF")) {
      LogUtil.d(SoLoader.TAG, "Corrupted lib file detected");
      err = new SoLoaderCorruptedLibFileError(soName, e.toString());
    } else if (corruptedLibName(soName)) {
      LogUtil.d(SoLoader.TAG, "Corrupted lib name detected");
      err = new SoLoaderCorruptedLibNameError(soName, "corrupted lib name: " + e.toString());
    } else {
      // General ULE exception
      err = new SoLoaderULError(soName);
    }
    err.initCause(e);
    return err;
  }

  private static boolean corruptedLibName(String soName) {
    Pattern pattern = Pattern.compile("\\P{ASCII}+");
    Matcher matcher = pattern.matcher(soName);

    if (matcher.find()) {
      String libName = matcher.group();
      LogUtil.w(
          SoLoader.TAG, "Library name is corrupted, contains non-ASCII characters " + libName);
      return true;
    }

    return false;
  }
}
