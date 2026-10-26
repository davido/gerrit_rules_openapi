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

package com.google.gerrit.openapi.path;

import com.google.gerrit.openapi.analyze.QueryParamExtractor;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Build-time guard against a collection-list endpoint silently dropping its list view's query
 * options.
 *
 * <p>The failure it prevents actually shipped: {@code GET /projects} and {@code GET /groups}
 * emitted empty {@code query_params} for months because their {@code list()} branches on {@code
 * ?query} (two returns), {@link CollectionListResolver#resolve} declines conditional dispatch, and
 * "no resolved view" collapsed into "no options" — indistinguishable in the output from a
 * legitimately optionless endpoint. The same trap sits on any {@code list()} that returns an
 * <em>interface</em> view (options live on the implementor, not the interface).
 *
 * <p>This asserts the invariant instead of trusting it: if a collection's {@code list()} resolves
 * via {@link CollectionListResolver#resolveAllViews} (which unions every dispatch branch and
 * applies the interface→impl hop) to any view that declares options, the emitted {@code
 * query_params} must be non-empty. A non-listing collection ({@code list()} throws or is not
 * statically resolvable) yields no views, so the guard stays silent — it fires only on a genuine
 * drop, and points the developer at {@code resolveAllViews} as the fix.
 */
public final class ListOptionCoverage {
  private final CollectionListResolver listResolver;
  private final QueryParamExtractor queryParams;

  public ListOptionCoverage(CollectionListResolver listResolver, QueryParamExtractor queryParams) {
    this.listResolver = listResolver;
    this.queryParams = queryParams;
  }

  /**
   * Throws {@link IllegalStateException} if {@code collection}'s {@code list()} resolves to
   * option-bearing view(s) yet {@code emittedParams} is empty.
   *
   * @param endpoint human-readable "METHOD /path" for the diagnostic
   * @param collection the collection class whose {@code list()} backs this endpoint
   * @param emittedParams the {@code query_params} rows the emitter produced for the endpoint
   */
  public void check(
      String endpoint, ClassInfo collection, List<Map<String, Object>> emittedParams) {
    if (!emittedParams.isEmpty() || collection == null) {
      return; // options present, or nothing to resolve against
    }
    Set<String> expected = new LinkedHashSet<>();
    for (ClassInfo view : listResolver.resolveAllViews(collection)) {
      for (Map<String, Object> qp : queryParams.rows(view)) {
        expected.add((String) qp.get("name"));
      }
    }
    if (expected.isEmpty()) {
      return; // non-listing or optionless collection -- an empty param list is correct
    }
    throw new IllegalStateException(
        "list endpoint "
            + endpoint
            + " emits no query params, but its list() resolves to view(s) declaring "
            + expected
            + " -- a branching or interface-typed list() view was dropped. Union options across all"
            + " list() branches via CollectionListResolver.resolveAllViews (see"
            + " RootListEndpointSynthesizer for the pattern).");
  }
}
