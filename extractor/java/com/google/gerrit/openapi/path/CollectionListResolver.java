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
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Resolves a REST collection's {@code list()} method to the concrete list view it dispatches to, so
 * a {@code child(...)} (collection-list) endpoint can be modeled by its real list view — its query
 * params, response, and status — instead of the collection class. The list view is bound by Guice
 * at runtime (a {@code Provider<X>} or covariant return), so this is a conservative static read of
 * {@code list()}, not a general dataflow analysis.
 *
 * <p>Recognized shapes (Codex's boundary — do not guess). Shape 0 is on the method signature;
 * shapes 1-3 are the {@code return} expressions in a {@code RestView<P> list()} body, decoded by
 * {@link #viewFromReturn}:
 *
 * <ol start="0">
 *   <li><b>Covariant concrete return type</b> — {@code public QueryChanges list()}: the declared
 *       return type is the view (see {@link #covariantReturnName}).
 *   <li><b>{@code return provider.get();}</b> — field {@code Provider<ListBranches>}: the type
 *       argument is the view.
 *   <li><b>{@code return name;}</b> — a bare field, or a local ({@code Foo x = ...; return x;}):
 *       its declared type is the view (an interface is fine; {@link #toScannable} maps it to the
 *       {@code @Option}-bearing impl).
 *   <li><b>{@code return new ListBranches(...);}</b> — the instantiated type.
 * </ol>
 *
 * Anything else — no {@code list()}, or a body shape not listed — yields no view. {@link #resolve}
 * additionally declines <em>more than one</em> return (conditional dispatch, e.g. {@code
 * ProjectsCollection} choosing query-vs-list) with an explicit {@link Result#unsupportedReason};
 * {@link #resolveAllViews} instead returns every branch so their options can be unioned.
 */
public final class CollectionListResolver {
  private final BiFunction<String, ClassInfo, ClassInfo> resolveClass;
  private final UnaryOperator<ClassInfo> toScannable;

  public CollectionListResolver(BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    this(resolveClass, UnaryOperator.identity());
  }

  /**
   * @param toScannable maps a resolved view to the class whose annotations should be read -- the
   *     identity for a concrete view, or an {@code @Option}-bearing implementor when the view
   *     resolves to an interface (e.g. {@code ListProjects} -> {@code AbstractListProjects}).
   */
  public CollectionListResolver(
      BiFunction<String, ClassInfo, ClassInfo> resolveClass, UnaryOperator<ClassInfo> toScannable) {
    this.resolveClass = resolveClass;
    this.toScannable = toScannable;
  }

  /**
   * Every list view a {@code list()} can dispatch to. Unlike {@link #resolve} (which yields one
   * view for the response/status shape and gives up on conditional dispatch), this returns
   * <em>all</em> statically-resolvable branches -- so a collection whose {@code list()} chooses
   * between a query view and a list view (e.g. {@code ProjectsCollection}, {@code
   * GroupsCollection}) contributes the union of both views' query options. Each view is mapped
   * through {@link #toScannable}.
   */
  public List<ClassInfo> resolveAllViews(ClassInfo collection) {
    List<ClassInfo> out = new ArrayList<>();
    if (collection.tree == null) {
      return out;
    }
    MethodTree list = findListMethod(collection.tree);
    if (list == null) {
      return out;
    }
    String covariant = covariantReturnName(list); // shape 0
    if (covariant != null) {
      addView(out, covariant, collection);
      return out;
    }
    for (ReturnTree ret : returnsInBody(list)) {
      String viewName = viewFromReturn(ret.getExpression(), collection, list);
      if (viewName != null) {
        addView(out, viewName, collection);
      }
    }
    return out;
  }

  private void addView(List<ClassInfo> out, String name, ClassInfo ctx) {
    ClassInfo v = resolveClass.apply(name, ctx);
    if (v != null) {
      ClassInfo scannable = toScannable.apply(v);
      if (scannable != null && !out.contains(scannable)) {
        out.add(scannable);
      }
    }
  }

  /** The resolved list view, or the reason it could not be resolved. Exactly one is non-null. */
  public record Result(@Nullable ClassInfo view, @Nullable String unsupportedReason) {
    static Result of(ClassInfo view) {
      return new Result(view, null);
    }

    static Result unsupported(String reason) {
      return new Result(null, reason);
    }
  }

  public Result resolve(ClassInfo collection) {
    if (collection.tree == null) {
      return Result.unsupported("collection " + collection.fqcn + " not in manifest");
    }
    MethodTree list = findListMethod(collection.tree);
    if (list == null) {
      return Result.unsupported("no list() method on " + collection.fqcn);
    }

    // Shape 0 -- covariant concrete return type (e.g. QueryChanges): the declared return type is
    // the list view.
    String covariant = covariantReturnName(list);
    if (covariant != null) {
      return resolveView(covariant, collection, "list() return type");
    }

    // RestView<P> return: inspect the (single) return expression.
    List<ReturnTree> returns = returnsInBody(list);
    if (returns.isEmpty()) {
      return Result.unsupported("list() has no return statement (throws, or listing unsupported)");
    }
    if (returns.size() > 1) {
      return Result.unsupported(
          "list() has " + returns.size() + " return statements (conditional/dynamic dispatch)");
    }
    String viewName = viewFromReturn(returns.get(0).getExpression(), collection, list);
    if (viewName == null) {
      return Result.unsupported("list() return expression is not a statically resolvable view");
    }
    return resolveView(viewName, collection, "list view");
  }

  private Result resolveView(String simpleName, ClassInfo ctx, String what) {
    ClassInfo view = resolveClass.apply(simpleName, ctx);
    return view != null
        ? Result.of(view)
        : Result.unsupported(what + " " + simpleName + " not in manifest");
  }

  @Nullable
  private static MethodTree findListMethod(ClassTree ct) {
    for (Tree member : ct.getMembers()) {
      if (member instanceof MethodTree m
          && m.getName().contentEquals("list")
          && m.getParameters().isEmpty()) {
        return m;
      }
    }
    return null;
  }

  /**
   * Shape 0: if {@code list()}'s declared return type is the concrete view itself (a covariant
   * override, not the generic {@code RestView<P>}), its name -- by full dotted name where available
   * (handles fully-qualified / cross-package / nested types), else the simple name. Null otherwise.
   */
  @Nullable
  private static String covariantReturnName(MethodTree list) {
    String simple = AstUtil.lastIdent(list.getReturnType());
    if (simple == null || simple.equals("RestView")) {
      return null;
    }
    String dotted = AstUtil.dottedName(list.getReturnType());
    return dotted != null ? dotted : simple;
  }

  /**
   * The view named by a single {@code return} expression -- shapes 1-3 in the class javadoc: {@code
   * provider.get()}, a bare field/local name, or {@code new View(...)}. Null for any other shape.
   */
  @Nullable
  private String viewFromReturn(
      @Nullable ExpressionTree expr, ClassInfo collection, MethodTree method) {
    // Shape 1 -- provider.get(): the Provider<View> field's type argument is the view.
    String providerField = providerGetField(expr);
    if (providerField != null) {
      return typeArgOf(fieldType(collection, providerField));
    }
    // Shape 2 -- a bare name: a field's declared type, or a local variable's. An interface is fine;
    // toScannable maps it to the @Option-bearing impl.
    if (expr instanceof IdentifierTree id) {
      Tree type = fieldOrLocalType(collection, method, id.getName().toString());
      return type == null ? null : AstUtil.dottedName(type);
    }
    // Shape 3 -- new View(...): the instantiated type.
    if (expr instanceof NewClassTree created) {
      return AstUtil.dottedName(created.getIdentifier());
    }
    return null;
  }

  /** If {@code expr} is {@code field.get()} (no arguments), the field's simple name; else null. */
  @Nullable
  private static String providerGetField(@Nullable ExpressionTree expr) {
    return expr instanceof MethodInvocationTree call
            && call.getArguments().isEmpty()
            && call.getMethodSelect() instanceof MemberSelectTree sel
            && sel.getIdentifier().contentEquals("get")
            && sel.getExpression() instanceof IdentifierTree receiver
        ? receiver.getName().toString()
        : null;
  }

  /**
   * The last type argument of a parameterized field type ({@code Provider<View>} → View), or null.
   */
  @Nullable
  private static String typeArgOf(@Nullable Tree fieldType) {
    Tree lastArg = lastTypeArgTree(fieldType);
    return lastArg == null ? null : AstUtil.dottedName(lastArg);
  }

  /** The declared type behind a bare-name return: a field of {@code collection}, else a local. */
  @Nullable
  private Tree fieldOrLocalType(ClassInfo collection, MethodTree method, String name) {
    Tree fieldType = fieldType(collection, name);
    return fieldType != null ? fieldType : localVarType(method, name);
  }

  /**
   * The declared type of a top-level local variable {@code name} in {@code method}'s body, or null.
   * Only method-level locals are considered -- a name declared inside a nested block would be
   * missed, but no {@code list()} does that, and the {@code ListOptionCoverage} guard fails the
   * build loudly if one ever appears.
   */
  @Nullable
  private static Tree localVarType(MethodTree method, String name) {
    if (method.getBody() == null) {
      return null;
    }
    for (StatementTree st : method.getBody().getStatements()) {
      if (st instanceof VariableTree v && v.getName().contentEquals(name)) {
        return v.getType();
      }
    }
    return null;
  }

  @Nullable
  private static Tree fieldType(ClassInfo collection, String fieldName) {
    for (Tree member : collection.tree.getMembers()) {
      if (member instanceof VariableTree v && v.getName().contentEquals(fieldName)) {
        return v.getType();
      }
    }
    return null;
  }

  /**
   * The last type-argument subtree of a parameterized type (e.g. {@code ListBranches}), or null.
   */
  @Nullable
  private static Tree lastTypeArgTree(@Nullable Tree parameterized) {
    if (parameterized instanceof com.sun.source.tree.ParameterizedTypeTree pt
        && !pt.getTypeArguments().isEmpty()) {
      return pt.getTypeArguments().get(pt.getTypeArguments().size() - 1);
    }
    return null;
  }

  /** Return statements directly in the method body — not those inside nested classes or lambdas. */
  private static List<ReturnTree> returnsInBody(MethodTree method) {
    List<ReturnTree> out = new ArrayList<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitClass(ClassTree node, Void p) {
        return null; // do not descend into nested/anonymous classes
      }

      @Override
      public Void visitLambdaExpression(LambdaExpressionTree node, Void p) {
        return null; // a lambda's return is not the method's
      }

      @Override
      public Void visitReturn(ReturnTree node, Void p) {
        out.add(node);
        return super.visitReturn(node, p);
      }
    }.scan(method.getBody(), null);
    return out;
  }
}
