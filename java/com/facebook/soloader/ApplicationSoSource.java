/**
 * Copyright (c) 2015-present, Facebook, Inc. All rights reserved.
 *
 * <p>This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree. An additional grant of patent rights can be found in the PATENTS
 * file in the same directory.
 */
package com.facebook.soloader;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.StrictMode;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import javax.annotation.Nullable;

/** {@link SoSource} that wraps a DirectorySoSource for the application's nativeLibraryDir. */
public class ApplicationSoSource extends SoSource {

  private Context applicationContext;
  private int flags;
  private DirectorySoSource soSource;

  public ApplicationSoSource(Context context, int flags) {
    applicationContext = context.getApplicationContext();
    if (applicationContext == null) {
      Log.w(
          SoLoader.TAG,
          "context.getApplicationContext returned null, holding reference to original context.");
      applicationContext = context;
    }
    this.flags = flags;
    soSource =
        new DirectorySoSource(
            new File(applicationContext.getApplicationInfo().nativeLibraryDir), flags);
  }

  /**
   * check to make sure there haven't been any changes to the nativeLibraryDir since the last check,
   * if there have been changes, update the context and soSource
   *
   * @return true if the nativeLibraryDir was updated
   */
  public boolean checkAndMaybeUpdate() throws IOException {
    try {
      File nativeLibDir = soSource.soDirectory;
      Context updatedContext =
          applicationContext.createPackageContext(applicationContext.getPackageName(), 0);
      File updatedNativeLibDir = new File(updatedContext.getApplicationInfo().nativeLibraryDir);
      if (!nativeLibDir.equals(updatedNativeLibDir)) {
        Log.d(
            SoLoader.TAG,
            "Native library directory updated from " + nativeLibDir + " to " + updatedNativeLibDir);
        soSource = new DirectorySoSource(updatedNativeLibDir, flags);
        soSource.prepare(flags);
        applicationContext = updatedContext;
        return true;
      }
      return false;
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    return soSource.loadLibrary(soName, loadFlags, threadPolicy);
  }

  @Nullable
  @Override
  public File unpackLibrary(String soName) throws IOException {
    return soSource.unpackLibrary(soName);
  }

  @Override
  protected void prepare(int flags) throws IOException {
    soSource.prepare(flags);
  }

  @Override
  public void addToLdLibraryPath(Collection<String> paths) {
    soSource.addToLdLibraryPath(paths);
  }

  @Override
  public String toString() {
    return soSource.toString();
  }
}
