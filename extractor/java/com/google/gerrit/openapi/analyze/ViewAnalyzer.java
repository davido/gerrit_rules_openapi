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
import com.google.gerrit.openapi.dto.ViewFacts;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Interprets a view class into immutable {@link ViewFacts}: which {@code RestView} interfaces it
 * carries (directly or inherited from a superclass, with generic substitution), its request type,
 * and its {@code apply()} for response inference. Cached per class. Keeping this out of {@code
 * RestEndpoints} — and returning facts rather than mutating {@link ClassInfo} — is why {@code
 * ClassInfo} can stay a pure parse model.
 */
public final class ViewAnalyzer {
  private static final List<String> REST_IFACES =
      List.of(
          "RestReadView",
          "RestModifyView",
          "RestCollectionModifyView",
          "RestCollectionCreateView",
          "RestCollectionDeleteMissingView",
          "ChildCollection");

  private final BiFunction<String, ClassInfo, ClassInfo> resolveClass;
  private final Map<ClassInfo, ViewFacts> cache = new HashMap<>();
  private final Set<ClassInfo> inProgress = new HashSet<>();

  public ViewAnalyzer(BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    this.resolveClass = resolveClass;
  }

  public ViewFacts analyze(ClassInfo ci) {
    ViewFacts cached = cache.get(ci);
    if (cached != null) {
      return cached;
    }
    if (ci.tree == null || !inProgress.add(ci)) {
      return ViewFacts.EMPTY;
    }
    Map<String, List<String>> viewIfaces = new LinkedHashMap<>();
    String applyReturn = null;
    MethodTree applyMethod = null;
    Set<String> unresolvedTypeVars = new HashSet<>();

    for (Tree impl : ci.tree.getImplementsClause()) {
      String raw = AstUtil.lastIdent(impl);
      if (raw != null && REST_IFACES.contains(raw)) {
        viewIfaces.put(raw, AstUtil.typeArgs(impl));
      }
    }
    for (Tree member : ci.tree.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("apply")) {
        String ret = m.getReturnType() == null ? null : m.getReturnType().toString();
        if (ret != null && ret.startsWith("Response")) {
          applyReturn = ret;
          applyMethod = m;
          break;
        }
      }
    }
    // Inherit from the superclass if this class carries no interface / no apply() of its own,
    // substituting the superclass's declared type parameters with the concrete args from this
    // class's extends clause (e.g. AbstractPostCollection<..,TBatchInput> + extends
    // AbstractPostCollection<..,BatchLabelInput> => TBatchInput -> BatchLabelInput).
    Tree ext = ci.tree.getExtendsClause();
    String superName = AstUtil.lastIdent(ext);
    if (superName != null && (viewIfaces.isEmpty() || applyReturn == null)) {
      ClassInfo sup = resolveClass.apply(superName, ci);
      if (sup != null && sup != ci) {
        ViewFacts supFacts = analyze(sup);
        List<String> supParams = AstUtil.typeParamNames(sup.tree);
        List<String> extArgs = AstUtil.typeArgs(ext);
        Map<String, String> subst = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(supParams.size(), extArgs.size()); i++) {
          subst.put(supParams.get(i), extArgs.get(i));
        }
        if (viewIfaces.isEmpty()) {
          for (Map.Entry<String, List<String>> e : supFacts.viewIfaces().entrySet()) {
            List<String> args = new ArrayList<>();
            for (String a : e.getValue()) {
              String s = subst.getOrDefault(a, a);
              args.add(s);
              if (supParams.contains(s)) {
                unresolvedTypeVars.add(s); // stayed a superclass type variable
              }
            }
            viewIfaces.put(e.getKey(), args);
          }
        }
        if (applyReturn == null) {
          applyReturn = supFacts.applyReturn();
          applyMethod = supFacts.applyMethod();
        }
      }
    }
    ViewFacts facts = new ViewFacts(viewIfaces, applyReturn, applyMethod, unresolvedTypeVars);
    inProgress.remove(ci);
    cache.put(ci, facts);
    return facts;
  }

  /**
   * Request type from the request-bearing interface, or null. GET/child bindings carry no body, so
   * request inference is verb-aware.
   */
  @Nullable
  public String requestType(ViewFacts facts, String verb) {
    if (verb.equals("get") || verb.equals("child")) {
      return null;
    }
    List<String> modify = facts.viewIfaces().get("RestModifyView");
    if (modify != null && modify.size() >= 2) {
      return modify.get(1);
    }
    for (String iface :
        List.of(
            "RestCollectionModifyView",
            "RestCollectionCreateView",
            "RestCollectionDeleteMissingView")) {
      List<String> a = facts.viewIfaces().get(iface);
      if (a != null && a.size() >= 3) {
        return a.get(2);
      }
    }
    return null;
  }
}
