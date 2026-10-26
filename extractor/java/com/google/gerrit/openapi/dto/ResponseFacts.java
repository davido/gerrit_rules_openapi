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

package com.google.gerrit.openapi.dto;

import com.google.gerrit.common.Nullable;
import java.util.List;

/**
 * Immutable response facts for one endpoint: payload type + HTTP status, each with a confidence.
 */
public record ResponseFacts(
    @Nullable String type,
    boolean needsOverride,
    String typeConfidence,
    @Nullable String status,
    String statusConfidence,
    List<String> factories) {
  /** Defensively copies the factory list so the record is genuinely immutable. */
  public ResponseFacts {
    factories = List.copyOf(factories);
  }
}
