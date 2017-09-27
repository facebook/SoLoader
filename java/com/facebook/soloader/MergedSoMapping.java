// Copyright 2004-present Facebook. All Rights Reserved.

package com.facebook.soloader;

import javax.annotation.Nullable;

class MergedSoMapping {
  static @Nullable String mapLibName(String preMergedLibName) {
    return null;
  }

  static void invokeJniOnload(String preMergedLibName) {
    throw new IllegalArgumentException(
        "Unknown library: " + preMergedLibName);
  }
}
