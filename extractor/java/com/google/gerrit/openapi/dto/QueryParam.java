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
 * One query parameter of a REST endpoint, extracted from an args4j {@code @Option} or
 * {@code @Argument} on a view field or setter (see {@code QueryParamExtractor}).
 *
 * <p>{@code kind} is {@code "option"} for {@code @Option} (named, e.g. {@code --limit} / {@code
 * -n}) or {@code "argument"} for {@code @Argument} (positional). {@code name} is the primary
 * declared name as written in source ({@code @Option(name=...)}); {@code aliases} are the
 * additional {@code aliases={...}} names. {@code repeated} is true when the parameter accepts
 * multiple values: a {@code List}/{@code Set}/{@code Collection}/immutable-collection or array
 * target, an {@code add*} setter method, or {@code @Argument(multiValued=true)}. {@code declaredIn}
 * is {@code "self"} when declared on the bound view, or the simple name of the superclass it was
 * inherited from.
 */
public record QueryParam(
    String kind,
    @Nullable String name,
    List<String> aliases,
    String type,
    boolean required,
    boolean repeated,
    @Nullable String metaVar,
    @Nullable String usage,
    String declaredIn) {
  /** Defensively copies the alias list so the record is genuinely immutable. */
  public QueryParam {
    aliases = List.copyOf(aliases);
  }
}
