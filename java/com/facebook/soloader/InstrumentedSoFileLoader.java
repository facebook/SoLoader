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
import com.facebook.soloader.observer.ObserverHolder;
import javax.annotation.Nullable;

public class InstrumentedSoFileLoader implements SoFileLoader {
  private final SoFileLoader mDelegate;

  public InstrumentedSoFileLoader(SoFileLoader delegate) {
    mDelegate = delegate;
  }

  @Override
  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  public void load(String pathToSoFile, int loadFlags) {
    @Nullable Throwable failure = null;
    ObserverHolder.onSoFileLoaderLoadStart(mDelegate, "load", loadFlags);
    try {
      mDelegate.load(pathToSoFile, loadFlags);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onSoFileLoaderLoadEnd(failure);
    }
  }

  @Override
  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  public void loadBytes(String pathName, ElfByteChannel bytes, int loadFlags) {
    @Nullable Throwable failure = null;
    ObserverHolder.onSoFileLoaderLoadStart(mDelegate, "loadBytes", loadFlags);
    try {
      mDelegate.loadBytes(pathName, bytes, loadFlags);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onSoFileLoaderLoadEnd(failure);
    }
  }
}
