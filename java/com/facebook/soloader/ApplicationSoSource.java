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
    File nativeLibDir = soSource.soDirectory;
    Context updatedContext = getUpdatedContext();
    File updatedNativeLibDir = getNativeLibDirFromContext(updatedContext);
    if (!nativeLibDir.equals(updatedNativeLibDir)) {
      Log.d(
          SoLoader.TAG,
          "Native library directory updated from " + nativeLibDir + " to " + updatedNativeLibDir);
      // update flags to resolve dependencies since the system does not properly resolve
      // dependencies when the location has moved
      flags |= DirectorySoSource.RESOLVE_DEPENDENCIES;
      soSource = new DirectorySoSource(updatedNativeLibDir, flags);
      soSource.prepare(flags);
      applicationContext = updatedContext;
      return true;
    }
    return false;
  }

  public Context getUpdatedContext() {
    try {
      return applicationContext.createPackageContext(applicationContext.getPackageName(), 0);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static File getNativeLibDirFromContext(Context context) {
    return new File(context.getApplicationInfo().nativeLibraryDir);
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    return soSource.loadLibrary(soName, loadFlags, threadPolicy);
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    return soSource.getLibraryPath(soName);
  }

  @Nullable
  @Override
  public String[] getLibraryDependencies(String soName) throws IOException {
    return soSource.getLibraryDependencies(soName);
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
