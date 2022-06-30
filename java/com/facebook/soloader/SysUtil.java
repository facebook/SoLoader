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
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Parcel;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;
import dalvik.system.BaseDexClassLoader;
import java.io.DataOutput;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;

public final class SysUtil {

  private static final String TAG = "SysUtil";

  private static final byte APK_SIGNATURE_VERSION = 1;

  private static final long APK_DEP_BLOCK_METADATA_LENGTH =
      4 + // APK_SIGNATURE_VERSION
          8 + // Apk file last modified time stamp
          4 + // App version code
          4; // Apk file length

  /**
   * Determine how preferred a given ABI is on this system.
   *
   * @param supportedAbis ABIs on this system
   * @param abi ABI of a shared library we might want to unpack
   * @return -1 if not supported or an integer, smaller being more preferred
   */
  public static int findAbiScore(String[] supportedAbis, String abi) {
    for (int i = 0; i < supportedAbis.length; ++i) {
      if (supportedAbis[i] != null && abi.equals(supportedAbis[i])) {
        return i;
      }
    }

    return -1;
  }

  public static void deleteOrThrow(File file) throws IOException {
    final File folder = file.getParentFile();
    // We need write permission on parent folder to delete the file
    if (folder != null && !folder.canWrite() && !folder.setWritable(true)) {
      Log.e(TAG, "Enable write permission failed: " + folder);
    }

    if (!file.delete() && file.exists()) {
      throw new IOException("Could not delete file " + file);
    }
  }

  /**
   * Return an list of ABIs we supported on this device ordered according to preference. Use a
   * separate inner class to isolate the version-dependent call where it won't cause the whole class
   * to fail preverification.
   *
   * @return Ordered array of supported ABIs
   */
  public static String[] getSupportedAbis() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return MarshmallowSysdeps.getSupportedAbis();
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      return LollipopSysdeps.getSupportedAbis();
    } else {
      return new String[] {Build.CPU_ABI, Build.CPU_ABI2};
    }
  }

  /**
   * Pre-allocate disk space for a file if we can do that on this version of the OS.
   *
   * @param fd File descriptor for file
   * @param length Number of bytes to allocate.
   */
  public static void fallocateIfSupported(FileDescriptor fd, long length) throws IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      LollipopSysdeps.fallocateIfSupported(fd, length);
    }
  }

  /**
   * Delete a directory and its contents.
   *
   * <p>WARNING: Java APIs do not let us distinguish directories from symbolic links to directories.
   * Consequently, if the directory contains symbolic links to directories, we will attempt to
   * delete the contents of pointed-to directories.
   *
   * @param file File or directory to delete
   */
  public static void dumbDeleteRecursive(File file) throws IOException {
    if (file.isDirectory()) {
      File[] fileList = file.listFiles();
      if (fileList == null) {
        // If file is not a directory, listFiles() will return null
        return;
      }
      for (File entry : fileList) {
        dumbDeleteRecursive(entry);
      }
    }

    deleteOrThrow(file);
  }

  /**
   * Encapsulate Lollipop-specific calls into an independent class so we don't fail preverification
   * downlevel.
   */
  @TargetApi(Build.VERSION_CODES.LOLLIPOP)
  @DoNotOptimize
  private static final class LollipopSysdeps {
    @DoNotOptimize
    public static String[] getSupportedAbis() {
      String[] supportedAbis = Build.SUPPORTED_ABIS;
      TreeSet<String> allowedAbis = new TreeSet<>();
      try {
        // Some devices report both 64-bit and 32-bit ABIs but *actually* run
        // the process in 32-bit mode.
        //
        // Determine the current process bitness and use that to filter
        // out incompatible ABIs from SUPPORTED_ABIS.
        if (is64Bit()) {
          allowedAbis.add(MinElf.ISA.AARCH64.toString());
          allowedAbis.add(MinElf.ISA.X86_64.toString());
        } else {
          allowedAbis.add(MinElf.ISA.ARM.toString());
          allowedAbis.add(MinElf.ISA.X86.toString());
        }
      } catch (ErrnoException e) {
        Log.e(
            TAG,
            String.format(
                "Could not read /proc/self/exe. Falling back to default ABI list: %s. errno: %d Err msg: %s",
                Arrays.toString(supportedAbis), e.errno, e.getMessage()));
        return Build.SUPPORTED_ABIS;
      }
      // Filter out the incompatible ABIs from the list of supported ABIs,
      // retaining the original order.
      ArrayList<String> compatibleSupportedAbis = new ArrayList<>();
      for (String abi : supportedAbis) {
        if (allowedAbis.contains(abi)) {
          compatibleSupportedAbis.add(abi);
        }
      }

      String[] finalAbis = new String[compatibleSupportedAbis.size()];
      finalAbis = compatibleSupportedAbis.toArray(finalAbis);

      return finalAbis;
    }

    @DoNotOptimize
    public static void fallocateIfSupported(FileDescriptor fd, long length) throws IOException {
      try {
        Os.posix_fallocate(fd, 0, length);
      } catch (ErrnoException ex) {
        if (ex.errno != OsConstants.EOPNOTSUPP
            && ex.errno != OsConstants.ENOSYS
            && ex.errno != OsConstants.EINVAL) {
          throw new IOException(ex.toString(), ex);
        }
      }
    }

    @DoNotOptimize
    public static boolean is64Bit() throws ErrnoException {
      return Os.readlink("/proc/self/exe").contains("64");
    }
  }

  @TargetApi(Build.VERSION_CODES.M)
  @DoNotOptimize
  private static final class MarshmallowSysdeps {
    @DoNotOptimize
    public static String[] getSupportedAbis() {
      String[] supportedAbis = Build.SUPPORTED_ABIS;
      TreeSet<String> allowedAbis = new TreeSet<>();
      // Some devices report both 64-bit and 32-bit ABIs but *actually* run
      // the process in 32-bit mode.
      //
      // Determine the current process bitness and use that to filter
      // out incompatible ABIs from SUPPORTED_ABIS.
      if (is64Bit()) {
        allowedAbis.add(MinElf.ISA.AARCH64.toString());
        allowedAbis.add(MinElf.ISA.X86_64.toString());
      } else {
        allowedAbis.add(MinElf.ISA.ARM.toString());
        allowedAbis.add(MinElf.ISA.X86.toString());
      }
      // Filter out the incompatible ABIs from the list of supported ABIs,
      // retaining the original order.
      ArrayList<String> compatibleSupportedAbis = new ArrayList<>();
      for (String abi : supportedAbis) {
        if (allowedAbis.contains(abi)) {
          compatibleSupportedAbis.add(abi);
        }
      }

      String[] finalAbis = new String[compatibleSupportedAbis.size()];
      finalAbis = compatibleSupportedAbis.toArray(finalAbis);

      return finalAbis;
    }

    @DoNotOptimize
    public static boolean is64Bit() {
      return android.os.Process.is64Bit();
    }

    public static boolean isSupportedDirectLoad(Context context, int appType) throws IOException {
      if (appType == SoLoader.AppType.SYSTEM_APP) {
        // Ideally, system_app permanently stores dso files uncompressed and page-aligned, even with
        // FLAG_EXTRACT_NATIVE_LIBS flag. But to support a specific Oculus's sideload method, we
        // need this extra checking. ref: D27831042
        return isApkUncompressedDso(context);
      } else {
        return isDisabledExtractNativeLibs(context);
      }
    }

    public static boolean isDisabledExtractNativeLibs(Context context) {
      return context != null
          && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) == 0;
    }

    private static boolean isApkUncompressedDso(Context context) throws IOException {
      File apkFile = new File(context.getApplicationInfo().sourceDir);
      try (ZipFile mZipFile = new ZipFile(apkFile)) {
        Enumeration<? extends ZipEntry> entries = mZipFile.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          if (entry != null
              && entry.getName().endsWith(".so")
              && entry.getName().contains("/lib")) {
            // Checking one dso item is good enough.
            return entry.getMethod() == ZipEntry.STORED;
          }
        }
      }
      return false;
    }
  }

  /**
   * Like File.mkdirs, but throws on error. Succeeds even if File.mkdirs "fails", but dir still
   * names a directory.
   *
   * @param dir Directory to create. All parents created as well.
   */
  public static void mkdirOrThrow(File dir) throws IOException {
    if (!dir.mkdirs() && !dir.isDirectory()) {
      throw new IOException("cannot mkdir: " + dir);
    }
  }

  /**
   * Copy up to byteLimit bytes from the input stream to the output stream.
   *
   * @param os Destination stream
   * @param is Input stream
   * @param byteLimit Maximum number of bytes to copy
   * @param buffer IO buffer to use
   * @return Number of bytes actually copied
   */
  static int copyBytes(DataOutput os, InputStream is, int byteLimit, byte[] buffer)
      throws IOException {
    // Yes, this method is exactly the same as the above, just with a different type for `os'.
    int bytesCopied = 0;
    int nrRead;
    while (bytesCopied < byteLimit
        && (nrRead = is.read(buffer, 0, Math.min(buffer.length, byteLimit - bytesCopied))) != -1) {
      os.write(buffer, 0, nrRead);
      bytesCopied += nrRead;
    }
    return bytesCopied;
  }

  public static void fsyncRecursive(File fileName) throws IOException {
    if (fileName.isDirectory()) {
      File[] files = fileName.listFiles();
      if (files == null) {
        throw new IOException("cannot list directory " + fileName);
      }
      for (int i = 0; i < files.length; ++i) {
        fsyncRecursive(files[i]);
      }
    } else if (fileName.getPath().endsWith("_lock")) {
      /* Do not sync! Any close(2) of a locked file counts as releasing the file for the whole
       * process! */
    } else {
      try (RandomAccessFile file = new RandomAccessFile(fileName, "r")) {
        file.getFD().sync();
      }
    }
  }

  private static long getParcelPadSize(long len) {
    return len + (4 - (len % 4)) % 4;
  }

  public static long getApkDepBlockLength(File apkFile) throws IOException {
    apkFile = apkFile.getCanonicalFile();
    // Parcel encodes strings starting with the length (4 bytes), then
    // 2 bytes for every character, and a null terminating character at the end
    long apkFileLen = getParcelPadSize(2 * (apkFile.getPath().length() + 1));
    return apkFileLen + APK_DEP_BLOCK_METADATA_LENGTH;
  }

  /**
   * N.B. If this method is changed, the above method {@link #getApkDepBlockLength} must also be
   * updated to reflect the expected size of the dep block
   */
  public static byte[] makeApkDepBlock(File apkFile, final Context context) throws IOException {
    apkFile = apkFile.getCanonicalFile();
    Parcel parcel = Parcel.obtain();
    try {
      parcel.writeByte(APK_SIGNATURE_VERSION);
      parcel.writeString(apkFile.getPath());
      parcel.writeLong(apkFile.lastModified());
      parcel.writeInt(getAppVersionCode(context));
      return parcel.marshall();
    } finally {
      parcel.recycle();
    }
  }

  public static int getAppVersionCode(final Context context) {
    final PackageManager pm = context.getPackageManager();
    if (pm != null) {
      try {
        PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
        return pi.versionCode;
      } catch (PackageManager.NameNotFoundException e) {
        // That should not happen
      } catch (RuntimeException e) {
        // To catch RuntimeException("Package manager has died") that can occur
        // on some version of Android, when the remote PackageManager is
        // unavailable. I suspect this sometimes occurs when the App is being reinstalled.
      }
    }
    return 0;
  }

  @SuppressLint("CatchGeneralException")
  public static boolean is64Bit() {
    boolean is64bit = false;
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      is64bit = MarshmallowSysdeps.is64Bit();
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      try {
        is64bit = LollipopSysdeps.is64Bit();
      } catch (Exception e) {
        Log.e(TAG, String.format("Could not read /proc/self/exe. Err msg: %s", e.getMessage()));
      }
    }
    return is64bit;
  }

  public static boolean isSupportedDirectLoad(Context context, int appType) throws IOException {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      // Android starts to support directly loading from API 23.
      // https://android.googlesource.com/platform/bionic/+/master/android-changes-for-ndk-developers.md#opening-shared-libraries-directly-from-an-apk
      return MarshmallowSysdeps.isSupportedDirectLoad(context, appType);
    }
    return false;
  }

  public static boolean isDisabledExtractNativeLibs(Context context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      return MarshmallowSysdeps.isDisabledExtractNativeLibs(context);
    }
    return false;
  }

  public static @Nullable FileLocker getOrCreateLockOnDir(
      File soDirectory, File lockFileName, boolean blocking) throws IOException {
    boolean notWritable = false;
    try {
      return getFileLocker(lockFileName, blocking);
    } catch (FileNotFoundException e) {
      notWritable = true;
      if (!soDirectory.setWritable(true)) {
        throw e;
      }
      return getFileLocker(lockFileName, blocking);
    } finally {
      if (notWritable && !soDirectory.setWritable(false)) {
        Log.w(TAG, "error removing " + soDirectory.getCanonicalPath() + " write permission");
      }
    }
  }

  private static @Nullable FileLocker getFileLocker(File lockFileName, boolean blocking)
      throws IOException {
    if (blocking) {
      return FileLocker.lock(lockFileName);
    } else {
      return FileLocker.tryLock(lockFileName);
    }
  }

  /** Gets the base name, without extension, of given file name. */
  public static String getBaseName(String fileName) {
    final int index = fileName.lastIndexOf('.');
    if (index > 0) {
      return fileName.substring(0, index);
    }
    return fileName;
  }

  @DoNotOptimize
  @TargetApi(14)
  public static class Api14Utils {
    public static String getClassLoaderLdLoadLibrary() {
      final ClassLoader classLoader = SoLoader.class.getClassLoader();

      if (classLoader != null && !(classLoader instanceof BaseDexClassLoader)) {
        throw new IllegalStateException(
            "ClassLoader "
                + classLoader.getClass().getName()
                + " should be of type BaseDexClassLoader");
      }
      try {
        final BaseDexClassLoader baseDexClassLoader = (BaseDexClassLoader) classLoader;
        final Method getLdLibraryPathMethod =
            BaseDexClassLoader.class.getMethod("getLdLibraryPath");

        return (String) getLdLibraryPathMethod.invoke(baseDexClassLoader);
      } catch (Exception e) {
        throw new RuntimeException("Cannot call getLdLibraryPath", e);
      }
    }
  }
}
