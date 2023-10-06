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
import com.facebook.soloader.UnpackingSoSource.Dso;
import com.facebook.soloader.UnpackingSoSource.InputDso;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** {@link SoSource} that retrieves libraries from an exopackage repository. */
public final class ExoSoSource extends UnpackingSoSource {

  public ExoSoSource(Context context, String name) {
    super(context, name);
  }

  @Override
  public String getName() {
    return "ExoSoSource";
  }

  @Override
  protected MessageDigest getHashingAlgorithm() throws NoSuchAlgorithmException {
    return MessageDigest.getInstance("SHA-1");
  }

  @Override
  protected Unpacker makeUnpacker(boolean forceUnpacking) throws IOException {
    return new ExoUnpacker(this);
  }

  private final class ExoUnpacker extends Unpacker {

    private final FileDso[] mDsos;

    ExoUnpacker(final UnpackingSoSource soSource) throws IOException {
      Context context = mContext;
      File exoDir =
          new File("/data/local/tmp/exopackage/" + context.getPackageName() + "/native-libs/");

      ArrayList<FileDso> providedLibraries = new ArrayList<>();

      Set<String> librariesAbiSet = new LinkedHashSet<>();

      for (String abi : SysUtil.getSupportedAbis()) {
        File abiDir = new File(exoDir, abi);
        if (!abiDir.isDirectory()) {
          continue;
        }

        librariesAbiSet.add(abi);

        File metadataFileName = new File(abiDir, "metadata.txt");
        if (!metadataFileName.isFile()) {
          continue;
        }

        try (FileReader fr = new FileReader(metadataFileName);
            BufferedReader br = new BufferedReader(fr)) {
          String line;
          while ((line = br.readLine()) != null) {
            if (line.length() == 0) {
              continue;
            }

            int sep = line.indexOf(' ');
            if (sep == -1) {
              throw new RuntimeException("illegal line in exopackage metadata: [" + line + "]");
            }

            String soName = line.substring(0, sep) + ".so";
            int nrAlreadyProvided = providedLibraries.size();
            boolean found = false;
            for (int i = 0; i < nrAlreadyProvided; ++i) {
              if (providedLibraries.get(i).name.equals(soName)) {
                found = true;
                break;
              }
            }

            if (found) {
              continue;
            }

            String backingFileBaseName = line.substring(sep + 1);
            String hash =
                backingFileBaseName.substring(
                    backingFileBaseName.indexOf('-'), backingFileBaseName.indexOf(".so"));
            providedLibraries.add(new FileDso(soName, hash, new File(abiDir, backingFileBaseName)));
          }
        }
      }

      soSource.setSoSourceAbis(librariesAbiSet.toArray(new String[librariesAbiSet.size()]));
      mDsos = providedLibraries.toArray(new FileDso[providedLibraries.size()]);
    }

    @Override
    public Dso[] getDsos() throws IOException {
      return mDsos;
    }

    @Override
    public void unpack(File soDirectory) throws IOException {
      byte[] ioBuffer = new byte[32 * 1024];
      deleteUnmentionedFiles(mDsos);
      for (FileDso fileDso : mDsos) {
        FileInputStream is = new FileInputStream(fileDso.backingFile);
        try (InputDso inputDso = new InputDso(fileDso, is)) {
          is = null; // Transfer ownership to inputDso
          extractDso(inputDso, ioBuffer, soDirectory);
        } finally {
          if (is != null) {
            is.close();
          }
        }
      }
    }
  }

  private static final class FileDso extends Dso {
    final File backingFile;

    FileDso(String name, String hash, File backingFile) {
      super(name, hash);
      this.backingFile = backingFile;
    }
  }
}
