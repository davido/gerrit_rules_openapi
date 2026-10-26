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

package com.google.gerrit.openapi.parse;

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Small, stateless helpers over the javac Compiler Tree API, shared by the OpenAPI inventory tools
 * and {@code PathRootResolver}. Kept separate so components (e.g. the path-root resolver) don't
 * have to reach back into another tool for these primitives.
 */
public final class AstUtil {
  private AstUtil() {}

  /**
   * A class and its superclasses in {@code extends} order, self first. Each super name is resolved
   * in the subclass's own context via {@code resolveClass} (an inherited type is imported by the
   * declaring superclass, not the leaf). The walk stops at the first class that is unresolved,
   * tree-less, or already visited, so it is cycle-safe. Use for the common "look up the hierarchy"
   * loops; a walk that also substitutes type parameters (e.g. {@code ViewAnalyzer}, {@code
   * SchemaGenerator}) needs the {@code extends} arguments per edge and does not fit this shape.
   */
  public static List<ClassInfo> superclassChain(
      @Nullable ClassInfo start, BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    List<ClassInfo> chain = new ArrayList<>();
    Set<ClassInfo> seen = new HashSet<>();
    ClassInfo cur = start;
    while (cur != null && seen.add(cur)) {
      chain.add(cur);
      if (cur.tree == null) {
        break;
      }
      String superName = lastIdent(cur.tree.getExtendsClause());
      cur = superName == null ? null : resolveClass.apply(superName, cur);
    }
    return chain;
  }

  /** Last identifier of a (possibly qualified or parameterized) type expression. */
  @Nullable
  public static String lastIdent(@Nullable Tree t) {
    if (t == null) {
      return null;
    }
    if (t instanceof MemberSelectTree ms) {
      return ms.getIdentifier().toString();
    }
    if (t instanceof ParameterizedTypeTree pt) {
      return lastIdent(pt.getType());
    }
    String s = t.toString();
    int dot = s.lastIndexOf('.');
    return dot >= 0 ? s.substring(dot + 1) : s;
  }

  /** Full dotted name of a type expression (e.g. {@code ChangeEdits.Get}), or null. */
  @Nullable
  public static String dottedName(@Nullable Tree t) {
    if (t == null) {
      return null;
    }
    if (t instanceof MemberSelectTree ms) {
      String base = dottedName(ms.getExpression());
      return base == null ? ms.getIdentifier().toString() : base + "." + ms.getIdentifier();
    }
    if (t instanceof ParameterizedTypeTree pt) {
      return dottedName(pt.getType());
    }
    return t.toString();
  }

  /** The unquoted value of a string-literal tree, or null for a non-literal expression. */
  @Nullable
  public static String stringLiteral(Tree t) {
    String s = t.toString();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      return s.substring(1, s.length() - 1);
    }
    return null;
  }

  /** From {@code View.class}, the (possibly nested) View type name. */
  @Nullable
  public static String classLiteralType(Tree t) {
    if (t instanceof MemberSelectTree ms && ms.getIdentifier().contentEquals("class")) {
      return dottedName(ms.getExpression()); // preserve Outer.Inner from Outer.Inner.class
    }
    return null;
  }

  /** Type-argument strings of a parameterized type, e.g. {@code [R, I]} for {@code Foo<R, I>}. */
  public static List<String> typeArgs(Tree parameterized) {
    if (parameterized instanceof ParameterizedTypeTree pt) {
      List<String> out = new ArrayList<>();
      for (Tree a : pt.getTypeArguments()) {
        out.add(a.toString());
      }
      return out;
    }
    return List.of();
  }

  /** Declared type-parameter names of a class, e.g. {@code [P, C, I]}. */
  public static List<String> typeParamNames(@Nullable ClassTree ct) {
    if (ct == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    ct.getTypeParameters().forEach(tp -> out.add(tp.getName().toString()));
    return out;
  }

  /** Path-parameter name for a resource kind, e.g. {@code CONFIG_KIND -> "config-id"}. */
  public static String idParam(String kind) {
    String base = kind.endsWith("_KIND") ? kind.substring(0, kind.length() - 5) : kind;
    return base.toLowerCase().replace('_', '-') + "-id";
  }
}
