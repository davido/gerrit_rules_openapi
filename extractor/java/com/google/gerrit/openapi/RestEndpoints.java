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

package com.google.gerrit.openapi;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.analyze.QueryParamExtractor;
import com.google.gerrit.openapi.analyze.RequestRepresentationInferencer;
import com.google.gerrit.openapi.analyze.ResponseInferencer;
import com.google.gerrit.openapi.analyze.ViewAnalyzer;
import com.google.gerrit.openapi.bind.BindingReader;
import com.google.gerrit.openapi.dto.Binding;
import com.google.gerrit.openapi.dto.ViewFacts;
import com.google.gerrit.openapi.emit.JsonWriter;
import com.google.gerrit.openapi.emit.OpenApiEmitter;
import com.google.gerrit.openapi.emit.Overrides;
import com.google.gerrit.openapi.emit.SchemaGenerator;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.ManifestReader;
import com.google.gerrit.openapi.parse.SourceIndex;
import com.google.gerrit.openapi.path.CollectionListResolver;
import com.google.gerrit.openapi.path.KindIndex;
import com.google.gerrit.openapi.path.ListOptionCoverage;
import com.google.gerrit.openapi.path.PathAssembler;
import com.google.gerrit.openapi.path.PathRootResolver;
import com.google.gerrit.openapi.path.RootListEndpointSynthesizer;
import com.sun.source.tree.Tree;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-endpoint extraction for a single REST module (see {@code
 * Documentation/dev-openapi-inventory.md}).
 *
 * <p>For one {@code *RestApiModule} it reads the DSL bindings ({@code
 * get/put/post/delete/child/create/deleteMissing/postOnCollection/deleteOnCollection} ...{@code
 * .to(View.class)}), resolves each view class, and describes the endpoint: request type (from
 * {@code RestModifyView<R, I>} / the collection variants, verb-aware and superclass-inherited),
 * response type + status (from the concrete {@code apply()}), and query params (args4j options). A
 * {@code child(...)} collection row is modeled by its {@code list()} view, not the collection.
 * Paths are assembled from the {@code child()} nesting as a {@code resource_kind_chain} plus
 * segments, with the absolute mount of the top kind resolved by {@link PathRootResolver}. Finally
 * the open root collections' list endpoints ({@code GET /changes}), which have no DSL binding, are
 * synthesized by {@link RootListEndpointSynthesizer}.
 *
 * <p>This class is the orchestrator; the cohesive concerns live in their own building blocks:
 * {@link AstUtil}, {@link KindIndex}, {@link PathRootResolver}, {@link CollectionListResolver}
 * (list view of a collection), {@link RootListEndpointSynthesizer}, {@link ViewAnalyzer}, {@link
 * ResponseInferencer} (response type + status), {@link QueryParamExtractor} (query params), {@link
 * RequestRepresentationInferencer} (request media types). Further work should keep landing in
 * dedicated helpers rather than growing this file.
 *
 * <p>Parse-only javac Tree API, no attribution, no dependency — same hermetic model as {@link
 * RestInventory}.
 */
public final class RestEndpoints {

  public static void main(String[] args) throws Exception {
    Path manifest = null;
    Path baseDir = null;
    Path out = Path.of("endpoints.json");
    String moduleFqcn = null;
    List<String> moduleFqcns = null;
    List<String> contextModuleFqcns = List.of();
    String pluginName = "";
    String plugin = "";
    String scope = "all-core-domains";
    String format = "inventory";
    String version = "0.0.0";
    Path overridesPath = null;
    boolean allowUnusedOverrides = false;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--manifest" -> manifest = Path.of(args[++i]);
        case "--base-dir" -> baseDir = Path.of(args[++i]);
        case "--module" -> moduleFqcn = args[++i];
        case "--modules" -> moduleFqcns = List.of(args[++i].split(","));
        case "--context-modules" -> contextModuleFqcns = List.of(args[++i].split(","));
        case "--plugin-name" -> pluginName = args[++i];
        case "--plugin" -> plugin = args[++i];
        case "--scope" -> scope = args[++i];
        case "--out" -> out = Path.of(args[++i]);
        case "--format" -> format = args[++i];
        case "--version" -> version = args[++i];
        case "--overrides" -> overridesPath = Path.of(args[++i]);
        case "--allow-unused-overrides" -> allowUnusedOverrides = true;
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    if (manifest == null || (moduleFqcn == null && moduleFqcns == null)) {
      throw new IllegalArgumentException(
          "--manifest <file> and (--module <fqcn> | --modules <fqcn,fqcn,...>) are required");
    }
    if (moduleFqcn != null && moduleFqcns != null) {
      throw new IllegalArgumentException("use either --module or --modules, not both");
    }
    if (!format.equals("inventory") && !format.equals("openapi")) {
      throw new IllegalArgumentException("--format must be 'inventory' or 'openapi'");
    }
    List<Path> files;
    try {
      files = ManifestReader.read(manifest, baseDir);
    } catch (ManifestReader.InvalidManifest e) {
      System.exit(2); // ManifestReader already printed the offending paths
      return;
    }
    RestEndpoints tool = new RestEndpoints();
    tool.contextModuleFqcns = contextModuleFqcns;
    tool.pluginName = pluginName;
    tool.plugin = plugin;
    tool.version = version;
    tool.allowUnusedOverrides = allowUnusedOverrides;
    if (overridesPath != null) {
      tool.overrides = Overrides.load(Files.readString(overridesPath, StandardCharsets.UTF_8));
    }
    if (moduleFqcns != null) {
      tool.runCombined(files, moduleFqcns, scope, out, format, System.err);
    } else {
      tool.run(files, moduleFqcn, out, format, System.err);
    }
  }

  private final SourceIndex index = new SourceIndex();
  private Overrides overrides = Overrides.empty();
  private boolean allowUnusedOverrides = false;
  private List<String> contextModuleFqcns = List.of();
  private String pluginName = "";
  private String plugin = "";
  private String version = "0.0.0";
  private List<PathAssembler.ModuleBindings> contextEdges = List.of();
  private KindIndex kindIndex;
  private final ResponseInferencer responseInferencer = new ResponseInferencer();
  private final ViewAnalyzer viewAnalyzer = new ViewAnalyzer(index::resolveClass);
  private final QueryParamExtractor queryParams = new QueryParamExtractor(index::resolveClass);
  private final CollectionListResolver collectionListResolver =
      new CollectionListResolver(index::resolveClass, this::scannableView);
  private final ListOptionCoverage optionCoverage =
      new ListOptionCoverage(collectionListResolver, queryParams);
  private final RequestRepresentationInferencer requestReps =
      new RequestRepresentationInferencer(index::resolveClass);

  void run(List<Path> files, String moduleFqcn, Path out, String format, PrintStream log)
      throws IOException {
    Prep prep = prepare(files, log);
    List<Map<String, Object>> rows = describeModule(moduleFqcn, prep, new HashSet<>());
    sortRows(rows);
    write(out, moduleFqcn, rows, format);
    log.printf("module %s: %d endpoints%n", moduleFqcn, rows.size());
  }

  /**
   * Combined inventory across several modules (the all-domain golden): one sorted report of every
   * module's DSL bindings (all preserved — distinct bindings can share a method+path, e.g. create
   * vs put on a collection) plus its synthesized root-list rows (only those deduplicated by
   * method+path, so a synthesized root list never doubles an explicit binding).
   */
  void runCombined(
      List<Path> files,
      List<String> moduleFqcns,
      String label,
      Path out,
      String format,
      PrintStream log)
      throws IOException {
    Prep prep = prepare(files, log);
    List<Map<String, Object>> rows = new ArrayList<>();
    Set<String> present = new HashSet<>();
    for (String moduleFqcn : moduleFqcns) {
      rows.addAll(describeModule(moduleFqcn, prep, present));
    }
    sortRows(rows);
    write(out, label, rows, format);
    log.printf("%d modules, %s: %d endpoints%n", moduleFqcns.size(), label, rows.size());
  }

  /** Shared parse + index/resolver setup. */
  private Prep prepare(List<Path> files, PrintStream log) throws IOException {
    index.parse(files, log);
    kindIndex = new KindIndex(index.byFqcn(), index::resolveClass);
    PathRootResolver rootResolver =
        new PathRootResolver(index.byFqcn(), index::resolveClass, kindIndex, plugin);
    // Context modules seed the kind/path graph (e.g. project -> branch, change -> revision) so a
    // plugin document hanging off a core kind resolves its parent chain; no endpoints are emitted
    // from them. Empty for the core all-domain run.
    List<PathAssembler.ModuleBindings> ctx = new ArrayList<>();
    for (String fqcn : contextModuleFqcns) {
      SourceIndex.ClassInfo cm = index.byFqcn().get(fqcn);
      if (cm == null) {
        throw new IllegalStateException("context module not found in manifest: " + fqcn);
      }
      ctx.add(new PathAssembler.ModuleBindings(BindingReader.read(cm), cm));
    }
    contextEdges = ctx;
    return new Prep(
        rootResolver,
        new RootListEndpointSynthesizer(
            rootResolver, collectionListResolver, viewAnalyzer, responseInferencer, queryParams));
  }

  /**
   * Maps a resolved list view to the class whose {@code @Option} query fields should be scanned. A
   * concrete view is returned as-is; an interface view -- e.g. {@code ListProjects}, whose options
   * live on {@code AbstractListProjects implements ListProjects} -- is mapped to its unique
   * in-manifest implementor. Falls back to the interface itself when there is no implementor or the
   * implementor is ambiguous (never guess).
   */
  private SourceIndex.ClassInfo scannableView(SourceIndex.ClassInfo view) {
    if (view == null || view.tree == null || view.tree.getKind() != Tree.Kind.INTERFACE) {
      return view;
    }
    String iface = view.tree.getSimpleName().toString();
    SourceIndex.ClassInfo impl = null;
    for (SourceIndex.ClassInfo c : index.byFqcn().values()) {
      if (c.tree == null || c.tree.getKind() == Tree.Kind.INTERFACE) {
        continue;
      }
      for (Tree it : c.tree.getImplementsClause()) {
        if (iface.equals(AstUtil.lastIdent(it))) {
          if (impl != null && impl != c) {
            return view; // ambiguous implementor -- don't guess
          }
          impl = c;
        }
      }
    }
    return impl != null ? impl : view;
  }

  private record Prep(PathRootResolver rootResolver, RootListEndpointSynthesizer rootLists) {}

  /**
   * Rows for one module's DSL bindings + synthesized root lists. {@code present} records every
   * emitted method+path so a synthesized root-list row never doubles an existing binding.
   */
  private List<Map<String, Object>> describeModule(
      String moduleFqcn, Prep prep, Set<String> present) {
    SourceIndex.ClassInfo module = index.byFqcn().get(moduleFqcn);
    if (module == null) {
      throw new IllegalStateException("module not found in manifest: " + moduleFqcn);
    }
    List<Binding> bindings = BindingReader.read(module);
    PathAssembler paths =
        new PathAssembler(
            bindings,
            module,
            index,
            viewAnalyzer,
            kindIndex,
            prep.rootResolver(),
            contextEdges,
            pluginName);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (Binding b : bindings) {
      // A binding whose kind has multiple parents (files, reviewers) emits one endpoint per parent
      // path; a single-parent binding emits exactly one.
      for (Map<String, Object> pathFields : paths.pathVariants(b)) {
        Map<String, Object> row = describe(b, module, pathFields);
        if (row != null) {
          rows.add(row);
          present.add(row.get("http_method") + " " + row.get("path_template"));
        }
      }
    }
    // Root-collection listings (GET /changes, /groups) have no DSL .to(...) binding — synthesize
    // them from the open root collections the module *owns*: mapOf(binder(), KIND) or a
    // collection-level op (postOnCollection/deleteOnCollection). A kind the module only references
    // via a named/item binding (a plugin hanging off a core kind) is not owned, so a plugin
    // document never claims GET /changes. Skip any that a binding already produced.
    for (Map<String, Object> synth : prep.rootLists().synthesize(module, bindings)) {
      if (present.add(synth.get("http_method") + " " + synth.get("path_template"))) {
        rows.add(synth);
      }
    }
    return rows;
  }

  /**
   * Resolve a type name against {@code view} and each of its superclasses in turn — the first that
   * imports/declares it wins. An inherited request type is resolvable only from the superclass
   * whose interface declared it, not from the bound subclass.
   *
   * <p>Best-effort: if a subclass and a superclass both have a different type of the same simple
   * name, the subclass wins here even when the inherited interface referred to the superclass's. A
   * fully robust model would have {@link ViewAnalyzer} carry the request type's declaring {@link
   * SourceIndex.ClassInfo} in {@link ViewFacts}; no core view triggers the ambiguity today, so that
   * is a follow-up before plugin reuse.
   */
  @Nullable
  private SourceIndex.ClassInfo resolveInHierarchy(String typeName, SourceIndex.ClassInfo view) {
    for (SourceIndex.ClassInfo cur : AstUtil.superclassChain(view, index::resolveClass)) {
      SourceIndex.ClassInfo resolved = index.resolveClass(typeName, cur);
      if (resolved != null) {
        return resolved;
      }
    }
    return null;
  }

  private static void sortRows(List<Map<String, Object>> rows) {
    rows.sort(
        Comparator.comparing((Map<String, Object> m) -> (String) m.get("path_template"))
            .thenComparing(m -> (String) m.get("http_method"))
            .thenComparing(m -> (String) m.get("view_fqcn")));
  }

  @Nullable
  private Map<String, Object> describe(
      Binding b, SourceIndex.ClassInfo module, Map<String, Object> pathFields) {
    SourceIndex.ClassInfo bound = index.resolveClass(b.viewSimpleName(), module);
    boolean childList = b.verb().equals("child");

    // A child(...) binding is the collection-list endpoint; the bound class is the collection, not
    // the list view (collection.list(), bound by injection). Resolve that list view so the row is
    // modeled by it — its query params, response, and fqcn — rather than by the collection class.
    SourceIndex.ClassInfo view = bound;
    String childListReason = null;
    if (childList && bound != null) {
      CollectionListResolver.Result r = collectionListResolver.resolve(bound);
      if (r.view() != null) {
        view = r.view();
      } else {
        // Some ChildCollection roots are lookup-only: they parse named children but deliberately
        // throw from list(). Those roots return 404 at runtime and should not be published as
        // successful collection-list operations.
        if (isLookupOnlyChildList(r.unsupportedReason())) {
          return null;
        }
        view = null;
        childListReason = r.unsupportedReason();
      }
    }

    ViewFacts facts = view != null ? viewAnalyzer.analyze(view) : null;
    Map<String, Object> row = new LinkedHashMap<>();
    row.putAll(pathFields); // http_method + path fields (one parent-chain variant)
    List<String> unresolved = new ArrayList<>();

    row.put(
        "view_fqcn", view != null ? view.fqcn : (bound != null ? bound.fqcn : b.viewSimpleName()));
    if (bound == null) {
      unresolved.add("view class not in manifest: " + b.viewSimpleName());
    } else if (childList && view == null) {
      unresolved.add(bound.fqcn + ".list(): " + childListReason);
    }

    // request type from the request-bearing interface (RestModifyView / collection modify /
    // create / delete-missing), verb-aware and including superclass-inherited interfaces. A
    // child(...) list view is a GET, so it carries no request body.
    String requestType = facts != null ? viewAnalyzer.requestType(facts, b.verb()) : null;
    if (requestType != null && facts.unresolvedTypeVars().contains(requestType)) {
      unresolved.add(
          "request type is an unresolved superclass type variable (" + requestType + ")");
    }
    row.put("request_type", requestType);
    // Resolve the input class against the class that declares the request-bearing interface — walk
    // the view's superclass chain, since an inherited request type is imported by the superclass
    // (e.g. DeletePrivateByPost inherits DeletePrivate's InputWithMessage), not the bound view.
    boolean concreteInput =
        requestType != null
            && !requestType.equals("String")
            && requestType.indexOf('<')
                < 0 // a List<..>/generic body is a JSON array (json), not a class
            && view != null
            && !facts.unresolvedTypeVars().contains(requestType);
    SourceIndex.ClassInfo inputClass = concreteInput ? resolveInHierarchy(requestType, view) : null;
    row.put(
        "request_representations",
        requestReps.infer(requestType, (String) row.get("http_method"), inputClass));
    // A concrete body-bearing input we can't resolve can't be classified (raw/text would be
    // hidden), so request_representations falls back to json — flag that it's a guess.
    if (concreteInput && inputClass == null) {
      unresolved.add(
          "input type " + requestType + " not in manifest; request_representations assumed json");
    }
    // A child(...) collection's list() may branch (query-vs-list) or return an interface view, just
    // like a root collection's. Model the endpoint's query params as the union over all dispatch
    // branches (via resolveAllViews, which also does the interface->impl hop), falling back to the
    // single resolved view for the ordinary one-branch case. Response/fqcn stay on the single view.
    List<Map<String, Object>> params;
    if (childList && bound != null) {
      List<SourceIndex.ClassInfo> listViews = collectionListResolver.resolveAllViews(bound);
      params = listViews.isEmpty() ? queryParams.rows(view) : queryParams.unionRows(listViews);
    } else {
      params = queryParams.rows(view);
    }
    row.put("query_params", params);
    // Defense in depth: fail the build loudly if a child collection still resolves to
    // option-bearing
    // views yet emits none (e.g. a future regression in the union above).
    if (childList) {
      optionCoverage.check(row.get("http_method") + " " + row.get("path_template"), bound, params);
    }

    row.put("child_list", childList);
    ResponseInferencer.putResponseFields(row, responseInferencer.infer(facts), view != null);

    row.put(
        "binding",
        b.verb() + "(" + b.kind() + (b.name() != null ? ", \"" + b.name() + "\"" : "") + ")");
    row.put("unsupported_reason", unresolved.isEmpty() ? null : String.join("; ", unresolved));
    return row;
  }

  private static boolean isLookupOnlyChildList(@Nullable String unsupportedReason) {
    return unsupportedReason != null
        && (unsupportedReason.contains("no list() method")
            || unsupportedReason.contains("list() has no return statement"));
  }

  private void write(Path out, String scope, List<Map<String, Object>> rows, String format)
      throws IOException {
    if (format.equals("openapi")) {
      // OpenAPI projection with schemas generated from the parsed Input/Info classes.
      Files.writeString(
          out,
          OpenApiEmitter.emit(
              "Gerrit Code Review REST API",
              version,
              rows,
              new SchemaGenerator(index),
              overrides,
              allowUnusedOverrides),
          StandardCharsets.UTF_8);
    } else {
      writeJson(out, scope, rows);
    }
  }

  private static void writeJson(Path out, String scope, List<Map<String, Object>> rows)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"schema_version\": 1,\n  \"report\": \"endpoints\",\n  \"scope\": \"")
        .append(JsonWriter.esc(scope))
        .append("\",\n  \"endpoints\": [\n");
    for (int i = 0; i < rows.size(); i++) {
      sb.append("    ");
      JsonWriter.writeObj(sb, rows.get(i));
      sb.append(i + 1 < rows.size() ? ",\n" : "\n");
    }
    sb.append("  ]\n}\n");
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }
}
