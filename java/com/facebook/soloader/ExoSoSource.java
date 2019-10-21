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

import android.content.Context;
import com.facebook.soloader.UnpackingSoSource.Dso;
import com.facebook.soloader.UnpackingSoSource.DsoManifest;
import com.facebook.soloader.UnpackingSoSource.InputDso;
import com.facebook.soloader.UnpackingSoSource.InputDsoIterator;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

/** {@link SoSource} that retrieves libraries from an exopackage repository. */
public final class ExoSoSource extends UnpackingSoSource {

  public ExoSoSource(Context context, String name) {
    super(context, name);
  }

  @Override
  protected Unpacker makeUnpacker() throws IOException {
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
            providedLibraries.add(
                new FileDso(soName, backingFileBaseName, new File(abiDir, backingFileBaseName)));
          }
        }
      }

      soSource.setSoSourceAbis(librariesAbiSet.toArray(new String[librariesAbiSet.size()]));
      mDsos = providedLibraries.toArray(new FileDso[providedLibraries.size()]);
    }

    @Override
    protected DsoManifest getDsoManifest() throws IOException {
      return new DsoManifest(mDsos);
    }

    @Override
    protected InputDsoIterator openDsoIterator() throws IOException {
      return new FileBackedInputDsoIterator();
    }

    private final class FileBackedInputDsoIterator extends InputDsoIterator {
      private int mCurrentDso;

      @Override
      public boolean hasNext() {
        return mCurrentDso < mDsos.length;
      }

      @Override
      public InputDso next() throws IOException {
        FileDso fileDso = mDsos[mCurrentDso++];
        FileInputStream dsoFile = new FileInputStream(fileDso.backingFile);
        try {
          InputDso ret = new InputDso(fileDso, dsoFile);
          dsoFile = null; // Ownership transferred
          return ret;
        } finally {
          if (dsoFile != null) {
            dsoFile.close();
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
