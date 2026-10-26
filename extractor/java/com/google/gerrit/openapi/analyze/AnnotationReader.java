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
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.NewArrayTree;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recognizes the args4j annotations the query-param scanner cares about and reads their members.
 * Stateless helpers over the javac Tree API, like {@link AstUtil}.
 *
 * <p>Matching is <b>strict on the FQN</b>, not just the simple name: the annotation must be a
 * fully-qualified use of, or a single-type import of, the real {@code org.kohsuke.args4j.Option} /
 * {@code Argument}. A same-named type from another package — imported or same-package — is
 * rejected. (Core Gerrit uses explicit args4j imports everywhere; no REST view relies on a wildcard
 * args4j import, so requiring the import loses nothing.)
 */
final class AnnotationReader {
  private AnnotationReader() {}

  static final String OPTION_FQN = "org.kohsuke.args4j.Option";
  static final String ARGUMENT_FQN = "org.kohsuke.args4j.Argument";

  /** {@code "option"} / {@code "argument"} for an args4j param annotation, else null. */
  @Nullable
  static String optionKind(AnnotationTree ann, ClassInfo ctx) {
    String simple = AstUtil.lastIdent(ann.getAnnotationType());
    if ("Option".equals(simple) && resolvesTo(ann, ctx, OPTION_FQN)) {
      return "option";
    }
    if ("Argument".equals(simple) && resolvesTo(ann, ctx, ARGUMENT_FQN)) {
      return "argument";
    }
    return null;
  }

  /**
   * Named annotation members as {@code member -> value expression} (e.g. {@code name -> "--x"}).
   */
  static Map<String, ExpressionTree> members(AnnotationTree ann) {
    Map<String, ExpressionTree> out = new LinkedHashMap<>();
    for (ExpressionTree arg : ann.getArguments()) {
      if (arg instanceof AssignmentTree a) {
        out.put(a.getVariable().toString(), a.getExpression());
      }
    }
    return out;
  }

  /** String value of a member, from a string literal (else null). */
  @Nullable
  static String stringValue(@Nullable ExpressionTree e) {
    if (e instanceof LiteralTree lit && lit.getValue() instanceof String s) {
      return s;
    }
    return e == null ? null : AstUtil.stringLiteral(e);
  }

  /** String values of an array member ({@code {"-a", "-b"}}), or a lone value, or empty. */
  static List<String> stringArray(@Nullable ExpressionTree e) {
    if (e == null) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    if (e instanceof NewArrayTree arr) {
      for (ExpressionTree el : arr.getInitializers()) {
        String s = stringValue(el);
        if (s != null) {
          out.add(s);
        }
      }
    } else {
      String s = stringValue(e);
      if (s != null) {
        out.add(s);
      }
    }
    return out;
  }

  /** Boolean value of a member, true only for the literal {@code true} (default false). */
  static boolean boolValue(@Nullable ExpressionTree e) {
    return e instanceof LiteralTree lit && Boolean.TRUE.equals(lit.getValue());
  }

  /**
   * True only when the annotation resolves to exactly {@code fqn}: a fully-qualified use matches
   * directly, otherwise the simple name must be a single-type import of {@code fqn}. An unimported
   * or foreign-package name is rejected.
   *
   * <p>Assumption: the annotation is reached through a single-type import (or written fully
   * qualified). A wildcard {@code import org.kohsuke.args4j.*;} is <em>not</em> recognized — core
   * Gerrit never uses one, and we deliberately do not carry on-demand-import state to cover
   * hypothetical custom plugins. See {@code Documentation/dev-openapi-inventory.md} §10.
   */
  private static boolean resolvesTo(AnnotationTree ann, ClassInfo ctx, String fqn) {
    String dotted = AstUtil.dottedName(ann.getAnnotationType());
    if (dotted != null && dotted.indexOf('.') >= 0) {
      return dotted.equals(fqn);
    }
    return fqn.equals(ctx.imports.get(AstUtil.lastIdent(ann.getAnnotationType())));
  }
}
