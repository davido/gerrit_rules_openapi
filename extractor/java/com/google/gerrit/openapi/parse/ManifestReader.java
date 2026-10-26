// Copyright (C) 2026 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.openapi.parse;

import com.google.gerrit.common.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a source manifest (one .java path per line) into an explicit, validated file list,
 * resolving relative entries against {@code --base-dir}. Shared by the OpenAPI tools so both fail
 * loudly and identically when a path is missing (under {@code bazel run} the cwd is the runfiles
 * dir, not the workspace root).
 */
public final class ManifestReader {
  private ManifestReader() {}

  /** A manifest entry did not resolve to a readable file; {@code main()} maps this to exit 2. */
  public static final class InvalidManifest extends Exception {
    private static final long serialVersionUID = 1L;

    InvalidManifest(String message) {
      super(message);
    }
  }

  public static List<Path> read(Path manifest, @Nullable Path baseDir)
      throws IOException, InvalidManifest {
    List<Path> files = new ArrayList<>();
    List<String> missing = new ArrayList<>();
    for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
      String p = line.strip();
      if (p.isEmpty()) {
        continue;
      }
      Path path = Path.of(p);
      if (baseDir != null && !path.isAbsolute()) {
        path = baseDir.resolve(path);
      }
      if (Files.isRegularFile(path)) {
        files.add(path);
      } else {
        missing.add(path.toString());
      }
    }
    if (!missing.isEmpty()) {
      System.err.printf("ERROR: %d manifest entries are not readable files.%n", missing.size());
      missing.stream().limit(5).forEach(m -> System.err.println("  " + m));
      throw new InvalidManifest(missing.size() + " unreadable manifest entries");
    }
    return files;
  }
}
