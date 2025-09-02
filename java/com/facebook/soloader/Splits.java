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

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Splits {
  private static final String BASE = "base";
  private static final String BASE_APK = "base.apk";

  public static String findAbiSplit(String feature) {
    return findAbiSplit(feature, SoLoader.getApplicationInfo());
  }

  public static String findAbiSplit(String feature, ApplicationInfo aInfo) {
    @Nullable String[] splitSourceDirs = aInfo.splitSourceDirs;
    if (splitSourceDirs == null) {
      return BASE_APK;
    }

    final String featureSplit;
    final String configSplit;
    if (BASE.equals(feature)) {
      featureSplit = BASE_APK;
      configSplit = "split_config." + SoLoader.getPrimaryAbi().replace("-", "_") + ".apk";
    } else {
      featureSplit = "split_" + feature + ".apk";
      configSplit =
          "split_" + feature + ".config." + SoLoader.getPrimaryAbi().replace("-", "_") + ".apk";
    }

    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(configSplit)) {
        return configSplit;
      }
    }

    if (BASE.equals(feature)) {
      return BASE_APK;
    }

    for (String splitSourceDir : splitSourceDirs) {
      if (splitSourceDir.endsWith(featureSplit)) {
        return featureSplit;
      }
    }

    return BASE_APK;
  }

  public static String getSplitPath(String splitFileName) {
    return getSplitPath(splitFileName, SoLoader.getApplicationInfo());
  }

  public static String getSplitPath(String splitFileName, ApplicationInfo aInfo) {
    if (BASE_APK.equals(splitFileName)) {
      return aInfo.sourceDir;
    }

    @Nullable String[] splitsSourceDirs = aInfo.splitSourceDirs;
    if (splitsSourceDirs == null) {
      throw new IllegalStateException("No splits avaiable");
    }

    for (String splitSourceDir : splitsSourceDirs) {
      if (splitSourceDir.endsWith(splitFileName)) {
        return splitSourceDir;
      }
    }

    throw new IllegalStateException("Could not find " + splitFileName + " split");
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      return isApplicationSplitName(splitName, aInfo);
    }

    @Nullable String[] splitsSourceDirs = aInfo.splitSourceDirs;
    if (splitsSourceDirs == null) {
      return false;
    }

    final String needle = path.getPath();
    for (String splitSourceDir : splitsSourceDirs) {
      if (splitSourceDir.equals(needle)) {
        return true;
      }
    }

    return false;
  }

  public static @Nullable String getSplitName(File path) {
    String name = path.getName();
    if (name.startsWith("split_") && name.endsWith(".apk")) {
      return name.substring(6, name.length() - 4);
    }
    return null;
  }

  @TargetApi(Build.VERSION_CODES.O)
  public static boolean isApplicationSplitName(String name, ApplicationInfo aInfo) {
    String[] splitNames = aInfo.splitNames;
    if (splitNames == null) {
      return false;
    }

    return Arrays.binarySearch(splitNames, name) >= 0;
  }
}
