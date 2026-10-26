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
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreeScanner;
import java.util.Set;
import java.util.TreeSet;

/**
 * The resource kinds a REST module <em>owns</em> at the root: the {@code *_KIND} constants it
 * registers via {@code DynamicMap.mapOf(binder(), KIND)}. Scoped to the module's {@code
 * configure()} body (mirroring {@link com.google.gerrit.openapi.bind.BindingReader}) so ownership
 * tracks actual REST-module wiring, not incidental {@code mapOf} calls elsewhere in the class.
 *
 * <p>Shared by {@link RootListEndpointSynthesizer} (which synthesizes a root-list only for owned
 * open collections) and {@link PathAssembler} (which treats a plugin's own kinds as not-core when
 * deciding the {@code <plugin>~} segment form) so both read one definition of "owned".
 */
public final class ModuleKinds {
  private ModuleKinds() {}

  /** {@code *_KIND} constants the module registers via {@code DynamicMap.mapOf(binder(), KIND)}. */
  public static Set<String> mapOfOwned(ClassInfo module) {
    Set<String> kinds = new TreeSet<>(); // deterministic order
    if (module.tree == null) {
      return kinds;
    }
    MethodTree configure = null;
    for (Tree member : module.tree.getMembers()) {
      if (member instanceof MethodTree m && m.getName().contentEquals("configure")) {
        configure = m;
        break;
      }
    }
    if (configure == null) {
      return kinds;
    }
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if (methodName(node).equals("mapOf") && !node.getArguments().isEmpty()) {
          String kind = kindName(node.getArguments().get(node.getArguments().size() - 1));
          if (kind != null) {
            kinds.add(kind);
          }
        }
        return super.visitMethodInvocation(node, p);
      }
    }.scan(configure, null);
    return kinds;
  }

  private static String methodName(MethodInvocationTree node) {
    return switch (node.getMethodSelect()) {
      case MemberSelectTree m -> m.getIdentifier().toString();
      case IdentifierTree i -> i.getName().toString();
      default -> "";
    };
  }

  /** The trailing {@code *_KIND} simple name of a {@code mapOf} argument, or null. */
  @Nullable
  private static String kindName(ExpressionTree arg) {
    String name =
        switch (arg) {
          case MemberSelectTree m -> m.getIdentifier().toString();
          case IdentifierTree i -> i.getName().toString();
          default -> null;
        };
    return name != null && name.endsWith("_KIND") ? name : null;
  }
}
