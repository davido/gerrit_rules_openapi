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
import com.google.gerrit.openapi.analyze.ViewAnalyzer;
import com.google.gerrit.openapi.dto.Binding;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Models the resource-kind nesting and turns a binding into its HTTP method + path. Owns the kind
 * graph (built from {@code child()} bindings) and the absolute-root map, then fills the path fields
 * of a report row. Keeps the path/kind-graph logic out of the extractor's row assembly.
 */
public final class PathAssembler {
  // A resource kind can be a child of MULTIPLE parents: FileResource hangs off branch, commit and
  // revision; ReviewerResource off change and revision. Recording every (parent, segment) edge --
  // not just the last-registered one -- lets the item views bound on such a kind emit under EACH
  // parent path instead of silently dropping all but one.
  private final Map<String, LinkedHashSet<Edge>> childEdges = new LinkedHashMap<>();

  /** A single {@code child(parentKind, segment)} edge onto a child resource kind. */
  private record Edge(String parentKind, String segment) {}

  private final Map<String, String> roots = new LinkedHashMap<>(); // topKind -> absolute mount
  private final Set<String> emitOwnedKinds = new LinkedHashSet<>(); // kinds the emit module defines
  private final String pluginName; // non-empty -> emit the explicit <plugin>~ segment form

  // The /plugins mount is served by HttpPluginServlet, not the normal RestApiServlet: it routes a
  // named view on a plugin resource only when the path segment is prefixed with the owning plugin's
  // namespace (see HttpPluginServlet#isApiCall, which requires parts.get(1).startsWith("gerrit~")).
  // The built-in plugin-management views (enable/disable/reload/status) belong to the "gerrit" core
  // plugin, so their routable segment is gerrit~<view>, not the bare <view> a normal collection
  // view
  // would emit.
  private static final String PLUGIN_KIND = "PLUGIN_KIND";
  private static final String CORE_PLUGIN_VIEW_PREFIX = "gerrit~";

  /** A module's bindings paired with the class they were read from (for simple-name resolution). */
  public record ModuleBindings(List<Binding> bindings, ClassInfo module) {}

  /**
   * The kind graph (Pass 1) is seeded from {@code contextEdges} -- other modules' {@code child()}
   * bindings whose endpoints are NOT emitted here. This lets a plugin document that hangs off a
   * core kind (e.g. {@code BRANCH_KIND}) resolve that kind's parent chain (project -> branch) from
   * the core module, without emitting core endpoints into the document.
   *
   * <p>{@code pluginName}, when non-empty, prefixes the plugin's own view segments that attach to a
   * <em>core</em> resource kind (one the emit module does not define) with {@code pluginName + "~"}
   * -- the explicit, always-unambiguous REST form Gerrit routes (e.g. {@code
   * /config/server/webhooks~projects/...}). Segments nested under the plugin's own kinds, and the
   * core all-domain run (empty {@code pluginName}), are unchanged.
   */
  public PathAssembler(
      List<Binding> bindings,
      ClassInfo module,
      SourceIndex index,
      ViewAnalyzer viewAnalyzer,
      KindIndex kindIndex,
      PathRootResolver rootResolver,
      List<ModuleBindings> contextEdges,
      String pluginName) {
    this.pluginName = pluginName;
    // Pass 1: child(PARENT, "seg").to(Collection) edges -> the kind graph. Context modules seed it
    // first (graph only, no endpoints from them); the emit module's edges apply last, staying
    // authoritative for the kinds it defines. With no context modules (the core all-domain run)
    // this is exactly the emit module's own edges, unchanged. Only the emit module's child kinds
    // are recorded as "owned" (for the <plugin>~ decision).
    for (ModuleBindings ctx : contextEdges) {
      addChildEdges(ctx.bindings(), ctx.module(), index, viewAnalyzer, kindIndex, null);
    }
    addChildEdges(bindings, module, index, viewAnalyzer, kindIndex, emitOwnedKinds);
    // The emit module also owns the top-level kinds it registers via DynamicMap.mapOf(binder(), K)
    // (e.g. a plugin's own servlet-mounted collection), which are never a child() target -- so the
    // <plugin>~ decision below treats them as the plugin's own, not core.
    emitOwnedKinds.addAll(ModuleKinds.mapOfOwned(module));
    // Pass 2: resolve the absolute mount of each top-level kind of the emitted bindings once. A
    // kind
    // with multiple parents has multiple chains (and thus multiple tops), so resolve each.
    for (Binding b : bindings) {
      for (List<String> chain : kindChains(b.kind())) {
        roots.computeIfAbsent(chain.get(0), rootResolver::resolveRoot);
      }
    }
  }

  private void addChildEdges(
      List<Binding> bindings,
      ClassInfo module,
      SourceIndex index,
      ViewAnalyzer viewAnalyzer,
      KindIndex kindIndex,
      @Nullable Set<String> ownedSink) {
    for (Binding b : bindings) {
      if (b.verb().equals("child") && b.name() != null) {
        ClassInfo coll = index.resolveClass(b.viewSimpleName(), module);
        String childKind = coll != null ? childKindOf(coll, index, viewAnalyzer, kindIndex) : null;
        if (childKind != null) {
          childEdges
              .computeIfAbsent(childKind, k -> new LinkedHashSet<>())
              .add(new Edge(b.kind(), b.name()));
          if (ownedSink != null) {
            ownedSink.add(childKind);
          }
        }
      }
    }
  }

  /**
   * A view/child segment {@code seg} bound directly on {@code parentKind}: prefixed with {@code
   * pluginName + "~"} when a plugin name is set and the parent is a core kind (not one the emit
   * module defines) -- the plugin's entry point onto a core resource. Otherwise {@code seg}
   * unchanged.
   */
  private String namespaced(String seg, String parentKind) {
    return pluginName.isEmpty() || emitOwnedKinds.contains(parentKind)
        ? seg
        : pluginName + "~" + seg;
  }

  /**
   * All resolved paths for one binding -- one {@code {http_method + path fields}} map per parent
   * chain of its kind. A single-parent kind yields one; a multi-parent kind (files, reviewers)
   * yields one per parent, so its item views emit under every parent collection.
   */
  public List<Map<String, Object>> pathVariants(Binding b) {
    List<Map<String, Object>> variants = new ArrayList<>();
    for (List<String> kindChain : kindChains(b.kind())) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("http_method", httpMethod(b.verb()));
      fillPathFields(b, kindChain, row);
      variants.add(row);
    }
    return variants;
  }

  private void fillPathFields(Binding b, List<String> kindChain, Map<String, Object> row) {
    // postOnCollection/deleteOnCollection act on the *collection*, so the leaf resource id is not
    // part of the path; every other verb targets the resource identified by the leaf kind.
    boolean collectionOp =
        b.verb().equals("postOnCollection") || b.verb().equals("deleteOnCollection");
    List<String> segments = new ArrayList<>();
    for (int i = 1; i < kindChain.size(); i++) {
      String k = kindChain.get(i);
      segments.add(namespaced(segmentFor(k, kindChain.get(i - 1)), kindChain.get(i - 1)));
      boolean isLeaf = i == kindChain.size() - 1;
      if (!(isLeaf && collectionOp)) {
        segments.add("{" + AstUtil.idParam(k) + "}");
      }
    }
    // "/" is the DSL sentinel for the resource/self view, not a path segment.
    if (b.name() != null && !b.name().equals("/")) {
      String view = namespaced(b.name(), b.kind());
      segments.add(b.kind().equals(PLUGIN_KIND) ? CORE_PLUGIN_VIEW_PREFIX + view : view);
    }
    // Root mount. A collection op on the top kind drops the root's own id (POST /changes, not
    // /changes/{change-id}); item mount "/changes/{change-id}" and collection mount "/changes"
    // differ only by the trailing id segment.
    boolean rootCollectionOp = collectionOp && kindChain.size() == 1;
    String rel = String.join("/", segments);
    String itemMount = roots.get(kindChain.get(0));
    boolean resolved = itemMount != null;
    String root;
    if (resolved) {
      root = rootCollectionOp ? itemMount.substring(0, itemMount.lastIndexOf('/')) : itemMount;
    } else {
      root = "{" + kindChain.get(0) + (rootCollectionOp ? "-collection}" : "-root}");
    }
    row.put("path_template", root + (rel.isEmpty() ? "" : "/" + rel));
    row.put("resource_kind_chain", kindChain);
    row.put("path_confidence", resolved ? "high" : "medium");
    row.put("unresolved_path_reason", resolved ? null : "absolute mount of top kind not resolved");
  }

  /** For a ChildCollection, the child resource's KIND constant name (its 2nd type arg's kind). */
  @Nullable
  private static String childKindOf(
      ClassInfo coll, SourceIndex index, ViewAnalyzer viewAnalyzer, KindIndex kindIndex) {
    List<String> args = viewAnalyzer.analyze(coll).viewIfaces().get("ChildCollection");
    if (args == null || args.size() < 2) {
      return null;
    }
    // e.g. child resource TaskResource, AccountResource.Email
    ClassInfo res = index.resolveClass(args.get(1), coll);
    return res != null ? kindIndex.kindOfResource(res) : null;
  }

  /**
   * All root->leaf kind chains for {@code leafKind}: one per {@code child()} parent edge, expanded
   * transitively up to the roots. A single-parent kind yields one chain; a multi-parent kind yields
   * one per distinct ancestry. Cycles stop climbing (defensive; the kind graph is a forest).
   */
  private List<List<String>> kindChains(String leafKind) {
    List<List<String>> out = new ArrayList<>();
    extendChains(leafKind, new ArrayList<>(List.of(leafKind)), out);
    return out;
  }

  private void extendChains(String kind, List<String> partial, List<List<String>> out) {
    Set<Edge> edges = childEdges.get(kind);
    if (edges == null || edges.isEmpty()) {
      out.add(partial); // reached a root
      return;
    }
    for (Edge e : edges) {
      if (partial.contains(e.parentKind())) {
        out.add(partial); // cycle guard: treat the current top as the root
        continue;
      }
      List<String> next = new ArrayList<>();
      next.add(e.parentKind());
      next.addAll(partial);
      extendChains(e.parentKind(), next, out);
    }
  }

  /** The collection segment joining {@code parentKind} to its child {@code childKind}. */
  private String segmentFor(String childKind, String parentKind) {
    Set<Edge> edges = childEdges.get(childKind);
    if (edges != null) {
      for (Edge e : edges) {
        if (e.parentKind().equals(parentKind)) {
          return e.segment();
        }
      }
    }
    return childKind;
  }

  private static String httpMethod(String verb) {
    return switch (verb) {
      case "get", "child" -> "GET";
      case "put", "create" -> "PUT";
      case "post", "postOnCollection" -> "POST";
      case "delete", "deleteMissing", "deleteOnCollection" -> "DELETE";
      default -> verb.toUpperCase();
    };
  }
}
