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

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import com.facebook.soloader.nativeloader.NativeLoader;
import dalvik.system.BaseDexClassLoader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Native code loader.
 *
 * <p>To load a native library, call the static method {@link #loadLibrary} from the static
 * initializer of the Java class declaring the native methods. The argument should be the library's
 * short name.
 *
 * <p>For example, if the native code is in libmy_jni_methods.so:
 *
 * <pre>{@code
 * class MyClass {
 *   static {
 *     SoLoader.loadLibrary("my_jni_methods");
 *   }
 * }
 * }</pre>
 *
 * <p>Before any library can be loaded SoLoader needs to be initialized. The application using
 * SoLoader should do that by calling SoLoader.init early on app initialization path. The call must
 * happen before any class using SoLoader in its static initializer is loaded.
 */
@ThreadSafe
public class SoLoader {

  /* package */ static final String TAG = "SoLoader";
  /* package */ static final boolean DEBUG = false;
  /* package */ static final boolean SYSTRACE_LIBRARY_LOADING;
  /* package */ @Nullable static SoFileLoader sSoFileLoader;

  /**
   * locking controlling the list of SoSources. We want to allow long running iterations over the
   * list to happen concurrently, but also ensure that nothing modifies the list while others are
   * reading it.
   */
  private static final ReentrantReadWriteLock sSoSourcesLock = new ReentrantReadWriteLock();

  /**
   * Ordered list of sources to consult when trying to load a shared library or one of its
   * dependencies. {@code null} indicates that SoLoader is uninitialized.
   */
  @GuardedBy("sSoSourcesLock")
  @Nullable
  private static SoSource[] sSoSources = null;

  @GuardedBy("sSoSourcesLock")
  private static volatile int sSoSourcesVersion = 0;

  /** A backup SoSources to try if a lib file is corrupted */
  @GuardedBy("sSoSourcesLock")
  @Nullable
  private static UnpackingSoSource[] sBackupSoSources;

  /**
   * A SoSource for the Context.ApplicationInfo.nativeLibsDir that can be updated if the application
   * moves this directory
   */
  @GuardedBy("sSoSourcesLock")
  @Nullable
  private static ApplicationSoSource sApplicationSoSource;

  /** Records the sonames (e.g., "libdistract.so") of shared libraries we've loaded. */
  @GuardedBy("SoLoader.class")
  private static final HashSet<String> sLoadedLibraries = new HashSet<>();

  /**
   * Libraries that are in the process of being loaded, and lock objects to synchronize on and wait
   * for the loading to end.
   *
   * <p>To prevent potential deadlock, always acquire sSoSourcesLock before these locks!
   */
  @GuardedBy("SoLoader.class")
  private static final Map<String, Object> sLoadingLibraries = new HashMap<>();

  /**
   * Libraries that have loaded and completed their JniOnLoad. Reads and writes are guarded by the
   * corresponding lock in sLoadingLibraries. However, as we only add to this set (and never
   * remove), it is safe to perform un-guarded reads as an optional optimization prior to locking.
   */
  private static final Set<String> sLoadedAndMergedLibraries =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  /** Wrapper for System.loadLibrary. */
  @Nullable private static SystemLoadLibraryWrapper sSystemLoadLibraryWrapper = null;

  /**
   * Name of the directory we use for extracted DSOs from built-in SO sources (main APK, exopackage)
   */
  private static final String SO_STORE_NAME_MAIN = "lib-main";

  /** Name of the directory we use for extracted DSOs from split APKs */
  private static final String SO_STORE_NAME_SPLIT = "lib-";

  /** Enable the exopackage SoSource. */
  public static final int SOLOADER_ENABLE_EXOPACKAGE = (1 << 0);

  /**
   * Allow deferring some initialization work to asynchronous background threads. Shared libraries
   * are nevertheless ready to load as soon as init returns.
   */
  public static final int SOLOADER_ALLOW_ASYNC_INIT = (1 << 1);

  public static final int SOLOADER_LOOK_IN_ZIP = (1 << 2);

  /**
   * In some contexts, using a backup so source in case of so corruption is not feasible e.g. lack
   * of write permissions to the library path.
   */
  public static final int SOLOADER_DISABLE_BACKUP_SOSOURCE = (1 << 3);

  /**
   * Skip calling JNI_OnLoad if the library is merged. This is necessary for libraries that don't
   * define JNI_OnLoad and are only loaded for their side effects (like static constructors
   * registering callbacks). DO NOT use this to allow implicit JNI registration (by naming your
   * methods Java_com_facebook_whatever) because that is buggy on Android.
   */
  public static final int SOLOADER_SKIP_MERGED_JNI_ONLOAD = (1 << 4);

  /** System Apps ignore PREFER_ANDROID_LIBS_DIRECTORY. Don't do that for this app. */
  public static final int SOLOADER_DONT_TREAT_AS_SYSTEMAPP = (1 << 5);

  @GuardedBy("sSoSourcesLock")
  private static int sFlags;

  private static boolean isSystemApp;

  static {
    boolean shouldSystrace = false;
    try {
      shouldSystrace = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
      // In some test contexts, the Build class and/or some of its dependencies do not exist.
    }

    SYSTRACE_LIBRARY_LOADING = shouldSystrace;
  }

  public static void init(Context context, int flags) throws IOException {
    init(context, flags, null);
  }

  /**
   * Initializes native code loading for this app; this class's other static facilities cannot be
   * used until this {@link #init} is called. This method is idempotent: calls after the first are
   * ignored.
   *
   * @param context application context.
   * @param flags Zero or more of the SOLOADER_* flags
   * @param soFileLoader
   */
  public static void init(Context context, int flags, @Nullable SoFileLoader soFileLoader)
      throws IOException {
    StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
    try {
      isSystemApp = checkIfSystemApp(context, flags);
      initSoLoader(soFileLoader);
      initSoSources(context, flags, soFileLoader);
      if (!NativeLoader.isInitialized()) {
        NativeLoader.init(new NativeLoaderToSoLoaderDelegate());
      }
    } finally {
      StrictMode.setThreadPolicy(oldPolicy);
    }
  }

  /** Backward compatibility */
  public static void init(Context context, boolean nativeExopackage) {
    try {
      init(context, nativeExopackage ? SOLOADER_ENABLE_EXOPACKAGE : 0);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static void initSoSources(Context context, int flags, @Nullable SoFileLoader soFileLoader)
      throws IOException {
    sSoSourcesLock.writeLock().lock();
    try {
      if (sSoSources == null) {
        Log.d(TAG, "init start");
        sFlags = flags;

        ArrayList<SoSource> soSources = new ArrayList<>();

        //
        // Add SoSource objects for each of the system library directories.
        //

        String LD_LIBRARY_PATH = System.getenv("LD_LIBRARY_PATH");
        if (LD_LIBRARY_PATH == null) {
          LD_LIBRARY_PATH =
              SysUtil.is64Bit() ? "/vendor/lib64:/system/lib64" : "/vendor/lib:/system/lib";
        }

        for (String systemLibraryDirectory : LD_LIBRARY_PATH.split(":")) {
          // Don't pass DirectorySoSource.RESOLVE_DEPENDENCIES for directories we find on
          // LD_LIBRARY_PATH: Bionic's dynamic linker is capable of correctly resolving dependencies
          // these libraries have on each other, so doing that ourselves would be a waste.
          Log.d(TAG, "adding system library source: " + systemLibraryDirectory);
          File systemSoDirectory = new File(systemLibraryDirectory);
          soSources.add(
              new DirectorySoSource(systemSoDirectory, DirectorySoSource.ON_LD_LIBRARY_PATH));
        }

        //
        // We can only proceed forward if we have a Context. The prominent case
        // where we don't have a Context is barebones dalvikvm instantiations. In
        // that case, the caller is responsible for providing a correct LD_LIBRARY_PATH.
        //

        if (context != null) {
          //
          // Prepend our own SoSource for our own DSOs.
          //

          if ((flags & SOLOADER_ENABLE_EXOPACKAGE) != 0) {
            sBackupSoSources = null;
            Log.d(TAG, "adding exo package source: " + SO_STORE_NAME_MAIN);
            soSources.add(0, new ExoSoSource(context, SO_STORE_NAME_MAIN));
          } else {
            int apkSoSourceFlags;
            if (isSystemApp) {
              apkSoSourceFlags = 0;
            } else {
              apkSoSourceFlags = ApkSoSource.PREFER_ANDROID_LIBS_DIRECTORY;
              int ourSoSourceFlags = 0;

              // On old versions of Android, Bionic doesn't add our library directory to its
              // internal search path, and the system doesn't resolve dependencies between
              // modules we ship. On these systems, we resolve dependencies ourselves. On other
              // systems, Bionic's built-in resolver suffices.

              if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                ourSoSourceFlags |= DirectorySoSource.RESOLVE_DEPENDENCIES;
              }

              sApplicationSoSource = new ApplicationSoSource(context, ourSoSourceFlags);
              Log.d(TAG, "adding application source: " + sApplicationSoSource.toString());
              soSources.add(0, sApplicationSoSource);
            }

            if ((sFlags & SOLOADER_DISABLE_BACKUP_SOSOURCE) != 0) {
              sBackupSoSources = null;
            } else {

              final File mainApkDir = new File(context.getApplicationInfo().sourceDir);
              ArrayList<UnpackingSoSource> backupSources = new ArrayList<>();
              ApkSoSource mainApkSource =
                  new ApkSoSource(context, mainApkDir, SO_STORE_NAME_MAIN, apkSoSourceFlags);
              backupSources.add(mainApkSource);
              Log.d(TAG, "adding backup source from : " + mainApkSource.toString());

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                  && context.getApplicationInfo().splitSourceDirs != null) {
                Log.d(TAG, "adding backup sources from split apks");
                int splitIndex = 0;
                for (String splitApkDir : context.getApplicationInfo().splitSourceDirs) {
                  ApkSoSource splitApkSource =
                      new ApkSoSource(
                          context,
                          new File(splitApkDir),
                          SO_STORE_NAME_SPLIT + (splitIndex++),
                          apkSoSourceFlags);
                  Log.d(TAG, "adding backup source: " + splitApkSource.toString());
                  backupSources.add(splitApkSource);
                }
              }

              sBackupSoSources = backupSources.toArray(new UnpackingSoSource[backupSources.size()]);
              soSources.addAll(0, backupSources);
            }
          }
        }

        SoSource[] finalSoSources = soSources.toArray(new SoSource[soSources.size()]);
        int prepareFlags = makePrepareFlags();
        for (int i = finalSoSources.length; i-- > 0; ) {
          Log.d(TAG, "Preparing SO source: " + finalSoSources[i]);
          finalSoSources[i].prepare(prepareFlags);
        }
        sSoSources = finalSoSources;
        sSoSourcesVersion++;
        Log.d(TAG, "init finish: " + sSoSources.length + " SO sources prepared");
      }
    } finally {
      Log.d(TAG, "init exiting");
      sSoSourcesLock.writeLock().unlock();
    }
  }

  private static int makePrepareFlags() {
    int prepareFlags = 0;
    // ensure the write lock is being held to protect sFlags
    // this is used when preparing new SoSources in the list.
    sSoSourcesLock.writeLock().lock();
    try {
      if ((sFlags & SOLOADER_ALLOW_ASYNC_INIT) != 0) {
        prepareFlags |= SoSource.PREPARE_FLAG_ALLOW_ASYNC_INIT;
      }
      return prepareFlags;
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  private static synchronized void initSoLoader(@Nullable SoFileLoader soFileLoader) {
    if (soFileLoader != null) {
      sSoFileLoader = soFileLoader;
      return;
    }

    final Runtime runtime = Runtime.getRuntime();
    final Method nativeLoadRuntimeMethod = getNativeLoadRuntimeMethod();

    final boolean hasNativeLoadMethod = nativeLoadRuntimeMethod != null;

    final String localLdLibraryPath =
        hasNativeLoadMethod ? Api14Utils.getClassLoaderLdLoadLibrary() : null;
    final String localLdLibraryPathNoZips = makeNonZipPath(localLdLibraryPath);

    sSoFileLoader =
        new SoFileLoader() {
          @Override
          public void load(final String pathToSoFile, final int loadFlags) {
            String error = null;
            if (hasNativeLoadMethod) {
              final boolean inZip = (loadFlags & SOLOADER_LOOK_IN_ZIP) == SOLOADER_LOOK_IN_ZIP;
              final String path = inZip ? localLdLibraryPath : localLdLibraryPathNoZips;
              try {
                synchronized (runtime) {
                  error =
                      (String)
                          nativeLoadRuntimeMethod.invoke(
                              runtime, pathToSoFile, SoLoader.class.getClassLoader(), path);
                  if (error != null) {
                    throw new UnsatisfiedLinkError(error);
                  }
                }
              } catch (IllegalAccessException
                  | IllegalArgumentException
                  | InvocationTargetException e) {
                error = "Error: Cannot load " + pathToSoFile;
                throw new RuntimeException(error, e);
              } finally {
                if (error != null) {
                  Log.e(
                      TAG,
                      "Error when loading lib: "
                          + error
                          + " lib hash: "
                          + getLibHash(pathToSoFile)
                          + " search path is "
                          + path);
                }
              }
            } else {
              System.load(pathToSoFile);
            }
          }

          /** * Logs MD5 of lib that failed loading */
          private String getLibHash(String libPath) {
            String digestStr;
            try {
              File libFile = new File(libPath);
              MessageDigest digest = MessageDigest.getInstance("MD5");
              try (InputStream libInStream = new FileInputStream(libFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = libInStream.read(buffer)) > 0) {
                  digest.update(buffer, 0, bytesRead);
                }
                digestStr = String.format("%32x", new BigInteger(1, digest.digest()));
              }
            } catch (IOException e) {
              digestStr = e.toString();
            } catch (SecurityException e) {
              digestStr = e.toString();
            } catch (NoSuchAlgorithmException e) {
              digestStr = e.toString();
            }
            return digestStr;
          }
        };
  }

  @Nullable
  private static Method getNativeLoadRuntimeMethod() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Build.VERSION.SDK_INT > 27) {
      return null;
    }

    try {
      final Method method;
      method =
          Runtime.class.getDeclaredMethod(
              "nativeLoad", String.class, ClassLoader.class, String.class);

      method.setAccessible(true);
      return method;
    } catch (final NoSuchMethodException | SecurityException e) {
      Log.w(TAG, "Cannot get nativeLoad method", e);
      return null;
    }
  }

  private static boolean checkIfSystemApp(Context context, int flags) {
    if ((flags & SOLOADER_DONT_TREAT_AS_SYSTEMAPP) != 0) {
      return false;
    }

    return (context != null)
        && (context.getApplicationInfo().flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP))
            != 0;
  }

  /** Turn shared-library loading into a no-op. Useful in special circumstances. */
  public static void setInTestMode() {
    TestOnlyUtils.setSoSources(new SoSource[] {new NoopSoSource()});
  }

  /** Make shared-library loading delegate to the system. Useful for tests. */
  public static void deinitForTest() {
    TestOnlyUtils.setSoSources(null);
  }

  @NotThreadSafe
  static class TestOnlyUtils {
    /* Set so sources. Useful for tests. */
    /* package */ static void setSoSources(SoSource[] sources) {
      sSoSourcesLock.writeLock().lock();
      try {
        sSoSources = sources;
        sSoSourcesVersion++;
      } finally {
        sSoSourcesLock.writeLock().unlock();
      }
    }

    /**
     * Set so file loader. <br>
     * N.B. <b>ONLY FOR TESTS</b>. It has read/write race with {@code
     * SoLoader.sSoFileLoader.load(String, int)} in {@link DirectorySoSource#loadLibraryFrom}
     *
     * @param loader {@link SoFileLoader}
     */
    /* package */ static void setSoFileLoader(SoFileLoader loader) {
      sSoFileLoader = loader;
    }

    /** Reset internal status. Only for tests. */
    /* package */ static void resetStatus() {
      synchronized (SoLoader.class) {
        sLoadedLibraries.clear();
        sLoadingLibraries.clear();
        sSoFileLoader = null;
      }
      setSoSources(null);
    }
  }

  /**
   * Provide a wrapper object for calling {@link System#loadLibrary}. This is useful for controlling
   * which ClassLoader libraries are loaded into.
   */
  public static void setSystemLoadLibraryWrapper(SystemLoadLibraryWrapper wrapper) {
    sSystemLoadLibraryWrapper = wrapper;
  }

  public static final class WrongAbiError extends UnsatisfiedLinkError {
    WrongAbiError(Throwable cause, String machine) {
      super(
          "APK was built for a different platform. Supported ABIs: "
              + Arrays.toString(SysUtil.getSupportedAbis())
              + " error: "
              + machine);
      initCause(cause);
    }
  }

  /**
   * Gets the full path of a library.
   *
   * @param libName the library file name, including the prefix and extension.
   * @return the full path of the library, or null if it is not found in none of the SoSources.
   * @throws IOException if there is an error calculating the canonical path of libName
   */
  public static @Nullable String getLibraryPath(String libName) throws IOException {
    String libPath = null;

    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources != null) {
        for (int i = 0; libPath == null && i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          libPath = currentSource.getLibraryPath(libName);
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    return libPath;
  }

  /**
   * Gets the dependencies of a library.
   *
   * @param libName the library file name, including the prefix and extension.
   * @return An array naming the dependencies of the library, or null if it is not found in any
   *     SoSources
   * @throws IOException if there is an error reading libName
   */
  public static @Nullable String[] getLibraryDependencies(String libName) throws IOException {
    String[] deps = null;

    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources != null) {
        for (int i = 0; deps == null && i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          deps = currentSource.getLibraryDependencies(libName);
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    return deps;
  }

  public static boolean loadLibrary(String shortName) {
    return loadLibrary(shortName, 0);
  }

  /**
   * Load a shared library, initializing any JNI binding it contains.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @param loadFlags Control flags for the loading behavior. See available flags under {@link
   *     SoSource} (LOAD_FLAG_XXX).
   * @return Whether the library was loaded as a result of this call (true), or was already loaded
   *     through a previous call to SoLoader (false).
   */
  public static boolean loadLibrary(String shortName, int loadFlags) throws UnsatisfiedLinkError {
    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources == null) {
        // This should never happen during normal operation,
        // but if we're running in a non-Android environment,
        // fall back to System.loadLibrary.
        if ("http://www.android.com/".equals(System.getProperty("java.vendor.url"))) {
          // This will throw.
          assertInitialized();
        } else {
          // Not on an Android system.  Ask the JVM to load for us.
          synchronized (SoLoader.class) {
            boolean needsLoad = !sLoadedLibraries.contains(shortName);
            if (needsLoad) {
              if (sSystemLoadLibraryWrapper != null) {
                sSystemLoadLibraryWrapper.loadLibrary(shortName);
              } else {
                System.loadLibrary(shortName);
              }
            }
            return needsLoad;
          }
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    // This is to account for the fact that we want to load .so files from the apk itself when it is
    // a system app.
    if (isSystemApp && sSystemLoadLibraryWrapper != null) {
      sSystemLoadLibraryWrapper.loadLibrary(shortName);
      return true;
    }

    String mergedLibName = MergedSoMapping.mapLibName(shortName);

    String soName = mergedLibName != null ? mergedLibName : shortName;

    return loadLibraryBySoName(
        System.mapLibraryName(soName), shortName, mergedLibName, loadFlags, null);
  }

  /* package */ static void loadLibraryBySoName(
      String soName, int loadFlags, StrictMode.ThreadPolicy oldPolicy) {
    loadLibraryBySoNameImpl(soName, null, null, loadFlags, oldPolicy);
  }

  private static boolean loadLibraryBySoName(
      String soName,
      @Nullable String shortName,
      @Nullable String mergedLibName,
      int loadFlags,
      @Nullable StrictMode.ThreadPolicy oldPolicy) {
    boolean ret = false;
    boolean retry;
    do {
      retry = false;
      try {
        ret = loadLibraryBySoNameImpl(soName, shortName, mergedLibName, loadFlags, oldPolicy);
      } catch (UnsatisfiedLinkError e) {
        final int currentVersion = sSoSourcesVersion;
        sSoSourcesLock.writeLock().lock();
        try {
          if (sApplicationSoSource != null && sApplicationSoSource.checkAndMaybeUpdate()) {
            Log.w(
                TAG,
                "sApplicationSoSource updated during load: " + soName + ", attempting load again.");
            sSoSourcesVersion++;
            retry = true;
          }
        } catch (IOException ex) {
          throw new RuntimeException(ex);
        } finally {
          sSoSourcesLock.writeLock().unlock();
        }

        if (sSoSourcesVersion == currentVersion) {
          // nothing changed in soSource, Propagate original error
          throw e;
        }
      }
    } while (retry);
    return ret;
  }

  private static boolean loadLibraryBySoNameImpl(
      String soName,
      @Nullable String shortName,
      @Nullable String mergedLibName,
      int loadFlags,
      @Nullable StrictMode.ThreadPolicy oldPolicy) {
    // As an optimization, avoid taking locks if the library has already loaded. Without locks this
    // does not provide 100% coverage (e.g. another thread may currently be loading or initializing
    // the library), so we'll need to check again with locks held, below.
    if (!TextUtils.isEmpty(shortName) && sLoadedAndMergedLibraries.contains(shortName)) {
      return false;
    }

    // LoadingLibLock is used to ensure that doLoadLibraryBySoName and its corresponding JniOnload
    // are only executed once per library. It also guarantees that concurrent calls to loadLibrary
    // for the same library do not return until both its load and JniOnLoad have completed.
    Object loadingLibLock;
    boolean loaded = false;
    synchronized (SoLoader.class) {
      if (sLoadedLibraries.contains(soName)) {
        if (mergedLibName == null) {
          // Not a merged lib, no need to init
          return false;
        }
        loaded = true;
      }
      if (sLoadingLibraries.containsKey(soName)) {
        loadingLibLock = sLoadingLibraries.get(soName);
      } else {
        loadingLibLock = new Object();
        sLoadingLibraries.put(soName, loadingLibLock);
      }
    }

    // Note that both doLoadLibraryBySoName and invokeJniOnload (below) may re-enter loadLibrary
    // (or loadLibraryBySoNameImpl), recursively acquiring additional library and soSource locks.
    //
    // To avoid bi-directional lock usage (threadA takes loadingLibLock then sSoSourcesLock, threadB
    // takes sSoSourcesLock then loadingLibLock) and potential deadlock as this method recursively
    // calls loadLibrary, we must acquire sSoSourcesLock first.
    sSoSourcesLock.readLock().lock();
    try {
      synchronized (loadingLibLock) {
        if (!loaded) {
          synchronized (SoLoader.class) {
            if (sLoadedLibraries.contains(soName)) {
              // Library was successfully loaded by other thread while we waited
              if (mergedLibName == null) {
                // Not a merged lib, no need to init
                return false;
              }
              loaded = true;
            }
            // Else, load was not successful on other thread. We will try in this one.
          }

          if (!loaded) {
            try {
              Log.d(TAG, "About to load: " + soName);
              doLoadLibraryBySoName(soName, loadFlags, oldPolicy);
            } catch (UnsatisfiedLinkError ex) {
              String message = ex.getMessage();
              if (message != null && message.contains("unexpected e_machine:")) {
                String machine_msg =
                    message.substring(message.lastIndexOf("unexpected e_machine:"));
                throw new WrongAbiError(ex, machine_msg);
              }
              throw ex;
            }
            synchronized (SoLoader.class) {
              Log.d(TAG, "Loaded: " + soName);
              sLoadedLibraries.add(soName);
            }
          }
        }

        if ((loadFlags & SOLOADER_SKIP_MERGED_JNI_ONLOAD) == 0) {
          // MergedSoMapping#invokeJniOnload does not necessarily handle concurrent nor redundant
          // invocation. sLoadedAndMergedLibraries is used in conjunction with loadingLibLock to
          // ensure one invocation per library.
          boolean isAlreadyMerged =
              !TextUtils.isEmpty(shortName) && sLoadedAndMergedLibraries.contains(shortName);
          if (mergedLibName != null && !isAlreadyMerged) {
            if (SYSTRACE_LIBRARY_LOADING) {
              Api18TraceUtils.beginTraceSection("MergedSoMapping.invokeJniOnload[", shortName, "]");
            }
            try {
              Log.d(TAG, "About to merge: " + shortName + " / " + soName);
              MergedSoMapping.invokeJniOnload(shortName);
              sLoadedAndMergedLibraries.add(shortName);
            } catch (UnsatisfiedLinkError e) {
              // If you are seeing this exception, first make sure your library sets
              // allow_jni_merging=True.  Trying to merge a library without that
              // will trigger this error.  If that's already in place, you're probably
              // not defining JNI_OnLoad.  Calling SoLoader.loadLibrary on a library
              // that doesn't define JNI_OnLoad is a no-op when that library is not merged.
              // Once you enable merging, it throws an UnsatisfiedLinkError.
              // There are three main reasons a library might not define JNI_OnLoad,
              // and the solution depends on which case you have.
              // - You might be using implicit registration (native methods defined like
              //   `Java_com_facebook_Foo_bar(JNIEnv* env)`).  This is not safe on Android
              //   https://fb.workplace.com/groups/442333009148653/permalink/651212928260659/
              //   and is not compatible with FBJNI.  Stop doing it.  Use FBJNI registerNatives.
              // - You might have a C++-only library with no JNI bindings and no static
              //   initializers with side-effects.  You can just delete the loadLibrary call.
              // - You might have a C++-only library that needs to be loaded explicitly because
              //   it has static initializers whose side-effects are needed.  In that case,
              //   pass the SOLOADER_SKIP_MERGED_JNI_ONLOAD flag to loadLibrary.
              throw new RuntimeException(
                  "Failed to call JNI_OnLoad from '"
                      + shortName
                      + "', which has been merged into '"
                      + soName
                      + "'.  See comment for details.",
                  e);
            } finally {
              if (SYSTRACE_LIBRARY_LOADING) {
                Api18TraceUtils.endSection();
              }
            }
          }
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
    return !loaded;
  }

  /**
   * Unpack library and its dependencies, returning the location of the unpacked library file. All
   * non-system dependencies of the given library will either be on LD_LIBRARY_PATH or will be in
   * the same directory as the returned File.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @return Unpacked DSO location
   */
  public static File unpackLibraryAndDependencies(String shortName) throws UnsatisfiedLinkError {
    assertInitialized();
    try {
      return unpackLibraryBySoName(System.mapLibraryName(shortName));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static void doLoadLibraryBySoName(
      String soName, int loadFlags, @Nullable StrictMode.ThreadPolicy oldPolicy)
      throws UnsatisfiedLinkError {

    int result = SoSource.LOAD_RESULT_NOT_FOUND;
    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources == null) {
        Log.e(TAG, "Could not load: " + soName + " because no SO source exists");
        throw new UnsatisfiedLinkError("couldn't find DSO to load: " + soName);
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    // This way, we set the thread policy only one per loadLibrary no matter how many
    // dependencies we load.  Each call to StrictMode.allowThreadDiskWrites allocates.
    boolean restoreOldPolicy = false;
    if (oldPolicy == null) {
      oldPolicy = StrictMode.allowThreadDiskReads();
      restoreOldPolicy = true;
    }

    if (SYSTRACE_LIBRARY_LOADING) {
      Api18TraceUtils.beginTraceSection("SoLoader.loadLibrary[", soName, "]");
    }

    Throwable error = null;
    try {
      sSoSourcesLock.readLock().lock();
      try {
        for (int i = 0; result == SoSource.LOAD_RESULT_NOT_FOUND && i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          result = currentSource.loadLibrary(soName, loadFlags, oldPolicy);
          if (result == SoSource.LOAD_RESULT_CORRUPTED_LIB_FILE && sBackupSoSources != null) {
            // Let's try from the backup source
            Log.d(TAG, "Trying backup SoSource for " + soName);
            for (UnpackingSoSource backupSoSource : sBackupSoSources) {
              backupSoSource.prepare(soName);
              int resultFromBackup = backupSoSource.loadLibrary(soName, loadFlags, oldPolicy);
              if (resultFromBackup == SoSource.LOAD_RESULT_LOADED) {
                result = resultFromBackup;
                break;
              }
            }
            break;
          }
        }
      } finally {
        sSoSourcesLock.readLock().unlock();
      }
    } catch (Throwable t) {
      error = t;
    } finally {
      if (SYSTRACE_LIBRARY_LOADING) {
        Api18TraceUtils.endSection();
      }

      if (restoreOldPolicy) {
        StrictMode.setThreadPolicy(oldPolicy);
      }
      if (result == SoSource.LOAD_RESULT_NOT_FOUND
          || result == SoSource.LOAD_RESULT_CORRUPTED_LIB_FILE) {
        StringBuilder sb = new StringBuilder().append("couldn't find DSO to load: ").append(soName);
        if (error != null) {
          String cause = error.getMessage();
          if (cause == null) {
            cause = error.toString();
          }
          sb.append(" caused by: ").append(cause);
          error.printStackTrace();
        } else {
          // load failure wasn't caused by dependent libraries.
          // Print the sources and current native library directory
          sSoSourcesLock.readLock().lock();
          for (int i = 0; i < sSoSources.length; ++i) {
            sb.append("\n\tSoSource ").append(i).append(": ").append(sSoSources[i].toString());
          }
          if (sApplicationSoSource != null) {
            Context updatedContext = sApplicationSoSource.getUpdatedContext();
            File updatedNativeLibDir =
                ApplicationSoSource.getNativeLibDirFromContext(updatedContext);
            sb.append("\n\tNative lib dir: ")
                .append(updatedNativeLibDir.getAbsolutePath())
                .append("\n");
          }
          sSoSourcesLock.readLock().unlock();
        }
        sb.append(" result: ").append(result);
        final String message = sb.toString();
        Log.e(TAG, message);
        UnsatisfiedLinkError err = new UnsatisfiedLinkError(message);
        if (error != null) {
          err.initCause(error);
        }
        throw err;
      }
    }
  }

  @Nullable
  public static String makeNonZipPath(final String localLdLibraryPath) {
    if (localLdLibraryPath == null) {
      return null;
    }

    final String[] paths = localLdLibraryPath.split(":");
    final ArrayList<String> pathsWithoutZip = new ArrayList<String>(paths.length);
    for (final String path : paths) {
      if (path.contains("!")) {
        continue;
      }
      pathsWithoutZip.add(path);
    }

    return TextUtils.join(":", pathsWithoutZip);
  }

  /* package */ static File unpackLibraryBySoName(String soName) throws IOException {
    sSoSourcesLock.readLock().lock();
    try {
      for (SoSource soSource : sSoSources) {
        File unpacked = soSource.unpackLibrary(soName);
        if (unpacked != null) {
          return unpacked;
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    throw new FileNotFoundException(soName);
  }

  private static void assertInitialized() {
    if (!isInitialized()) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }
  }

  public static boolean isInitialized() {
    sSoSourcesLock.readLock().lock();
    try {
      return sSoSources != null;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  public static int getSoSourcesVersion() {
    return sSoSourcesVersion;
  }

  /**
   * Add a new source of native libraries. SoLoader consults the new source before any
   * currently-installed source.
   *
   * @param extraSoSource The SoSource to install
   */
  public static void prependSoSource(SoSource extraSoSource) throws IOException {
    sSoSourcesLock.writeLock().lock();
    try {
      Log.d(TAG, "Prepending to SO sources: " + extraSoSource);
      assertInitialized();
      extraSoSource.prepare(makePrepareFlags());
      SoSource[] newSoSources = new SoSource[sSoSources.length + 1];
      newSoSources[0] = extraSoSource;
      System.arraycopy(sSoSources, 0, newSoSources, 1, sSoSources.length);
      sSoSources = newSoSources;
      sSoSourcesVersion++;
      Log.d(TAG, "Prepended to SO sources: " + extraSoSource);
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  /**
   * Retrieve an LD_LIBRARY_PATH value suitable for using the native linker to resolve our shared
   * libraries.
   */
  public static String makeLdLibraryPath() {
    sSoSourcesLock.readLock().lock();
    try {
      assertInitialized();
      Log.d(TAG, "makeLdLibraryPath");
      ArrayList<String> pathElements = new ArrayList<>();
      SoSource[] soSources = sSoSources;
      if (soSources != null) {
        for (SoSource soSource : soSources) {
          soSource.addToLdLibraryPath(pathElements);
        }
      }
      String joinedPaths = TextUtils.join(":", pathElements);
      Log.d(TAG, "makeLdLibraryPath final path: " + joinedPaths);
      return joinedPaths;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  /**
   * This function ensure that every SoSources Abi is supported for at least one abi in
   * SysUtil.getSupportedAbis
   *
   * @return true if all SoSources have their Abis supported
   */
  public static boolean areSoSourcesAbisSupported() {
    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources == null) {
        return false;
      }

      String[] supportedAbis = SysUtil.getSupportedAbis();
      for (SoSource soSource : sSoSources) {
        String[] soSourceAbis = soSource.getSoSourceAbis();
        for (String soSourceAbi : soSourceAbis) {
          boolean soSourceSupported = false;
          for (int k = 0; k < supportedAbis.length && !soSourceSupported; ++k) {
            soSourceSupported = soSourceAbi.equals(supportedAbis[k]);
          }
          if (!soSourceSupported) {
            Log.e(TAG, "abi not supported: " + soSourceAbi);
            return false;
          }
        }
      }

      return true;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  @DoNotOptimize
  @TargetApi(14)
  private static class Api14Utils {
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
