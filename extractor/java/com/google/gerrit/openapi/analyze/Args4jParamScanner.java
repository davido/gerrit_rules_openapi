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
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.google.gerrit.openapi.parse.TypeNames;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Scans a view's fields and {@code set*}/{@code add*} methods for args4j options, walking the
 * superclass chain (args4j's {@code ClassParser} does the same; Gerrit declares some options on an
 * abstract base, e.g. {@code AbstractListProjects}). Options are deduped by {@code (kind,
 * effectiveName)} <b>subclass-first</b>, so an override wins over the inherited declaration.
 *
 * <p>Annotation recognition and member reading live in {@link AnnotationReader}; this class owns
 * the member-shape decisions:
 *
 * <ul>
 *   <li><b>repeated</b> — a {@code List}/{@code Set}/{@code Collection}/array-typed target, or an
 *       {@code add*} setter (Gerrit's convention for a multi-valued option); a plain {@code set*}
 *       is single-valued.
 *   <li><b>type</b> — the field's declared type, or a setter's single parameter type, verbatim.
 * </ul>
 *
 * Returns the deduped params in discovery order; the {@link QueryParamExtractor} facade sorts them.
 */
final class Args4jParamScanner {
  private final BiFunction<String, ClassInfo, ClassInfo> resolveClass;

  Args4jParamScanner(BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    this.resolveClass = resolveClass;
  }

  /** Options declared on {@code view} or inherited from a superclass, deduped, unsorted. */
  List<QueryParam> scan(ClassInfo view) {
    Map<String, QueryParam> byKey = new LinkedHashMap<>();
    Set<ClassInfo> visited = new HashSet<>();
    ClassInfo cur = view;
    while (cur != null && cur.tree != null && visited.add(cur)) {
      collectInto(cur, cur == view ? "self" : simpleName(cur), byKey);
      String superName = AstUtil.lastIdent(cur.tree.getExtendsClause());
      cur = superName == null ? null : resolveClass.apply(superName, cur);
    }
    return new ArrayList<>(byKey.values());
  }

  private void collectInto(ClassInfo ci, String declaredIn, Map<String, QueryParam> byKey) {
    for (Tree member : ci.tree.getMembers()) {
      if (member instanceof VariableTree field) {
        putIfAbsent(
            byKey, fromMember(field.getModifiers().getAnnotations(), field, declaredIn, ci));
      } else if (member instanceof MethodTree method) {
        putIfAbsent(
            byKey, fromMember(method.getModifiers().getAnnotations(), method, declaredIn, ci));
      }
    }
  }

  private static void putIfAbsent(Map<String, QueryParam> byKey, @Nullable QueryParam qp) {
    if (qp != null) {
      byKey.putIfAbsent(qp.kind() + "|" + effectiveName(qp), qp);
    }
  }

  /** Builds a {@link QueryParam} for a field or setter carrying an args4j option, else null. */
  @Nullable
  private static QueryParam fromMember(
      List<? extends AnnotationTree> annotations, Tree member, String declaredIn, ClassInfo ctx) {
    for (AnnotationTree ann : annotations) {
      String kind = AnnotationReader.optionKind(ann, ctx);
      if (kind == null) {
        continue;
      }
      Map<String, ExpressionTree> args = AnnotationReader.members(ann);

      String targetType;
      boolean repeatedTarget;
      if (member instanceof VariableTree field) {
        targetType = field.getType().toString();
        repeatedTarget = TypeNames.isCollection(targetType);
      } else {
        MethodTree method = (MethodTree) member;
        targetType =
            method.getParameters().size() == 1
                ? method.getParameters().get(0).getType().toString()
                : "void";
        // A multi-valued option is bound through an add* method (addProject, addCommit, ...).
        repeatedTarget =
            method.getName().toString().startsWith("add") || TypeNames.isCollection(targetType);
      }

      String name = AnnotationReader.stringValue(args.get("name"));
      List<String> aliases = AnnotationReader.stringArray(args.get("aliases"));
      boolean required = AnnotationReader.boolValue(args.get("required"));
      // @Argument uses multiValued; @Option's repetition comes from its target shape.
      boolean repeated = repeatedTarget || AnnotationReader.boolValue(args.get("multiValued"));
      String metaVar = AnnotationReader.stringValue(args.get("metaVar"));
      String usage = AnnotationReader.stringValue(args.get("usage"));
      return new QueryParam(
          kind, name, aliases, targetType, required, repeated, metaVar, usage, declaredIn);
    }
    return null;
  }

  /** Whether a target type binds multiple values (a collection or array). */
  /** Stable identity for dedup/sort: the declared name, else the first alias, else {@code "?"}. */
  static String effectiveName(QueryParam qp) {
    if (qp.name() != null) {
      return qp.name();
    }
    return qp.aliases().isEmpty() ? "?" : qp.aliases().get(0);
  }

  private static String simpleName(ClassInfo ci) {
    int dot = ci.fqcn.lastIndexOf('.');
    return dot >= 0 ? ci.fqcn.substring(dot + 1) : ci.fqcn;
  }
}
