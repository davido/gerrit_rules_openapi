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
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import javax.lang.model.element.Modifier;

/**
 * Infers the media types a modify endpoint's request body can take, from its input type's shape —
 * exactly the branches {@code RestApiServlet.parseRequest()} takes on the input {@code Type}:
 *
 * <ul>
 *   <li>no request-bearing interface (read/GET/child) → {@code none};
 *   <li>any JSON field → {@code json} (the JSON content-type branch runs first, so {@code json} is
 *       accepted even for a raw-capable input);
 *   <li>a {@code RawInput} field on <b>PUT/POST</b> → {@code raw}; because the raw branch returns
 *       <b>before</b> the text/plain and form branches, such an input does <b>not</b> also accept
 *       {@code form} or {@code text};
 *   <li>otherwise (raw branch not active): a {@code @DefaultInput String} field, or a {@code
 *       String} input → {@code text}; and a JSON body on POST → {@code form} ({@code
 *       ParameterParser.formToJson});
 *   <li>an input with no fields (the empty {@code Input} marker) → {@code none}.
 * </ul>
 *
 * <p>Parse-only. {@code raw}/{@code text} are keyed on the concrete input's <b>declared</b> fields
 * (matching the servlet, whose {@code acceptsRawInput()} / {@code parseString()} use {@code
 * getDeclaredFields()}); {@code json}/{@code none} walk the <b>{@code extends} chain</b> (Gson
 * serializes inherited fields too). When the input type cannot be resolved in the manifest, the
 * servlet's default (JSON parsing) is reported as {@code json} and the caller flags the row; {@code
 * raw}/{@code text} on such an input cannot be detected.
 *
 * <p>{@code RawInput} / {@code @DefaultInput} are matched by <b>simple name</b> — fine for core,
 * where both are Gerrit-owned; hardening to FQN-strict (as {@link AnnotationReader} does for
 * {@code @Option}) would be safer before plugin reuse.
 */
public final class RequestRepresentationInferencer {
  private final BiFunction<String, ClassInfo, ClassInfo> resolveClass;

  public RequestRepresentationInferencer(BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    this.resolveClass = resolveClass;
  }

  /**
   * The accepted request media types, sorted. {@code input} is the resolved input class (the caller
   * resolves the request-type name against the right context — the class that declares the
   * request-bearing interface, which may be a superclass) or null when it could not be resolved.
   */
  public List<String> infer(
      @Nullable String requestType, String httpMethod, @Nullable ClassInfo input) {
    if (requestType == null) {
      return List.of("none"); // read view / no request body
    }
    if (requestType.equals("String")) {
      return List.of("text");
    }
    if (input == null || input.tree == null) {
      return List.of("json"); // servlet default for an unresolved (non-raw) type
    }

    // raw / text are keyed on the concrete class's DECLARED fields — matching the servlet, whose
    // acceptsRawInput() / parseString() both use getDeclaredFields() on the concrete input type.
    boolean rawCapable = false;
    boolean hasDefaultInputString = false;
    for (Tree member : input.tree.getMembers()) {
      if (!(member instanceof VariableTree f) || isStatic(f)) {
        continue;
      }
      if ("RawInput".equals(AstUtil.lastIdent(f.getType()))) {
        rawCapable = true;
      } else if (hasDefaultInput(f) && "String".equals(AstUtil.lastIdent(f.getType()))) {
        hasDefaultInputString = true;
      }
    }
    // json/none is keyed on the whole hierarchy — Gson serializes inherited fields too, so an input
    // that declares no fields but inherits some (e.g. extends InputWithCommitMessage) is still
    // json.
    boolean hasJsonField = hasJsonFieldInHierarchy(input);

    // parseRequest() precedence: the JSON content-type branch runs first (json is accepted whenever
    // there are JSON fields); then, on PUT/POST only, the raw branch — which returns BEFORE the
    // text/plain and form branches. So a raw-capable PUT/POST input is parsed as raw and does not
    // also accept form or text.
    boolean isPutOrPost = "PUT".equals(httpMethod) || "POST".equals(httpMethod);
    boolean rawActive = rawCapable && isPutOrPost;

    Set<String> reps = new TreeSet<>();
    if (rawActive) {
      reps.add("raw");
    }
    if (hasJsonField) {
      reps.add("json");
    }
    if (!rawActive) {
      if (hasJsonField && "POST".equals(httpMethod)) {
        reps.add("form");
      }
      if (hasDefaultInputString) {
        reps.add("text"); // text/plain maps to the @DefaultInput field
      }
    }
    if (reps.isEmpty()) {
      reps.add("none"); // empty Input marker: no meaningful body
    }
    return new ArrayList<>(reps);
  }

  /**
   * Whether the input, or any superclass, declares a non-static field that isn't {@code RawInput}.
   */
  private boolean hasJsonFieldInHierarchy(ClassInfo input) {
    for (ClassInfo cur : AstUtil.superclassChain(input, resolveClass)) {
      if (cur.tree == null) {
        continue;
      }
      for (Tree member : cur.tree.getMembers()) {
        if (member instanceof VariableTree f
            && !isStatic(f)
            && !"RawInput".equals(AstUtil.lastIdent(f.getType()))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isStatic(VariableTree f) {
    return f.getModifiers().getFlags().contains(Modifier.STATIC);
  }

  private static boolean hasDefaultInput(VariableTree f) {
    for (AnnotationTree ann : f.getModifiers().getAnnotations()) {
      if ("DefaultInput".equals(AstUtil.lastIdent(ann.getAnnotationType()))) {
        return true;
      }
    }
    return false;
  }
}
