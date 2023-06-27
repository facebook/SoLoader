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
import android.os.StrictMode;
import java.io.Closeable;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
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

  /**
   * Actual file containing DSO dependencies information as described by the DSO manifest structure.
   * For a general UnpackingSoSource it simply encodes a list of pairs (name, hash) which specifies
   * what the DSOs to be unpacked are. This is used for now to do a deeper check and understand what
   * libraries have changed and only unpack those that are not up-to-date.
   * TODO(T155772778)(@adicatana)
   */
  private static final String MANIFEST_FILE_NAME = "dso_manifest";

  private static final byte STATE_DIRTY = 0;
  private static final byte STATE_CLEAN = 1;

  private static final byte MANIFEST_VERSION = 1;

  protected final Context mContext;
  @Nullable protected String mCorruptedLib;

  @Nullable private String[] mAbis;

  private final Map<String, Object> mLibsBeingLoaded = new HashMap<>();

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

  public static final class DsoManifest {

    public final Dso[] dsos;

    public DsoManifest(Dso[] dsos) {
      this.dsos = dsos;
    }

    /** @return Dso manifest, or {@code null} if manifest is corrupt or illegible. */
    static final DsoManifest read(DataInput xdi) throws IOException {
      int version = xdi.readByte();
      if (version != MANIFEST_VERSION) {
        throw new RuntimeException("wrong dso manifest version");
      }

      int nrDso = xdi.readInt();
      if (nrDso < 0) {
        throw new RuntimeException("illegal number of shared libraries");
      }

      Dso[] dsos = new Dso[nrDso];
      for (int i = 0; i < nrDso; ++i) {
        dsos[i] = new Dso(xdi.readUTF(), xdi.readUTF());
      }
      return new DsoManifest(dsos);
    }

    public final void write(DataOutput xdo) throws IOException {
      xdo.writeByte(MANIFEST_VERSION);
      xdo.writeInt(dsos.length);
      for (int i = 0; i < dsos.length; ++i) {
        xdo.writeUTF(dsos[i].name);
        xdo.writeUTF(dsos[i].hash);
      }
    }
  }

  protected static interface InputDso extends Closeable {

    public void write(DataOutput out, byte[] ioBuffer) throws IOException;

    public Dso getDso();

    public String getFileName();

    public int available() throws IOException;

    public InputStream getStream();
  };

  public static class InputDsoStream implements InputDso {
    private final Dso dso;
    private final InputStream content;

    public InputDsoStream(Dso dso, InputStream content) {
      this.dso = dso;
      this.content = content;
    }

    @Override
    public void write(DataOutput out, byte[] ioBuffer) throws IOException {
      SysUtil.copyBytes(out, content, Integer.MAX_VALUE, ioBuffer);
    }

    @Override
    public Dso getDso() {
      return dso;
    }

    @Override
    public String getFileName() {
      return dso.name;
    }

    @Override
    public int available() throws IOException {
      return content.available();
    }

    @Override
    public InputStream getStream() {
      return content;
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
    public abstract DsoManifest getDsoManifest() throws IOException;

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

  protected String getSoNameFromFileName(String fileName) {
    return fileName;
  }

  /** Delete files not mentioned in the given DSO list. */
  private void deleteUnmentionedFiles(Dso[] dsos) throws IOException {
    String[] existingFiles = soDirectory.list();
    if (existingFiles == null) {
      throw new IOException("unable to list directory " + soDirectory);
    }

    for (int i = 0; i < existingFiles.length; ++i) {
      String fileName = existingFiles[i];
      if (fileName.equals(STATE_FILE_NAME)
          || fileName.equals(LOCK_FILE_NAME)
          || fileName.equals(DEPS_FILE_NAME)
          || fileName.equals(MANIFEST_FILE_NAME)) {
        continue;
      }

      boolean found = false;
      for (int j = 0; !found && j < dsos.length; ++j) {
        if ((dsos[j].name).equals(getSoNameFromFileName(fileName))) {
          found = true;
        }
      }

      if (!found) {
        File fileNameToDelete = new File(soDirectory, fileName);
        LogUtil.v(TAG, "deleting unaccounted-for file " + fileNameToDelete);
        SysUtil.dumbDeleteRecursive(fileNameToDelete);
      }
    }
  }

  private void extractDso(InputDso iDso, byte[] ioBuffer) throws IOException {
    LogUtil.i(TAG, "extracting DSO " + iDso.getDso().name);
    try {
      if (!soDirectory.setWritable(true)) {
        throw new IOException("cannot make directory writable for us: " + soDirectory);
      }
      extractDsoImpl(iDso, ioBuffer);
    } finally {
      if (!soDirectory.setWritable(false)) {
        LogUtil.w(TAG, "error removing " + soDirectory.getCanonicalPath() + " write permission");
      }
    }
  }

  private void extractDsoImpl(InputDso iDso, byte[] ioBuffer) throws IOException {
    File dsoFileName = new File(soDirectory, iDso.getFileName());
    RandomAccessFile dsoFile = null;
    try {
      if (dsoFileName.exists() && !dsoFileName.setWritable(true)) {
        LogUtil.w(TAG, "error adding write permission to: " + dsoFileName);
      }

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
      iDso.write(dsoFile, ioBuffer);
      dsoFile.setLength(dsoFile.getFilePointer()); // In case we shortened file
      if (!dsoFileName.setExecutable(true /* allow exec... */, false /* ...for everyone */)) {
        throw new IOException("cannot make file executable: " + dsoFileName);
      }
    } catch (IOException e) {
      SysUtil.dumbDeleteRecursive(dsoFileName);
      throw e;
    } finally {
      if (!dsoFileName.setWritable(false)) {
        LogUtil.w(TAG, "error removing " + dsoFileName + " write permission");
      }
      if (dsoFile != null) {
        dsoFile.close();
      }
    }
  }

  private void regenerate(byte state, DsoManifest desiredManifest, InputDsoIterator dsoIterator)
      throws IOException {
    LogUtil.v(TAG, "regenerating DSO store " + getClass().getName());
    File manifestFileName = new File(soDirectory, MANIFEST_FILE_NAME);
    try (RandomAccessFile manifestFile = new RandomAccessFile(manifestFileName, "rw")) {
      DsoManifest existingManifest = null;
      if (state == STATE_CLEAN) {
        try {
          existingManifest = DsoManifest.read(manifestFile);
        } catch (Exception ex) {
          LogUtil.i(TAG, "error reading existing DSO manifest", ex);
        }
      }

      if (existingManifest == null) {
        existingManifest = new DsoManifest(new Dso[0]);
      }

      deleteUnmentionedFiles(desiredManifest.dsos);
      byte[] ioBuffer = new byte[32 * 1024];
      while (dsoIterator.hasNext()) {
        try (InputDso iDso = dsoIterator.next()) {
          boolean obsolete = true;
          for (int i = 0; obsolete && i < existingManifest.dsos.length; ++i) {
            String iDsoName = iDso.getDso().name;
            if (existingManifest.dsos[i].name.equals(iDsoName)
                && existingManifest.dsos[i].hash.equals(iDso.getDso().hash)) {
              obsolete = false;
            }
          }
          File dsoFile = new File(soDirectory, iDso.getFileName());
          if (!dsoFile.exists()) {
            // so file exists, but file name changed
            obsolete = true;
          }
          if (obsolete) {
            extractDso(iDso, ioBuffer);
          }
        }
      }
    }
    LogUtil.v(TAG, "Finished regenerating DSO store " + getClass().getName());
  }

  protected boolean depsChanged(final byte[] existingDeps, final byte[] deps) {
    return !Arrays.equals(existingDeps, deps);
  }

  protected boolean depsChanged(final byte[] deps) {
    final File depsFileName = new File(soDirectory, DEPS_FILE_NAME);
    try (RandomAccessFile depsFile = new RandomAccessFile(depsFileName, "rw")) {
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
   * Checks the state of the dso store related files (dso_manifest, dso_deps) and based on that
   * tries to unpack libraries and update with the new state on device.
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
    byte state;
    try (RandomAccessFile stateFile = new RandomAccessFile(stateFileName, "rw")) {
      try {
        state = stateFile.readByte();
        if (state != STATE_CLEAN) {
          LogUtil.v(TAG, "dso store " + soDirectory + " regeneration interrupted: wiping clean");
          state = STATE_DIRTY;
        }
      } catch (EOFException ex) {
        state = STATE_DIRTY;
        LogUtil.v(
            TAG, "dso store " + soDirectory + " regeneration interrupted: " + ex.getMessage());
      }
    }

    if (depsChanged(recomputedDeps)) {
      LogUtil.v(TAG, "deps mismatch on deps store: regenerating");
      state = STATE_DIRTY;
    }

    DsoManifest desiredManifest = null;
    if (state == STATE_DIRTY || forceRefresh(flags)) {
      LogUtil.v(TAG, "so store dirty: regenerating");
      writeState(stateFileName, STATE_DIRTY);

      try (Unpacker u = makeUnpacker()) {
        desiredManifest = u.getDsoManifest();
        try (InputDsoIterator idi = u.openDsoIterator()) {
          regenerate(state, desiredManifest, idi);
        }
      }
    }

    if (desiredManifest == null) {
      return false; // No sync needed
    }

    final DsoManifest manifest = desiredManifest;
    Runnable syncer =
        new Runnable() {
          @Override
          public void run() {
            try {
              try {
                LogUtil.v(TAG, "starting syncer worker");

                // N.B. We can afford to write the deps file and the manifest file without
                // synchronization or fsyncs because we've marked the DSO store STATE_DIRTY, which
                // will cause us to ignore all intermediate state when regenerating it.  That is,
                // it's okay for the depsFile or manifestFile blocks to hit the disk before the
                // actual DSO data file blocks as long as both hit the disk before we reset
                // STATE_CLEAN.
                final File depsFileName = new File(soDirectory, DEPS_FILE_NAME);
                try (RandomAccessFile depsFile = new RandomAccessFile(depsFileName, "rw")) {
                  depsFile.write(recomputedDeps);
                  depsFile.setLength(depsFile.getFilePointer());
                }

                File manifestFileName = new File(soDirectory, MANIFEST_FILE_NAME);
                try (RandomAccessFile manifestFile = new RandomAccessFile(manifestFileName, "rw")) {
                  manifest.write(manifestFile);
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
    FileLocker lock = null;
    try {
      try {
        lock = getOrCreateLock(lockFileName);
      } catch (IOException ioe) {
        LogUtil.w(TAG, "Encountered exception during wait for unpacking", ioe);
      } finally {
        if (lock != null) {
          lock.close();
        }
      }
    } catch (IOException ioe) {
      LogUtil.w(TAG, "Encountered exception during wait for unpacking trying to close lock", ioe);
    }
    return;
  }

  /**
   * Return an opaque blob of bytes that represents all the dependencies of this SoSource; if this
   * block differs from one we've previously saved, we go through the heavyweight refresh process
   * that involves calling {@link Unpacker#getDsoManifest} and {@link Unpacker#openDsoIterator}.
   *
   * <p>Subclasses should override this method if {@link Unpacker#getDsoManifest} is expensive.
   *
   * @return dependency block
   */
  protected byte[] getDepsBlock() throws IOException {
    // Parcel is fine: we never parse the parceled bytes, so it's okay if the byte representation
    // changes beneath us.
    Parcel parcel = Parcel.obtain();
    try (Unpacker u = makeUnpacker()) {
      Dso[] dsos = u.getDsoManifest().dsos;
      parcel.writeByte(MANIFEST_VERSION);
      parcel.writeInt(dsos.length);
      for (int i = 0; i < dsos.length; ++i) {
        parcel.writeString(dsos[i].name);
        parcel.writeString(dsos[i].hash);
      }
    }
    byte[] depsBlock = parcel.marshall();
    parcel.recycle();
    return depsBlock;
  }

  protected @Nullable FileLocker getOrCreateLock(File lockFileName) throws IOException {
    return SysUtil.getOrCreateLockOnDir(soDirectory, lockFileName);
  }

  /** Verify or refresh the state of the shared library store. */
  @Override
  protected void prepare(int flags) throws IOException {
    SysUtil.mkdirOrThrow(soDirectory);

    final boolean dirCanWrite = soDirectory.canWrite();
    FileLocker lock = null;
    try {
      if (!dirCanWrite && !soDirectory.setWritable(true)) {
        LogUtil.w(TAG, "error adding " + soDirectory.getCanonicalPath() + " write permission");
      }

      // LOCK_FILE_NAME is used to synchronize changes in the dso store.
      File lockFileName = new File(soDirectory, LOCK_FILE_NAME);
      lock = getOrCreateLock(lockFileName);

      LogUtil.v(TAG, "locked dso store " + soDirectory);

      // If another process holds the instance lock, it means that it already
      // initialized the so source and libraries might be getting loaded in that
      // process, so we can't refresh the so source.
      if (refreshLocked(lock, flags)) {
        lock = null; // Lock transferred to syncer thread
      } else {
        LogUtil.i(TAG, "dso store is up-to-date: " + soDirectory);
      }
    } finally {
      if (!dirCanWrite && !soDirectory.setWritable(false)) {
        LogUtil.w(TAG, "error removing " + soDirectory.getCanonicalPath() + " write permission");
      }

      if (lock != null) {
        LogUtil.v(TAG, "releasing dso store lock for " + soDirectory);
        lock.close();
      } else {
        LogUtil.v(
            TAG, "not releasing dso store lock for " + soDirectory + " (syncer thread started)");
      }
    }
  }

  private Object getLibraryLock(String soName) {
    synchronized (mLibsBeingLoaded) {
      Object lock = mLibsBeingLoaded.get(soName);
      if (lock == null) {
        lock = new Object();
        mLibsBeingLoaded.put(soName, lock);
      }
      return lock;
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
  public synchronized void prepare(String soName) throws IOException {
    // Only one thread at a time can try to recover a corrupted lib from the same source
    Object lock = getLibraryLock(soName);
    synchronized (lock) {
      // While recovering, do not allow loading the same lib from another thread
      mCorruptedLib = soName;
      prepare(SoSource.PREPARE_FLAG_FORCE_REFRESH);
    }
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    Object lock = getLibraryLock(soName);
    synchronized (lock) {
      // Holds a lock on the specific library being loaded to avoid trying to recover it in another
      // thread while loading
      return loadLibraryFrom(soName, loadFlags, soDirectory, threadPolicy);
    }
  }
}
