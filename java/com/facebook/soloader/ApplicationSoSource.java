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
import android.os.StrictMode;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import javax.annotation.Nullable;

/** {@link SoSource} that wraps a DirectorySoSource for the application's nativeLibraryDir. */
public class ApplicationSoSource extends SoSource implements RecoverableSoSource {

  private final int flags;
  private DirectorySoSource soSource;

  public ApplicationSoSource(Context context, int flags) {
    this.flags = flags;
    this.soSource = new DirectorySoSource(getNativeLibDirFromContext(context), flags);
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

  @Override
  public SoSource recover(Context context) {
    soSource =
        new DirectorySoSource(
            getNativeLibDirFromContext(context), flags | DirectorySoSource.RESOLVE_DEPENDENCIES);
    return this;
  }
}
