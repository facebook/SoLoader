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

package com.facebook.soloader.observer;

import com.facebook.soloader.SoSource;
import com.facebook.soloader.recovery.RecoveryStrategy;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

public class ObserverHolder {
  private static final AtomicReference<Observer[]> sObservers = new AtomicReference<Observer[]>();

  public static void resetObserversForTestsOnly() {
    sObservers.set(null);
  }

  public static void addObserver(Observer newObserver) {
    Observer[] oldObservers = null;
    Observer[] newObservers = null;

    do {
      oldObservers = sObservers.get();
      if (oldObservers == null) {
        newObservers = new Observer[] {newObserver};
      } else {
        newObservers = new Observer[oldObservers.length + 1];
        System.arraycopy(oldObservers, 0, newObservers, 0, oldObservers.length);
        newObservers[oldObservers.length] = newObserver;
      }
    } while (!sObservers.compareAndSet(oldObservers, newObservers));
  }

  public static void onLoadLibraryStart(String library, int flags) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onLoadLibraryStart(library, flags);
      }
    }
  }

  public static void onLoadLibraryEnd(@Nullable Throwable t) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onLoadLibraryEnd(t);
      }
    }
  }

  public static void onLoadDependencyStart(String library, int flags) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onLoadDependencyStart(library, flags);
      }
    }
  }

  public static void onLoadDependencyEnd(@Nullable Throwable t) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onLoadDependencyEnd(t);
      }
    }
  }

  public static void onSoSourceLoadLibraryStart(SoSource source) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onSoSourceLoadLibraryStart(source);
      }
    }
  }

  public static void onSoSourceLoadLibraryEnd(@Nullable Throwable t) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onSoSourceLoadLibraryEnd(t);
      }
    }
  }

  public static void onRecoveryStart(RecoveryStrategy recovery) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onRecoveryStart(recovery);
      }
    }
  }

  public static void onRecoveryEnd(@Nullable Throwable t) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onRecoveryEnd(t);
      }
    }
  }

  public static void onGetDependenciesStart() {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onGetDependenciesStart();
      }
    }
  }

  public static void onGetDependenciesEnd(@Nullable Throwable t) {
    Observer[] observers = sObservers.get();
    if (observers != null) {
      for (Observer observer : observers) {
        observer.onGetDependenciesEnd(t);
      }
    }
  }
}
