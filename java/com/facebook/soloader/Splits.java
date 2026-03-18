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

import android.annotation.TargetApi;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nullable;

public class Splits {
  private static final String BASE = "base";
  private static final String BASE_APK = "base.apk";

  static String findNameOfAbiSplit(String feature) {
    return findNameOfAbiSplit(feature, SoLoader.getApplicationInfo());
  }

  static String findNameOfAbiSplit(String feature, ApplicationInfo aInfo) {
    @Nullable String[] splitSourceDirs = aInfo.splitSourceDirs;
    if (splitSourceDirs == null) {
      return BASE;
    }

    final String abi = SoLoader.getPrimaryAbi().replace("-", "_");
    final String configName;
    if (BASE.equals(feature)) {
      configName = "config." + abi;
    } else {
      configName = feature + ".config." + abi;
    }

    String configFileName = "split_" + configName + ".apk";
    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(configFileName)) {
        return configName;
      }
    }

    if (BASE.equals(feature)) {
      return BASE;
    }

    String featureFileName = "split_" + feature + ".apk";
    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(featureFileName)) {
        return feature;
      }
    }

    return BASE;
  }

  static @Nullable String findSplitPath(String splitName) {
    return findSplitPath(splitName, SoLoader.getApplicationInfo());
  }

  static @Nullable String findSplitPath(String splitName, ApplicationInfo aInfo) {
    if (BASE.equals(splitName)) {
      return aInfo.sourceDir;
    }

    @Nullable String[] splitsSourceDirs = aInfo.splitSourceDirs;
    if (splitsSourceDirs == null) {
      return null;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      int index = indexOfSplitO(splitName, aInfo);
      if (index < 0) {
        return null;
      }
      return splitsSourceDirs[index];
    }

    String splitFileName = "split_" + splitName + ".apk";
    for (String splitSourceDir : splitsSourceDirs) {
      if (splitSourceDir.endsWith(splitFileName)) {
        return splitSourceDir;
      }
    }

    return null;
  }

  static String getSplitPath(String splitName) {
    return getSplitPath(splitName, SoLoader.getApplicationInfo());
  }

  static String getSplitPath(String splitName, ApplicationInfo aInfo) {
    String path = findSplitPath(splitName, aInfo);
    if (path == null) {
      throw new IllegalStateException("Could not find " + splitName + " split");
    }
    return path;
  }

  public static boolean isApplicationSplit(File path) throws IOException {
    return isApplicationSplit(path, SoLoader.getApplicationInfo());
  }

  public static boolean isApplicationSplit(File path, ApplicationInfo aInfo) throws IOException {
    String splitName = getSplitName(path);
    if (splitName == null) {
      return false;
    }

    path = path.getCanonicalFile();
    String parent = path.getParent();

    if (parent == null) {
      return false;
    }

    if (!parent.equals(new File(aInfo.sourceDir).getParent())) {
      return false;
    }

    return isApplicationSplitName(splitName, aInfo);
  }

  public static boolean isBaseApk(File path) {
    return path.getName().equals(BASE_APK);
  }

  static @Nullable String getSplitName(File path) {
    String name = path.getName();
    if (name.equals(BASE_APK)) {
      return BASE;
    }
    if (name.startsWith("split_") && name.endsWith(".apk")) {
      return name.substring(6, name.length() - 4);
    }
    return null;
  }

  static boolean isApplicationSplitName(String name, ApplicationInfo aInfo) {
    if (name.equals(BASE)) {
      return true;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      return indexOfSplitO(name, aInfo) >= 0;
    }

    @Nullable String[] splitsSourceDirs = aInfo.splitSourceDirs;
    if (splitsSourceDirs == null) {
      return false;
    }

    for (String splitSourceDir : splitsSourceDirs) {
      String dirSplitName = getSplitName(new File(splitSourceDir));
      if (name.equals(dirSplitName)) {
        return true;
      }
    }

    return false;
  }

  @TargetApi(Build.VERSION_CODES.O)
  private static int indexOfSplitO(String name, ApplicationInfo aInfo) {
    String[] splitNames = aInfo.splitNames;
    if (splitNames == null) {
      return -1;
    }

    return Arrays.binarySearch(splitNames, name);
  }
}
