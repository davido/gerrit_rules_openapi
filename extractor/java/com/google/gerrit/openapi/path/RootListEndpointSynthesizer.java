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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.analyze.QueryParamExtractor;
import com.google.gerrit.openapi.analyze.ResponseInferencer;
import com.google.gerrit.openapi.analyze.ViewAnalyzer;
import com.google.gerrit.openapi.dto.Binding;
import com.google.gerrit.openapi.dto.ViewFacts;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Synthesizes the root-collection <em>list</em> endpoints — {@code GET /changes}, {@code GET
 * /groups} — that have <b>no DSL {@code .to(...)} binding at all</b>. The servlet serves them by
 * calling {@code rootCollection.list()}, so unlike every other endpoint they are not discoverable
 * from a module's {@code configure()}. Without this they are silently absent from the inventory — a
 * false "complete" for the API's most-used query surfaces (`?q=`, `?n=`, `?S=`, `?o=`).
 *
 * <p>For each top-level kind that has an <b>open</b> root collection ({@link
 * PathRootResolver#rootCollectionOf} resolves it and it is not a singleton root), this emits a
 * {@code GET} row modeled by the collection's {@code list()} view (via {@link
 * CollectionListResolver}) — the same conservative resolution M6a uses for {@code child(...)} rows.
 * The row is marked {@code binding: "rootList(<KIND>)"} and {@code child_list: false}. A collection
 * whose {@code list()} does not resolve (conditional dispatch — {@code GroupsCollection}, {@code
 * ProjectsCollection}) still gets a row, with an explicit {@code unsupported_reason}, so the
 * endpoint is present rather than a silent omission.
 */
public final class RootListEndpointSynthesizer {
  private final PathRootResolver rootResolver;
  private final CollectionListResolver listResolver;
  private final ViewAnalyzer viewAnalyzer;
  private final ResponseInferencer responseInferencer;
  private final QueryParamExtractor queryParams;
  private final ListOptionCoverage optionCoverage;

  public RootListEndpointSynthesizer(
      PathRootResolver rootResolver,
      CollectionListResolver listResolver,
      ViewAnalyzer viewAnalyzer,
      ResponseInferencer responseInferencer,
      QueryParamExtractor queryParams) {
    this.rootResolver = rootResolver;
    this.listResolver = listResolver;
    this.viewAnalyzer = viewAnalyzer;
    this.responseInferencer = responseInferencer;
    this.queryParams = queryParams;
    this.optionCoverage = new ListOptionCoverage(listResolver, queryParams);
  }

  /**
   * Collection-level verbs: an operation on the collection itself, hence root-collection ownership.
   */
  private static final Set<String> COLLECTION_VERBS =
      Set.of("postOnCollection", "deleteOnCollection");

  /**
   * One synthesized {@code GET} row per open root collection the module <b>owns</b>. A module owns
   * a resource kind when it either registers it via {@code DynamicMap.mapOf(binder(), <KIND>)}
   * (e.g. {@code AccessRestApiModule}, whose {@code /access} listing has no {@code .to(...)}
   * binding at all), or binds a <em>collection-level</em> operation on it -- {@code
   * postOnCollection(KIND)} / {@code deleteOnCollection(KIND)} (as {@code core changes} does for
   * {@code /changes}).
   *
   * <p>An <em>item</em> or <em>named</em> binding is deliberately NOT ownership: {@code get(KIND)}
   * reads one resource, and {@code get(CHANGE_KIND, "code_owners.status")} only hangs a sub-view
   * off a core collection -- so a plugin document never claims a core list API like {@code GET
   * /changes}. Owned kinds that are not open top-level collections (nested sub-collection maps,
   * singleton roots) are dropped by the {@link PathRootResolver#rootCollectionOf} filter below.
   */
  public List<Map<String, Object>> synthesize(ClassInfo module, List<Binding> bindings) {
    Set<String> owned = ModuleKinds.mapOfOwned(module); // DynamicMap.mapOf(binder(), KIND)
    for (Binding b : bindings) {
      if (COLLECTION_VERBS.contains(b.verb())) {
        owned.add(b.kind());
      }
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (String topKind : owned) {
      ClassInfo rootColl = rootResolver.rootCollectionOf(topKind);
      if (rootColl == null || rootResolver.isSingletonRoot(rootColl)) {
        // Not a top-level collection in the manifest, or a singleton root (no listing endpoint).
        continue;
      }
      rows.add(describeRootList(topKind, rootColl));
    }
    return rows;
  }

  private Map<String, Object> describeRootList(String topKind, ClassInfo rootColl) {
    // Resolve list() on the concrete impl (interface-bound roots like accounts); the servlet
    // prefix / path below stays keyed on the (possibly interface) root collection.
    ClassInfo listSource = rootResolver.listSource(rootColl);
    CollectionListResolver.Result r = listResolver.resolve(listSource);
    ClassInfo view = r.view();
    ViewFacts facts = view != null ? viewAnalyzer.analyze(view) : null;
    String listPath = rootResolver.collectionListPath(rootColl);

    // Field order mirrors RestEndpoints.describe() so synthesized and DSL rows share one shape.
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("http_method", "GET");
    // A root list is a collection endpoint (GET /changes), so an unresolved mount uses the
    // "-collection" form (matching PathAssembler), not the item-root "-root" form.
    row.put("path_template", listPath != null ? listPath : "{" + topKind + "-collection}");
    row.put("resource_kind_chain", List.of(topKind));
    row.put("path_confidence", listPath != null ? "high" : "medium");
    row.put("unresolved_path_reason", listPath != null ? null : "collection mount not resolved");
    // When the list view doesn't resolve, name the class whose list() failed (the impl for an
    // interface-bound root), consistent with the unsupported_reason below.
    row.put("view_fqcn", view != null ? view.fqcn : listSource.fqcn);
    row.put("request_type", null);
    row.put("request_representations", List.of("none")); // a root list is a GET read
    // A collection's list() may dispatch to more than one view (e.g. ProjectsCollection /
    // GroupsCollection choose a query view or a list view by whether ?query is set). args4j accepts
    // the union of every branch's @Option flags, so the endpoint's query params are that union --
    // not just the single view resolve() settles on for the response shape (it declines the
    // conditional dispatch and yields none). Dedupe by param name, first occurrence wins.
    List<Map<String, Object>> params = unionQueryParams(listSource, view);
    row.put("query_params", params);
    optionCoverage.check("GET " + row.get("path_template"), listSource, params);
    row.put("child_list", false);
    ResponseInferencer.putResponseFields(row, responseInferencer.infer(facts), view != null);
    row.put("binding", "rootList(" + topKind + ")");
    row.put(
        "unsupported_reason",
        view != null ? null : listSource.fqcn + ".list(): " + r.unsupportedReason());
    return row;
  }

  /**
   * The union of {@code @Option} query params over every list view {@code list()} can dispatch to.
   * Falls back to {@code resolve}'s single view when the multi-branch walk finds nothing (e.g. a
   * covariant concrete return already handled there).
   */
  private List<Map<String, Object>> unionQueryParams(
      ClassInfo listSource, @Nullable ClassInfo view) {
    List<ClassInfo> views = listResolver.resolveAllViews(listSource);
    return views.isEmpty() ? queryParams.rows(view) : queryParams.unionRows(views);
  }
}
