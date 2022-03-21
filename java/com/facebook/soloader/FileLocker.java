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

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import javax.annotation.Nullable;

public final class FileLocker implements Closeable {

  private FileOutputStream mLockFileOutputStream;
  private @Nullable FileLock mLock;

  public static FileLocker lock(File lockFile) throws IOException {
    return new FileLocker(lockFile, false);
  }

  public static @Nullable FileLocker tryLock(File lockFile) throws IOException {
    FileLocker fileLocker = new FileLocker(lockFile, true);
    if (fileLocker.mLock == null) {
      fileLocker.close();
      return null;
    }
    return fileLocker;
  }

  private void init(File lockFile, boolean tryLock) throws IOException {
    mLockFileOutputStream = new FileOutputStream(lockFile);
    FileLock lock = null;
    try {
      if (tryLock) {
        try {
          lock = mLockFileOutputStream.getChannel().tryLock();
        } catch (IOException | OverlappingFileLockException e) {
          // Try lock can throw an IOException (EAGAIN) while lock doesn't.
          // If this process already holds the lock this will throw OverlappingFileLockException as a RuntimeException.
          // Ideally, this code would not try to init if the lock is held elsewhere, but this is the most
          // straightforward fix for now
          lock = null;
        }
      } else {
        lock = mLockFileOutputStream.getChannel().lock();
      }
    } finally {
      if (lock == null) {
        mLockFileOutputStream.close();
      }
    }

    mLock = lock;
  }

  private FileLocker(File lockFile, boolean tryLock) throws IOException {
    init(lockFile, tryLock);
  }

  @Override
  public void close() throws IOException {
    try {
      if (mLock != null) {
        mLock.release();
      }
    } finally {
      mLockFileOutputStream.close();
    }
  }
}
