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

package com.google.gerrit.openapi.analyze;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.dto.QueryParam;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Facade over query-parameter extraction: caches per view class, delegates scanning to {@link
 * Args4jParamScanner} (which uses {@link AnnotationReader} for annotation matching), and stabilizes
 * the result order. The cohesive concerns live in those two building blocks so this stays a thin
 * entry point — the same "orchestrator + helpers" shape as {@code RestEndpoints} itself.
 *
 * <p>Parse-only javac Tree API — the same hermetic model as the rest of the extractor.
 */
public final class QueryParamExtractor {
  private final Args4jParamScanner scanner;
  private final Map<ClassInfo, List<QueryParam>> cache = new HashMap<>();

  public QueryParamExtractor(BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    this.scanner = new Args4jParamScanner(resolveClass);
  }

  /** Query parameters of {@code view}, declared on it or inherited, sorted deterministically. */
  public List<QueryParam> extract(ClassInfo view) {
    List<QueryParam> cached = cache.get(view);
    if (cached != null) {
      return cached;
    }
    List<QueryParam> params = scanner.scan(view);
    params.sort(
        Comparator.comparing(Args4jParamScanner::effectiveName).thenComparing(QueryParam::kind));
    cache.put(view, params);
    return params;
  }

  /** JSON-ready rows for a view's query params (empty when the view is null or has none). */
  public List<Map<String, Object>> rows(@Nullable ClassInfo view) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (view == null) {
      return out;
    }
    for (QueryParam qp : extract(view)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("kind", qp.kind());
      m.put("name", qp.name());
      m.put("aliases", qp.aliases());
      m.put("type", qp.type());
      m.put("required", qp.required());
      m.put("repeated", qp.repeated());
      m.put("meta_var", qp.metaVar());
      m.put("usage", qp.usage());
      m.put("declared_in", qp.declaredIn());
      out.add(m);
    }
    return out;
  }

  /**
   * The union of {@code rows(view)} over several views, deduped by param name (first occurrence
   * wins). A collection's {@code list()} may dispatch to more than one view (query-vs-list by
   * {@code ?query}); args4j accepts the union of every branch's options, so a collection-list
   * endpoint's query params are that union rather than any single branch's. When {@code views} is
   * empty this returns an empty list -- callers fall back to the single-view {@link #rows} for that
   * case.
   */
  public List<Map<String, Object>> unionRows(List<ClassInfo> views) {
    List<Map<String, Object>> out = new ArrayList<>();
    Set<Object> seen = new HashSet<>();
    for (ClassInfo v : views) {
      for (Map<String, Object> qp : rows(v)) {
        // Dedupe named options; keep every positional argument (name == null), which args4j
        // distinguishes by position, not name.
        Object name = qp.get("name");
        if (name == null || seen.add(name)) {
          out.add(qp);
        }
      }
    }
    return out;
  }
}
