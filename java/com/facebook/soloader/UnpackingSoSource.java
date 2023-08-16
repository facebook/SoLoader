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
import android.os.Parcel;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import javax.annotation.Nullable;

/** {@link SoSource} that extracts libraries from an APK to the filesystem. */
public abstract class UnpackingSoSource extends DirectorySoSource implements AsyncInitSoSource {

  private static final String TAG = "fb-UnpackingSoSource";

  /** File that contains state information (STATE_DIRTY or STATE_CLEAN). */
  private static final String STATE_FILE_NAME = "dso_state";

  /**
   * File used to synchronize changes in the dso store. For example, it is helpful to acquire this
   * lock to wait for other unpacking sources.
   */
  private static final String LOCK_FILE_NAME = "dso_lock";

  /**
   * File containing an opaque blob of bytes that represents all the dependencies of a SoSource. For
   * example for a plain ExtractFromZipSource you'd have a simple list of (name, hash) pairs like
   * (com.facebook.soloader.ExtractFromZipSoSource$ZipDso@9d65e11a,pseudo-zip-hash-1-lib/arm64-v8a/foo-64.so-7720-1820-260126021)
   */
  private static final String DEPS_FILE_NAME = "dso_deps";

  private static final byte STATE_DIRTY = 0;
  private static final byte STATE_CLEAN = 1;

  protected final Context mContext;
  @Nullable protected String mCorruptedLib;

  @Nullable private String[] mAbis;

  protected UnpackingSoSource(Context context, String name) {
    super(getSoStorePath(context, name), RESOLVE_DEPENDENCIES);
    mContext = context;
  }

  protected UnpackingSoSource(Context context, File storePath) {
    super(storePath, RESOLVE_DEPENDENCIES);
    mContext = context;
  }

  public static File getSoStorePath(Context context, String name) {
    return new File(context.getApplicationInfo().dataDir + "/" + name);
  }

  protected abstract Unpacker makeUnpacker() throws IOException;

  @Override
  public String[] getSoSourceAbis() {
    if (mAbis == null) {
      return super.getSoSourceAbis();
    }

    return mAbis;
  }

  public void setSoSourceAbis(final String[] abis) {
    mAbis = abis;
  }

  public static class Dso {
    public final String name;
    public final String hash;

    public Dso(String name, String hash) {
      this.name = name;
      this.hash = hash;
    }
  }

  protected static final class InputDso implements Closeable {
    private final Dso dso;
    private final InputStream content;

    public InputDso(Dso dso, InputStream content) {
      this.dso = dso;
      this.content = content;
    }

    public Dso getDso() {
      return dso;
    }

    public int available() throws IOException {
      return content.available();
    }

    @Override
    public void close() throws IOException {
      content.close();
    }
  }

  protected abstract static class InputDsoIterator implements Closeable {

    public abstract boolean hasNext();

    public abstract @Nullable InputDso next() throws IOException;

    @Override
    public void close() throws IOException {
      /* By default, do nothing */
    }
  }

  protected abstract static class Unpacker implements Closeable {
    public abstract Dso[] getDsos() throws IOException;

    public abstract InputDsoIterator openDsoIterator() throws IOException;

    @Override
    public void close() throws IOException {
      /* By default, do nothing */
    }
  }

  private static void writeState(File stateFileName, byte state) throws IOException {
    try (RandomAccessFile stateFile = new RandomAccessFile(stateFileName, "rw")) {
      stateFile.seek(0);
      stateFile.write(state);
      stateFile.setLength(stateFile.getFilePointer());
      stateFile.getFD().sync();
    } catch (SyncFailedException e) {
      LogUtil.w(TAG, "state file sync failed", e);
    }
  }

  /** Delete files not mentioned in the given DSO list. */
  private void deleteUnmentionedFiles(Dso[] dsos) throws IOException {
    String[] existingFiles = soDirectory.list();
    if (existingFiles == null) {
      throw new IOException("unable to list directory " + soDirectory);
    }

    for (String fileName : existingFiles) {
      if (fileName.equals(STATE_FILE_NAME)
          || fileName.equals(LOCK_FILE_NAME)
          || fileName.equals(DEPS_FILE_NAME)) {
        continue;
      }

      boolean found = false;
      for (Dso dso : dsos) {
        if (dso.name.equals(fileName)) {
          found = true;
          break;
        }
      }
      if (found) {
        continue;
      }

      File fileNameToDelete = new File(soDirectory, fileName);
      LogUtil.v(TAG, "deleting unaccounted-for file " + fileNameToDelete);
      SysUtil.dumbDeleteRecursive(fileNameToDelete);
    }
  }

  private void extractDso(InputDso iDso, byte[] ioBuffer) throws IOException {
    LogUtil.i(TAG, "extracting DSO " + iDso.getDso().name);
    File dsoFileName = new File(soDirectory, iDso.getDso().name);
    if (dsoFileName.exists() && !dsoFileName.setWritable(true)) {
      throw new IOException(
          "error adding write permission to: "
              + dsoFileName
              + " under "
              + soDirectory
              + " (is writable: "
              + soDirectory.canWrite()
              + ")");
    }

    RandomAccessFile dsoFile = null;
    try {
      try {
        dsoFile = new RandomAccessFile(dsoFileName, "rw");
      } catch (IOException ex) {
        LogUtil.w(TAG, "error overwriting " + dsoFileName + " trying to delete and start over", ex);
        SysUtil.dumbDeleteRecursive(dsoFileName); // Throws on error; not existing is okay
        dsoFile = new RandomAccessFile(dsoFileName, "rw");
      }

      int sizeHint = iDso.available();
      if (sizeHint > 1) {
        SysUtil.fallocateIfSupported(dsoFile.getFD(), sizeHint);
      }
      SysUtil.copyBytes(dsoFile, iDso.content, Integer.MAX_VALUE, ioBuffer);
      dsoFile.setLength(dsoFile.getFilePointer()); // In case we shortened file
      if (!dsoFileName.setExecutable(true /* allow exec... */, false /* ...for everyone */)) {
        throw new IOException("cannot make file executable: " + dsoFileName);
      }
    } catch (IOException e) {
      LogUtil.e(TAG, "error extracting dsos: " + e);
      SysUtil.dumbDeleteRecursive(dsoFileName);
      throw e;
    } finally {
      if (dsoFile != null) {
        dsoFile.close();
      }
      if (dsoFileName.exists() && !dsoFileName.setWritable(false)) {
        throw new IOException(
            "error removing "
                + dsoFileName
                + " write permission from directory "
                + soDirectory
                + " (writable: "
                + soDirectory.canWrite()
                + ")");
      }
    }
  }

  private void regenerate(InputDsoIterator dsoIterator) throws IOException {
    LogUtil.v(TAG, "regenerating DSO store " + getClass().getName());
    byte[] ioBuffer = new byte[32 * 1024];
    while (dsoIterator.hasNext()) {
      try (InputDso iDso = dsoIterator.next()) {
        extractDso(iDso, ioBuffer);
      }
    }
    LogUtil.v(TAG, "Finished regenerating DSO store " + getClass().getName());
  }

  protected MessageDigest getHashingAlgorithm() throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-256");
  }

  protected String computeFileHash(File file) {
    MessageDigest md;
    try {
      md = getHashingAlgorithm();
    } catch (NoSuchAlgorithmException e) {
      LogUtil.w(TAG, "Failed to calculate hash for " + file.getName(), e);
      return "-1";
    }

    try (FileInputStream fis = new FileInputStream(file);
        DigestInputStream dis = new DigestInputStream(fis, md)) {
      byte[] buffer = new byte[8192];
      while (dis.read(buffer) != -1) {}

      byte[] hashBytes = md.digest();
      StringBuilder sb = new StringBuilder(hashBytes.length * 2);
      for (byte b : hashBytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (IOException e) {
      LogUtil.w(TAG, "Failed to calculate hash for " + file.getName(), e);
      return "-1";
    }
  }

  /**
   * Checks whether a library on disk is corrupt by checking file existence and hash.
   *
   * @param dso Library we expect
   * @param dsoFile Library file on disk to check
   * @return True iff library file is corrupted or invalid or nonexistent
   */
  private boolean libraryIsCorrupted(Dso dso, File dsoFile) {
    if (!dsoFile.exists()) {
      return true;
    }

    String expectedHash = dso.hash;
    String actualHash = computeFileHash(dsoFile);
    if (!expectedHash.equals(actualHash)) {
      return true;
    }

    return false;
  }

  protected boolean depsChanged(final byte[] existingDeps, final byte[] deps) {
    return !Arrays.equals(existingDeps, deps);
  }

  protected boolean depsChanged(final byte[] deps) {
    final File depsFileName = new File(soDirectory, DEPS_FILE_NAME);
    try (RandomAccessFile depsFile = new RandomAccessFile(depsFileName, "rw")) {
      if (depsFile.length() == 0) {
        return true;
      }

      byte[] existingDeps = new byte[(int) depsFile.length()];
      if (depsFile.read(existingDeps) != existingDeps.length) {
        LogUtil.v(TAG, "short read of so store deps file: marking unclean");
        return true;
      }

      return depsChanged(existingDeps, deps);
    } catch (IOException ioe) {
      LogUtil.w(TAG, "failed to compare whether deps changed", ioe);
      return true;
    }
  }

  private static boolean forceRefresh(int flags) {
    return (flags & SoSource.PREPARE_FLAG_FORCE_REFRESH) != 0;
  }

  private static boolean rewriteStateAsync(int flags) {
    return (flags & PREPARE_FLAG_ALLOW_ASYNC_INIT) != 0;
  }

  /**
   * Checks the state of the dso store related files (dso_deps) and based on that tries to unpack
   * libraries and update with the new state on device.
   *
   * @param lock - Lock that's already been acquired over the state of the dso store
   * @param flags - * @param flags To pass PREPARE_FLAG_FORCE_REFRESH and/or
   *     PREPARE_FLAG_ALLOW_ASYNC_INIT. PREPARE_FLAG_FORCE_REFRESH will force a re-unpack,
   *     PREPARE_FLAG_ALLOW_ASYNC_INIT will spawn threads to do some background work.
   * @return Unpacking was successful and the on-device dso store state updated accordingly.
   * @throws IOException
   */
  private boolean refreshLocked(final FileLocker lock, final int flags) throws IOException {
    final File stateFileName = new File(soDirectory, STATE_FILE_NAME);
    final byte[] recomputedDeps = getDepsBlock();
    // By default, if we're forcing a refresh or dependencies have changed the state is dirty
    byte state = STATE_DIRTY;
    if (!forceRefresh(flags) && !depsChanged(recomputedDeps)) {
      try (RandomAccessFile stateFile = new RandomAccessFile(stateFileName, "rw")) {
        // If the stateFile is not one byte don't even bother reading from it
        if (stateFile.length() == 1) {
          try {
            byte onDiskState = stateFile.readByte();
            if (onDiskState == STATE_CLEAN) {
              LogUtil.v(
                  TAG, "dso store " + soDirectory + " regeneration not needed: state file clean");
              state = onDiskState;
            }
          } catch (IOException ex) {
            LogUtil.v(
                TAG, "dso store " + soDirectory + " regeneration interrupted: " + ex.getMessage());
          }
        }
      }
    }

    if (state == STATE_CLEAN) {
      return false; // No unpacking needed
    }

    LogUtil.v(TAG, "so store dirty: regenerating");
    writeState(stateFileName, STATE_DIRTY);
    Dso[] desiredDsos = null;
    try (Unpacker u = makeUnpacker()) {
      desiredDsos = u.getDsos();
      deleteUnmentionedFiles(desiredDsos);
      try (InputDsoIterator idi = u.openDsoIterator()) {
        regenerate(idi);
      }
    }

    if (desiredDsos == null) {
      return false; // No sync needed
    }

    // Task to update the on-device state of the DSO store. Can be run in a different thread if the
    // appropriate flag is passed (PREPARE_FLAG_ALLOW_ASYNC_INIT). Note that the syncer is
    // responsible of releasing the store lock.
    Runnable syncer =
        new Runnable() {
          @Override
          public void run() {
            LogUtil.v(TAG, "starting syncer worker");
            try {
              try {
                // N.B. We can afford to write the deps file and without
                // synchronization or fsyncs because we've marked the DSO store STATE_DIRTY, which
                // will cause us to ignore all intermediate state when regenerating it.  That is,
                // it's okay for the depsFile blocks to hit the disk before the
                // actual DSO data file blocks as long as both hit the disk before we reset
                // STATE_CLEAN.
                final File depsFileName = new File(soDirectory, DEPS_FILE_NAME);
                try (RandomAccessFile depsFile = new RandomAccessFile(depsFileName, "rw")) {
                  depsFile.write(recomputedDeps);
                  depsFile.setLength(depsFile.getFilePointer());
                }

                SysUtil.fsyncRecursive(soDirectory);
                writeState(stateFileName, STATE_CLEAN);
              } finally {
                LogUtil.v(
                    TAG, "releasing dso store lock for " + soDirectory + " (from syncer thread)");
                lock.close();
              }
            } catch (IOException ex) {
              throw new RuntimeException(ex);
            }
          }
        };

    if (rewriteStateAsync(flags)) {
      new Thread(syncer, "SoSync:" + soDirectory.getName()).start();
    } else {
      syncer.run();
    }

    return true;
  }

  @Override
  public void waitUntilInitCompleted() {
    File lockFileName = new File(soDirectory, LOCK_FILE_NAME);
    try (FileLocker lock = SysUtil.getOrCreateLockOnDir(soDirectory, lockFileName)) {
      // The lock file should be held by other processes trying to unpack libraries or recovering
    } catch (Exception e) {
      LogUtil.e(
          TAG,
          "Encountered exception during wait for unpacking trying to acquire file lock for "
              + getClass().getName()
              + " ("
              + soDirectory
              + "): ",
          e);
    }
  }

  /**
   * Return an opaque blob of bytes that represents all the dependencies of this SoSource; if this
   * block differs from one we've previously saved, we go through the heavyweight refresh process
   * that involves calling {@link Unpacker#openDsoIterator}.
   *
   * <p>Subclasses should override this method if {@link Unpacker#getDsos} is expensive.
   *
   * @return dependency block
   */
  protected byte[] getDepsBlock() throws IOException {
    // Parcel is fine: we never parse the parceled bytes, so it's okay if the byte representation
    // changes beneath us.
    Parcel parcel = Parcel.obtain();
    try (Unpacker u = makeUnpacker()) {
      Dso[] dsos = u.getDsos();
      parcel.writeInt(dsos.length);
      for (Dso dso : dsos) {
        parcel.writeString(dso.name);
        parcel.writeString(dso.hash);
      }
    }
    byte[] depsBlock = parcel.marshall();
    parcel.recycle();
    return depsBlock;
  }

  /** Verify or refresh the state of the shared library store. */
  @Override
  protected void prepare(int flags) throws IOException {
    SysUtil.mkdirOrThrow(soDirectory);

    if (!soDirectory.canWrite() && !soDirectory.setWritable(true)) {
      throw new IOException("error adding " + soDirectory.getCanonicalPath() + " write permission");
    }

    try {
      FileLocker lock = null;
      try {
        // LOCK_FILE_NAME is used to synchronize changes in the dso store.
        File lockFileName = new File(soDirectory, LOCK_FILE_NAME);
        lock = SysUtil.getOrCreateLockOnDir(soDirectory, lockFileName);
        LogUtil.v(TAG, "locked dso store " + soDirectory);

        // There might've been another process that revoked the write permission while we
        // waited for the lock.
        if (!soDirectory.canWrite() && !soDirectory.setWritable(true)) {
          throw new IOException(
              "error adding " + soDirectory.getCanonicalPath() + " write permission");
        }

        if (refreshLocked(lock, flags)) {
          lock = null; // Lock transferred to syncer thread
        } else {
          LogUtil.i(TAG, "dso store is up-to-date: " + soDirectory);
        }
      } finally {
        if (lock != null) {
          LogUtil.v(TAG, "releasing dso store lock for " + soDirectory);
          lock.close();
        } else {
          LogUtil.v(
              TAG, "not releasing dso store lock for " + soDirectory + " (syncer thread started)");
        }
      }
    } finally {
      if (soDirectory.canWrite() && !soDirectory.setWritable(false)) {
        throw new IOException(
            "error removing " + soDirectory.getCanonicalPath() + " write permission");
      }
    }
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    File soFile = getSoFileByName(soName);
    if (soFile == null) {
      return null;
    }
    return soFile.getCanonicalPath();
  }

  /** Prepare this SoSource extracting a corrupted library. */
  public void prepare(String soName) throws IOException {
    mCorruptedLib = soName;
    prepareForceRefresh();
  }

  /** Prepare this SoSource by force extracting a corrupted library. */
  public void prepareForceRefresh() throws IOException {
    prepare(SoSource.PREPARE_FLAG_FORCE_REFRESH);
  }
}
