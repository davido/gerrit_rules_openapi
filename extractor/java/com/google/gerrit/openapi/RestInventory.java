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

import static com.google.common.base.Equivalence.identity;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.emit.JsonWriter;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.ManifestReader;
import com.google.gerrit.openapi.parse.SourceIndex;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.util.TreeScanner;
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
import java.util.TreeMap;

/**
 * Modules-only REST inventory (see {@code Documentation/dev-openapi-inventory.md}).
 *
 * <p>Modules-only: statically traverse the REST {@code install(new X())} graph from the root REST
 * module, cross-check against every {@code RestApiModule} subclass in the supplied source manifest,
 * and record ignored non-REST installs. No request/response inference. Shares the parse/resolve
 * foundation ({@link SourceIndex}), manifest reading ({@link ManifestReader}), and JSON writing
 * ({@link JsonWriter}) with the endpoint extractor; only the install-graph logic is specific here.
 */
public final class RestInventory {
  private static final String DEFAULT_ROOT = "com.google.gerrit.server.restapi.RestApiModule";
  private static final String DEFAULT_REST_BASE =
      "com.google.gerrit.extensions.restapi.RestApiModule";

  public static void main(String[] args) throws Exception {
    Path manifest = null;
    Path out = Path.of("inventory-modules.json");
    Path baseDir = null;
    String root = DEFAULT_ROOT;
    String restBase = DEFAULT_REST_BASE;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--manifest" -> manifest = Path.of(args[++i]);
        case "--out" -> out = Path.of(args[++i]);
        case "--base-dir" -> baseDir = Path.of(args[++i]);
        case "--root" -> root = args[++i];
        case "--rest-base" -> restBase = args[++i];
        default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
      }
    }
    if (manifest == null) {
      throw new IllegalArgumentException("--manifest <file listing .java paths> is required");
    }
    List<Path> files;
    try {
      files = ManifestReader.read(manifest, baseDir);
    } catch (ManifestReader.InvalidManifest e) {
      System.exit(2); // ManifestReader already printed the offending paths
      return;
    }
    if (files.isEmpty()) {
      throw new IllegalStateException("manifest yielded no readable source files: " + manifest);
    }
    new RestInventory().run(files, root, restBase, out, System.err);
  }

  private final SourceIndex index = new SourceIndex();
  // classes that extend (transitively) the DSL RestApiModule base.
  private final Set<ClassInfo> restModules = new HashSet<>();

  void run(List<Path> files, String root, String restBase, Path out, PrintStream log)
      throws IOException {
    index.parse(files, log);
    markRestApiModules(restBase);

    // Traverse the install graph from the root.
    Map<String, List<String>> installPath = new TreeMap<>(); // fqcn -> edge chain from root
    List<Map<String, Object>> ignored = new ArrayList<>();
    ClassInfo rootClass = index.byFqcn().get(root);
    if (rootClass == null) {
      throw new IllegalStateException("root module not found in manifest: " + root);
    }
    installPath.put(root, List.of(root));
    traverse(rootClass, List.of(root), installPath, ignored);

    // Build the module report: reached REST modules + superset-only + ignored non-REST installs.
    List<Map<String, Object>> modules = new ArrayList<>();
    for (ClassInfo ci : index.byFqcn().values()) {
      if (!restModules.contains(ci)) {
        continue;
      }
      boolean reached = installPath.containsKey(ci.fqcn);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("module_fqcn", ci.fqcn);
      row.put("module_binary_name", ci.binaryName);
      row.put("reached_by", reached ? "install-graph" : "superset-only");
      row.put("install_path", reached ? installPath.get(ci.fqcn) : null);
      row.put("rest_api_module", true);
      if (!reached) {
        row.put(
            "discovery_warning",
            "REST module not reached from root " + root + "; installed via another injector");
      }
      modules.add(row);
    }
    modules.addAll(ignored);

    // Deterministic ordering: reached before superset-only before ignored, then by fqcn.
    modules.sort(
        Comparator.comparingInt((Map<String, Object> m) -> rank(m))
            .thenComparing(m -> (String) m.get("module_fqcn")));

    writeJson(out, modules);
    summarize(modules, log, out);
  }

  private static int rank(Map<String, Object> m) {
    if (Boolean.FALSE.equals(m.get("rest_api_module"))) {
      return 2; // ignored non-REST
    }
    return "install-graph".equals(m.get("reached_by")) ? 0 : 1;
  }

  @Nullable
  private static String extendsSimpleOf(ClassInfo ci) {
    return ci.tree == null ? null : AstUtil.lastIdent(ci.tree.getExtendsClause());
  }

  /**
   * Mark classes that extend (transitively) the DSL RestApiModule base. Membership requires the
   * extends clause to <em>resolve</em> to the configured base (or to an already-marked subclass);
   * an unresolved simple name is not assumed to be the base, so a wildcard import or a
   * same-named-but-different {@code RestApiModule} cannot produce a false positive.
   */
  private void markRestApiModules(String restBase) {
    boolean changed = true;
    while (changed) {
      changed = false;
      for (ClassInfo ci : index.byFqcn().values()) {
        if (restModules.contains(ci)) {
          continue;
        }
        String extendsSimple = extendsSimpleOf(ci);
        if (extendsSimple == null) {
          continue;
        }
        ClassInfo resolved = index.resolveClass(extendsSimple, ci);
        boolean isBase = resolved != null && restBase.equals(resolved.fqcn);
        boolean isKnownSubclass = resolved != null && restModules.contains(resolved);
        if (isBase || isKnownSubclass) {
          restModules.add(ci);
          changed = true;
        }
      }
    }
  }

  /** DFS the install(new X()) graph, recording edges and ignored non-REST installs. */
  private void traverse(
      ClassInfo module,
      List<String> pathToHere,
      Map<String, List<String>> installPath,
      List<Map<String, Object>> ignored) {
    for (NewClassTree nc : findInstalls(module.tree)) {
      ClassInfo resolvedCi = index.resolveClass(nc.getIdentifier().toString(), module);
      if (resolvedCi == null) {
        resolvedCi = index.resolveClass(AstUtil.lastIdent(nc.getIdentifier()), module);
      }
      String resolved = resolvedCi != null ? resolvedCi.fqcn : null;
      List<String> childPath = new ArrayList<>(pathToHere);
      childPath.add(resolved != null ? resolved : "UNRESOLVED:" + nc.getIdentifier());
      if (resolved == null) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("module_fqcn", nc.getIdentifier().toString());
        row.put("module_binary_name", null);
        row.put("reached_by", "install-graph");
        row.put("install_path", childPath);
        row.put("rest_api_module", false);
        row.put("ignored_reason", "unresolved install target from " + module.fqcn);
        ignored.add(row);
        continue;
      }
      ClassInfo child = resolvedCi;
      if (restModules.contains(child)) {
        if (!installPath.containsKey(resolved)) {
          installPath.put(resolved, childPath);
          traverse(child, childPath, installPath, ignored);
        }
      } else {
        String extendsSimple = extendsSimpleOf(child);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("module_fqcn", resolved);
        row.put("module_binary_name", child.binaryName);
        row.put("reached_by", "install-graph");
        row.put("install_path", childPath);
        row.put("rest_api_module", false);
        row.put(
            "ignored_reason",
            extendsSimple == null
                ? "extends Object; not a RestApiModule"
                : "extends " + extendsSimple + ", not the DSL RestApiModule");
        ignored.add(row);
      }
    }
  }

  /**
   * All {@code install(new X(...))} arguments in <em>this</em> class body only. Nested classes are
   * not descended into: an inner module's installs belong to the inner module, and are picked up
   * when that inner module is itself reached and traversed.
   */
  private static List<NewClassTree> findInstalls(ClassTree ct) {
    List<NewClassTree> result = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitClass(ClassTree node, Void p) {
        // Identity, not value equality: scan only the target class body, not nested classes.
        return identity().equivalent(node, ct) ? super.visitClass(node, p) : null;
      }

      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if ("install".equals(AstUtil.lastIdent(node.getMethodSelect()))
            && !node.getArguments().isEmpty()
            && node.getArguments().get(0) instanceof NewClassTree nc) {
          result.add(nc);
        }
        return super.visitMethodInvocation(node, p);
      }
    }.scan(ct, null);
    return result;
  }

  private static void writeJson(Path out, List<Map<String, Object>> modules) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"schema_version\": 1,\n  \"milestone\": 1,\n  \"modules\": [\n");
    for (int i = 0; i < modules.size(); i++) {
      sb.append("    ");
      JsonWriter.writeObj(sb, modules.get(i));
      sb.append(i + 1 < modules.size() ? ",\n" : "\n");
    }
    sb.append("  ]\n}\n");
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
  }

  private static void summarize(List<Map<String, Object>> modules, PrintStream log, Path out) {
    long reached =
        modules.stream()
            .filter(
                m ->
                    "install-graph".equals(m.get("reached_by"))
                        && Boolean.TRUE.equals(m.get("rest_api_module")))
            .count();
    long supersetOnly =
        modules.stream().filter(m -> "superset-only".equals(m.get("reached_by"))).count();
    long ignoredNonRest =
        modules.stream().filter(m -> Boolean.FALSE.equals(m.get("rest_api_module"))).count();
    log.printf("%n=== REST module inventory ===%n");
    log.printf("REST modules reached from root : %d%n", reached);
    log.printf("REST modules superset-only     : %d  (bound via other injectors)%n", supersetOnly);
    log.printf("ignored non-REST installs      : %d%n", ignoredNonRest);
    log.printf("wrote %s%n", out.toAbsolutePath());
  }
}
