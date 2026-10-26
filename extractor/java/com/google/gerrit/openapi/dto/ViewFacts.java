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
import com.sun.source.tree.MethodTree;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable analysis of one view class: the {@code RestView} interfaces it carries (directly or
 * inherited, with generic substitution), its {@code apply()} return/method, and any superclass type
 * variables left unsubstituted. Produced by {@code ViewAnalyzer}; consumed by the extractor and
 * {@code ResponseInferencer}.
 */
public record ViewFacts(
    Map<String, List<String>> viewIfaces,
    @Nullable String applyReturn,
    @Nullable MethodTree applyMethod,
    Set<String> unresolvedTypeVars) {
  public static final ViewFacts EMPTY = new ViewFacts(Map.of(), null, null, Set.of());

  /** Defensively copies the collections so the record is genuinely immutable. */
  public ViewFacts {
    Map<String, List<String>> copy = new LinkedHashMap<>();
    viewIfaces.forEach((k, v) -> copy.put(k, List.copyOf(v)));
    viewIfaces = Collections.unmodifiableMap(copy);
    unresolvedTypeVars = Set.copyOf(unresolvedTypeVars);
  }
}
