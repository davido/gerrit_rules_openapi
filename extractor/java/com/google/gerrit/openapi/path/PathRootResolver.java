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
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the absolute URL mount of a top-level REST kind (e.g. {@code CONFIG_KIND ->
 * "/config/server"}). This is not stated anywhere in the REST module; it is assembled from FOUR
 * independent sources, which is why it lives in its own class rather than inline. For {@code
 * CONFIG_KIND}:
 *
 * <ol>
 *   <li><b>resource class</b> — {@code CONFIG_KIND} is a static field of {@code ConfigResource}.
 *   <li><b>root collection</b> — {@code ConfigCollection implements
 *       RestCollection<TopLevelResource, ConfigResource>}: the collection mounted at the servlet
 *       root.
 *   <li><b>servlet + prefix</b> — {@code ConfigRestApiServlet}'s constructor takes {@code
 *       Provider<ConfigCollection>}, and {@code UrlModule} maps {@code
 *       serveRegex("^/(?:a/)?config/(.*)$")} to that servlet, yielding the URL prefix {@code
 *       "config"}.
 *   <li><b>root segment</b> — {@code ConfigCollection.parse()} compares the id to the literal
 *       {@code "server"}, so the singleton resource is mounted at that segment.
 * </ol>
 *
 * <p>=&gt; {@code "/" + prefix + "/" + segment = "/config/server"}.
 *
 * <p>Design decisions / limits:
 *
 * <ul>
 *   <li><b>Singleton vs. id root.</b> When {@code parse()} matches a fixed string literal (config's
 *       {@code "server"}) the segment is that literal; an "open" root collection
 *       (changes/projects/accounts, which accept any id) has no literal, so the segment becomes an
 *       id parameter {@code "{<kind>-id}"}.
 *   <li><b>Best-effort.</b> Any hop that cannot be resolved statically (collection/servlet/prefix
 *       not in the manifest, a non-literal {@code parse()}) yields {@code null}; the caller then
 *       keeps the relative {@code "{KIND-root}/..."} form with medium confidence rather than
 *       guessing an absolute path.
 * </ul>
 */
public final class PathRootResolver {
  /**
   * Absolute mounts the resolver cannot discover by static analysis: they are served by a dedicated
   * servlet at a fixed path rather than declared as a {@code serveRegex(...).with(...)} literal
   * that {@link #urlPrefixFor}'s AST scan can match, so they need an explicit entry here. Currently
   * only the plugins collection: {@code /plugins/} is served by {@code HttpPluginServlet} (bound in
   * {@code HttpPluginModule}, not {@code UrlModule} — see UrlModule's note "The '/plugins/' root
   * collection is already handled by HttpPluginServlet which we cannot bind here again"). Without
   * this entry the plugin-management endpoints (list/install/enable/disable/reload/status) fall
   * back to a {@code {PLUGIN_KIND-*}} placeholder path that no client generator can consume. Keyed
   * by root-collection simple name.
   */
  private static final Map<String, String> UNDISCOVERABLE_MOUNTS =
      Map.of("PluginsCollection", "plugins");

  private final Map<String, ClassInfo> byFqcn;
  private final BiFunction<String, ClassInfo, ClassInfo> resolveClass;
  private final KindIndex kindIndex;
  private final String pluginHttpPrefix; // plugin install name, or "" for a core run

  public PathRootResolver(
      Map<String, ClassInfo> byFqcn,
      BiFunction<String, ClassInfo, ClassInfo> resolveClass,
      KindIndex kindIndex,
      String pluginHttpPrefix) {
    this.byFqcn = byFqcn;
    this.resolveClass = resolveClass;
    this.kindIndex = kindIndex;
    this.pluginHttpPrefix = pluginHttpPrefix;
  }

  /**
   * Resolve the absolute mount of a top-level kind by walking resource -&gt; root collection -&gt;
   * servlet+prefix -&gt; root segment. Returns null if any hop cannot be resolved statically.
   */
  @Nullable
  String resolveRoot(String topKind) {
    ClassInfo resource = kindIndex.resourceOf(topKind);
    if (resource == null) {
      return null;
    }
    ClassInfo rootColl = findRootCollection(resource);
    if (rootColl == null) {
      return null;
    }
    String prefix = urlPrefixFor(rootColl);
    if (prefix == null) {
      return null;
    }
    String segment = parseSegmentLiteral(rootColl);
    if (segment == null) {
      segment = "{" + AstUtil.idParam(topKind) + "}"; // open collection: any id
    }
    return "/" + prefix + "/" + segment;
  }

  /** The root collection ({@code RestCollection<TopLevelResource, R>}) for a top-level kind. */
  @Nullable
  ClassInfo rootCollectionOf(String topKind) {
    ClassInfo resource = kindIndex.resourceOf(topKind);
    return resource == null ? null : findRootCollection(resource);
  }

  /**
   * Collection-listing path for a root collection ({@code "/changes"}) — the servlet prefix without
   * the resource segment. Null if the prefix can't be resolved. Used to place the root-list
   * endpoint ({@code GET /changes}) as opposed to a resource ({@code GET /changes/{id}}).
   */
  @Nullable
  String collectionListPath(ClassInfo rootColl) {
    String prefix = urlPrefixFor(rootColl);
    if (prefix == null) {
      return null;
    }
    // HttpPluginServlet routes the plugins listing only at the exact "/plugins/" (its mapping
    // "^/(?:a/)?plugins/(.*)?$" makes the slash mandatory, and unlike core collections there is no
    // companion bare-form binding in UrlModule) -- "/plugins" is a 404. Core collections are bound
    // both ways and tolerate the missing slash, and their override keys in openapi-overrides.json
    // are keyed on the bare form, so leave those untouched. Only the undiscoverable
    // (plugin-servlet)
    // mounts get the explicit trailing slash.
    boolean pluginServletMount = UNDISCOVERABLE_MOUNTS.containsKey(simpleOf(rootColl.fqcn));
    return "/" + prefix + (pluginServletMount ? "/" : "");
  }

  /**
   * Whether the root collection is a <em>singleton</em> root (its {@code parse()} matches a fixed
   * literal, e.g. config's {@code "server"}). A singleton root has no collection-listing endpoint;
   * an open root (changes/groups/projects, any id) does.
   *
   * <p>Checked on the mounted (possibly interface) collection, not {@link #listSource}. Correct for
   * current core — only {@code ConfigCollection} has a literal {@code parse()}, and no interface-
   * bound singleton root exists. An interface-bound singleton, whose {@code parse()} body lives on
   * the implementation, would be misread as open; harden this before plugin reuse.
   */
  boolean isSingletonRoot(ClassInfo rootColl) {
    return parseSegmentLiteral(rootColl) != null;
  }

  /**
   * The concrete source of {@code list()} for a root collection. An interface-bound root (accounts:
   * {@code interface AccountsCollection} + {@code AccountsCollectionImpl}) mounts its servlet
   * prefix on the interface, but its {@code list()} and query params live on the implementation —
   * so list resolution must use the impl. Returns the collection itself when it is already a
   * concrete class.
   */
  ClassInfo listSource(ClassInfo rootColl) {
    if (rootColl.tree != null && rootColl.tree.getKind() == Tree.Kind.INTERFACE) {
      ClassInfo impl = implementationOf(rootColl);
      if (impl != null) {
        return impl;
      }
    }
    return rootColl;
  }

  /**
   * A concrete class in the manifest that implements {@code iface} (first match by simple name).
   * Safe for current core, where a root interface has exactly one implementation. For plugin reuse
   * this should instead derive the impl from the module binding ({@code
   * bind(Iface.class).to(Impl.class)}) or resolve the implements clause to the actual {@link
   * ClassInfo}, rather than matching on {@code lastIdent()}.
   */
  @Nullable
  private ClassInfo implementationOf(ClassInfo iface) {
    String ifaceSimple = simpleOf(iface.fqcn);
    for (ClassInfo ci : byFqcn.values()) {
      if (ci.tree != null && ci.tree.getKind() == Tree.Kind.CLASS) {
        for (Tree impl : ci.tree.getImplementsClause()) {
          if (ifaceSimple.equals(AstUtil.lastIdent(impl))) {
            return ci;
          }
        }
      }
    }
    return null;
  }

  /** The class implementing {@code RestCollection<TopLevelResource, <resource>>}. */
  @Nullable
  private ClassInfo findRootCollection(ClassInfo resource) {
    for (ClassInfo ci : byFqcn.values()) {
      List<String> args = implementsArgs(ci, "RestCollection");
      if (args.size() == 2
          && args.get(0).equals("TopLevelResource")
          && resolveClass.apply(args.get(1), ci) == resource) {
        return ci;
      }
    }
    return null;
  }

  /**
   * The URL prefix ("config") for the servlet that mounts {@code rootColl}: find the {@code
   * *RestApiServlet} whose constructor takes {@code Provider<rootColl>}, then the {@code
   * serveRegex(...).with(thatServlet.class)} literal in the module that mounts it -- core's {@code
   * UrlModule} or a plugin's {@code HttpModule} (e.g. checks mounts its {@code /checkers} servlet
   * that way), so a plugin's own top-level collection resolves as well.
   */
  @Nullable
  private String urlPrefixFor(ClassInfo rootColl) {
    String collSimple = simpleOf(rootColl.fqcn);
    String fixed = UNDISCOVERABLE_MOUNTS.get(collSimple);
    if (fixed != null) {
      return fixed; // served by a dedicated servlet at a fixed path; not statically discoverable
    }
    ClassInfo servlet = null;
    for (ClassInfo ci : byFqcn.values()) {
      if (ci.tree != null && constructorTakesProviderOf(ci, collSimple)) {
        servlet = ci;
        break;
      }
    }
    if (servlet == null) {
      return null;
    }
    String servletSimple = simpleOf(servlet.fqcn);
    for (ClassInfo ci : byFqcn.values()) {
      if (ci.tree == null || !(ci.fqcn.endsWith("UrlModule") || ci.fqcn.endsWith("HttpModule"))) {
        continue;
      }
      String[] prefix = {null};
      new TreeScanner<Void, Void>() {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
          // serveRegex("^/(?:a/)?config/(.*)$").with(ConfigRestApiServlet.class)
          if (node.getMethodSelect() instanceof MemberSelectTree sel
              && sel.getIdentifier().contentEquals("with")
              && sel.getExpression() instanceof MethodInvocationTree recv
              && "serveRegex".equals(AstUtil.lastIdent(recv.getMethodSelect()))
              && !recv.getArguments().isEmpty()
              && servletSimple.equals(AstUtil.classLiteralType(node.getArguments().get(0)))) {
            String p2 = extractPrefix(AstUtil.stringLiteral(recv.getArguments().get(0)));
            if (p2 != null) {
              prefix[0] = p2;
            }
          }
          return super.visitMethodInvocation(node, p);
        }
      }.scan(ci.tree, null);
      if (prefix[0] != null) {
        // A plugin's own top-level servlet is bound in its Gerrit-HttpModule and served under
        // /plugins/<name>/ (added by HttpPluginServlet, not stated in the serveRegex); a core
        // mount (UrlModule) is at the site root. So a plugin build (pluginHttpPrefix set) prefixes
        // only its HttpModule-resolved mounts -- e.g. checks' /checkers -> /plugins/checks/checkers
        // -- while endpoints grafted onto core kinds (/changes/.../checks) stay unprefixed.
        if (!pluginHttpPrefix.isEmpty() && ci.fqcn.endsWith("HttpModule")) {
          return "plugins/" + pluginHttpPrefix + "/" + prefix[0];
        }
        return prefix[0];
      }
    }
    return null;
  }

  /** Does a constructor of {@code ci} declare a parameter of type {@code Provider<coll>}? */
  private static boolean constructorTakesProviderOf(ClassInfo ci, String collSimple) {
    for (Tree member : ci.tree.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("<init>")) {
        for (VariableTree param : m.getParameters()) {
          if (param.getType().toString().equals("Provider<" + collSimple + ">")) {
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * The first string literal a collection's {@code parse()} compares against, e.g. "server".
   *
   * <p>Heuristic: this treats a single {@code equals("literal")} as a singleton segment. A parse()
   * with two or more distinct string-equals is alias/multi-branch matching (e.g. {@code
   * equals("self")} before an id lookup), not a singleton, so it returns null rather than
   * misclassify the root and suppress its list endpoint.
   */
  @Nullable
  private static String parseSegmentLiteral(ClassInfo coll) {
    if (coll.tree == null) {
      return null;
    }
    for (Tree member : coll.tree.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("parse")) {
        String[] lit = {null};
        boolean[] ambiguous = {false};
        new TreeScanner<Void, Void>() {
          @Override
          public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
            if ("equals".equals(AstUtil.lastIdent(node.getMethodSelect()))
                && !node.getArguments().isEmpty()) {
              String s = AstUtil.stringLiteral(node.getArguments().get(0));
              if (s != null) {
                if (lit[0] == null) {
                  lit[0] = s;
                } else if (!lit[0].equals(s)) {
                  ambiguous[0] = true;
                }
              }
            }
            return super.visitMethodInvocation(node, p);
          }
        }.scan(m, null);
        // Only a single, unambiguous literal marks a singleton root. Multiple distinct
        // string-equals in parse() indicate alias/multi-branch matching (e.g. "self"/"me"
        // before an id lookup), which is not a singleton -- classifying it as one would
        // suppress that root's list (GET /collection) endpoint.
        return ambiguous[0] ? null : lit[0];
      }
    }
    return null;
  }

  /**
   * Extract the URL prefix from a serveRegex pattern: the first path segment after {@code ^/}, past
   * an optional {@code (?:a/)?} auth-prefix group. Handles core's {@code ^/(?:a/)?config/(.*)$}
   * ("config") and a plugin's {@code ^/checkers/(.*)$} ("checkers"), which has no {@code (?:a/)?}
   * group.
   *
   * <p>This is intentionally permissive (it also matches non-collection patterns like {@code
   * ^/settings$}). That is safe because it is reached only after the caller has matched a {@code
   * serveRegex(...).with(<servlet>.class)} whose servlet is the {@code *RestApiServlet} for a root
   * collection — the correctness guard is that servlet-name match, not this regex.
   */
  @Nullable
  private static String extractPrefix(@Nullable String regex) {
    if (regex == null) {
      return null;
    }
    Matcher m = Pattern.compile("^\\^?/(?:\\(\\?:a/\\)\\?)?([A-Za-z0-9_.-]+)").matcher(regex);
    return m.find() ? m.group(1) : null;
  }

  /** Type arguments of the given interface simple name on {@code ci}'s implements clause. */
  private static List<String> implementsArgs(ClassInfo ci, String ifaceSimple) {
    if (ci.tree == null) {
      return List.of();
    }
    for (Tree impl : ci.tree.getImplementsClause()) {
      if (ifaceSimple.equals(AstUtil.lastIdent(impl))) {
        return AstUtil.typeArgs(impl);
      }
    }
    return List.of();
  }

  private static String simpleOf(String fqcn) {
    int dot = fqcn.lastIndexOf('.');
    return dot >= 0 ? fqcn.substring(dot + 1) : fqcn;
  }
}
