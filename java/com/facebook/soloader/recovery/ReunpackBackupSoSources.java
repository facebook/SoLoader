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

package com.facebook.soloader.recovery;

import com.facebook.soloader.BackupSoSource;
import com.facebook.soloader.DirectorySoSource;
import com.facebook.soloader.LogUtil;
import com.facebook.soloader.SoLoader;
import com.facebook.soloader.SoLoaderDSONotFoundError;
import com.facebook.soloader.SoLoaderULError;
import com.facebook.soloader.SoSource;
import java.io.IOException;

/**
 * RecoveryStrategy that detects cases when SoLoader failed to load a corrupted library, case in
 * which we try to re-unpack the libraries. Only for BackupSoSources
 *
 * <p>Guards against a known Android OS bug where the installer incorrectly unpacks libraries under
 * /data/app
 */
public class ReunpackBackupSoSources implements RecoveryStrategy {

  private int mRecoveryFlags;

  public ReunpackBackupSoSources() {
    this(0);
  }

  public ReunpackBackupSoSources(int recoveryFlags) {
    mRecoveryFlags = recoveryFlags;
  }

  @Override
  public boolean recover(UnsatisfiedLinkError error, SoSource[] soSources) {
    if (!(error instanceof SoLoaderULError)) {
      // Only recover from SoLoaderULE errors
      return false;
    }
    SoLoaderULError err = (SoLoaderULError) error;
    String soName = err.getSoName();
    String message = err.getMessage();

    if (soName == null) {
      LogUtil.e(SoLoader.TAG, "No so name provided in ULE, cannot recover");
      return false;
    }

    if (err instanceof SoLoaderDSONotFoundError) {
      if ((mRecoveryFlags
              & RecoveryStrategy.FLAG_ENABLE_DSONOTFOUND_ERROR_RECOVERY_FOR_BACKUP_SO_SOURCE)
          != 0) {

        // Recover if DSO is not found
        logRecovery(err, soName);

        return recoverDSONotFoundError(soSources, soName, 0);
      } else {
        return false;
      }
    } else if (message == null || (!message.contains("/app/") && !message.contains("/mnt/"))) {
      // Don't recover if the DSO wasn't in the data/app directory

      return false;
    } else {
      logRecovery(err, soName);
      return lazyPrepareBackupSoSource(soSources, soName);
    }
  }

  private boolean recoverDSONotFoundError(SoSource[] soSources, String soName, int prepareFlags) {
    try {
      for (SoSource soSource : soSources) {
        if (soSource == null || !(soSource instanceof BackupSoSource)) {
          continue;
        }
        BackupSoSource uss = (BackupSoSource) soSource;

        if (uss.peekAndPrepareSoSource(soName, prepareFlags)) {
          return true;
        }
      }
      return false;
    } catch (IOException ioException) {
      LogUtil.e(SoLoader.TAG, "Failed to run recovery for backup so source due to: " + ioException);
      return false;
    }
  }

  private boolean lazyPrepareBackupSoSource(SoSource[] soSources, String soName) {
    boolean recovered = false;
    for (SoSource soSource : soSources) {
      if (soSource == null || !(soSource instanceof BackupSoSource)) {
        // NonApk SoSources get reunpacked in ReunpackNonBackupSoSource recovery strategy
        continue;
      }
      BackupSoSource backupSoSource = (BackupSoSource) soSource;
      try {
        LogUtil.e(
            SoLoader.TAG,
            "Preparing BackupSoSource for the first time " + backupSoSource.getName());
        backupSoSource.prepare(0);
        recovered = true;
        break;
      } catch (Exception e) {
        // Catch a general error and log it, rather than failing during recovery and crashing the
        // app
        // in a different way.
        LogUtil.e(
            SoLoader.TAG,
            "Encountered an exception while reunpacking BackupSoSource "
                + backupSoSource.getName()
                + " for library "
                + soName
                + ": ",
            e);
        return false;
      }
    }

    if (recovered) {
      for (SoSource soSource : soSources) {
        if (soSource == null || !(soSource instanceof DirectorySoSource)) {
          continue;
        }
        if (soSource instanceof BackupSoSource) {
          continue;
        }
        DirectorySoSource directorySoSource = (DirectorySoSource) soSource;
        // We need to explicitly resolve dependencies, as dlopen() cannot do
        // so for dependencies at non-standard locations.
        directorySoSource.setExplicitDependencyResolution();
      }
      return true;
    }

    return false;
  }

  private void logRecovery(Error error, String soName) {
    LogUtil.e(
        SoLoader.TAG,
        "Reunpacking BackupSoSources due to "
            + error
            + ", retrying for specific library "
            + soName);
  }
}
