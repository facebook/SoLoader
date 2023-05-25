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
import android.content.pm.PackageManager;
import android.os.StrictMode;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

/** {@link SoSource} that wraps a DirectorySoSource for the application's nativeLibraryDir. */
public class ApplicationSoSource extends SoSource {

  // AtomicReference acts as a shared mutable reference that can be updated by
  // ApplicationSoSource's recovery mechanism.
  private final AtomicReference<Context> contextHolder;
  private int flags;
  private DirectorySoSource soSource;

  public ApplicationSoSource(AtomicReference<Context> contextHolder, int flags) {
    this.contextHolder = contextHolder;
    this.flags = flags;
    soSource =
        new DirectorySoSource(
            new File(contextHolder.get().getApplicationInfo().nativeLibraryDir), flags);
  }

  /**
   * check to make sure there haven't been any changes to the nativeLibraryDir since the last check,
   * if there have been changes, update the context and soSource
   *
   * @return true if the nativeLibraryDir was updated
   * @throws IOException IOException
   */
  public boolean checkAndMaybeUpdate() throws IOException {
    File nativeLibDir = soSource.soDirectory;
    try {
      Context updatedContext = getUpdatedContext();
      File updatedNativeLibDir = getNativeLibDirFromContext(updatedContext);
      if (!nativeLibDir.equals(updatedNativeLibDir)) {
        LogUtil.d(
            SoLoader.TAG,
            "Native library directory updated from " + nativeLibDir + " to " + updatedNativeLibDir);
        // update flags to resolve dependencies since the system does not properly resolve
        // dependencies when the location has moved
        flags |= DirectorySoSource.RESOLVE_DEPENDENCIES;
        soSource = new DirectorySoSource(updatedNativeLibDir, flags);
        soSource.prepare(flags);
        contextHolder.set(updatedContext);
        return true;
      }
    } catch (PackageManager.NameNotFoundException e) {
      LogUtil.w(SoLoader.TAG, "Can not find the package during checkAndMaybeUpdate ", e);
    }
    return false;
  }

  public Context getUpdatedContext() throws PackageManager.NameNotFoundException {
    return contextHolder.get().createPackageContext(contextHolder.get().getPackageName(), 0);
  }

  private static File getNativeLibDirFromContext(Context context) {
    return new File(context.getApplicationInfo().nativeLibraryDir);
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    return soSource.loadLibrary(soName, loadFlags, threadPolicy);
  }

  @Override
  @Nullable
  public File getSoFileByName(String soName) throws IOException {
    return soSource.getSoFileByName(soName);
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
    return new StringBuilder()
        .append("ApplicationSoSource[")
        .append(soSource.toString())
        .append("]")
        .toString();
  }
}
