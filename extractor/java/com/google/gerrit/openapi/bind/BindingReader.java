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

package com.google.gerrit.openapi.bind;

import com.google.gerrit.openapi.dto.Binding;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import java.util.List;

/**
 * Reads the REST DSL bindings from a module's {@code configure()} body: each {@code <verb>(kind[,
 * "name"]).to(View.class)} becomes a {@link Binding}. Only {@code configure()} is scanned (nested
 * helper classes/methods are not), per the design model.
 */
public final class BindingReader {
  private static final List<String> VERBS =
      List.of(
          "get",
          "put",
          "post",
          "delete",
          "child",
          "create",
          "deleteMissing",
          "postOnCollection",
          "deleteOnCollection");

  private BindingReader() {}

  public static List<Binding> read(ClassInfo module) {
    List<Binding> result = new java.util.ArrayList<>();
    MethodTree configure = null;
    for (Tree member : module.tree.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("configure")) {
        configure = m;
        break;
      }
    }
    if (configure == null) {
      return result;
    }
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        // Match `<verb>(...).to(View.class)`. The verb call is unqualified, so its method select is
        // an IdentifierTree (e.g. "get"); `.to` is the MemberSelect on top of it.
        String verb =
            node.getMethodSelect() instanceof MemberSelectTree sel
                    && sel.getIdentifier().contentEquals("to")
                    && sel.getExpression() instanceof MethodInvocationTree recv0
                ? AstUtil.lastIdent(recv0.getMethodSelect())
                : null;
        if (verb != null && VERBS.contains(verb) && !node.getArguments().isEmpty()) {
          MethodInvocationTree recv =
              (MethodInvocationTree) ((MemberSelectTree) node.getMethodSelect()).getExpression();
          List<? extends ExpressionTree> vargs = recv.getArguments();
          String kind = vargs.isEmpty() ? null : AstUtil.lastIdent(vargs.get(0));
          String name = vargs.size() >= 2 ? AstUtil.stringLiteral(vargs.get(1)) : null;
          String viewSimpleName = AstUtil.classLiteralType(node.getArguments().get(0));
          if (viewSimpleName != null) {
            result.add(new Binding(verb, kind, name, viewSimpleName));
          }
        }
        return super.visitMethodInvocation(node, p);
      }
    }.scan(configure, null);
    return result;
  }
}
