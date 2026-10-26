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
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * The association between a resource {@code *_KIND} constant and its resource class, in both
 * directions. Shared by the child-graph builder ({@code PathAssembler.childKindOf}, which needs
 * resource → kind) and {@link PathRootResolver} (which needs kind → resource).
 *
 * <p>The association comes from the constant's <em>type</em>, not from where it is declared. A KIND
 * is declared as {@code TypeLiteral<RestView<X>>}, where {@code X} is the resource — and that
 * declaration may sit on an outer class while {@code X} is a nested resource. For example {@code
 * AccountResource} declares {@code TypeLiteral<RestView<Email>> EMAIL_KIND} for the nested resource
 * {@code AccountResource.Email}. Reading {@code X} from the type gets this right; scanning the
 * child class for a {@code *_KIND} field (the previous approach) would miss it and drop the child's
 * parent path.
 */
public final class KindIndex {
  private final Map<String, ClassInfo> kindToResource = new LinkedHashMap<>();
  private final Map<ClassInfo, String> resourceToKind = new LinkedHashMap<>();

  public KindIndex(
      Map<String, ClassInfo> byFqcn, BiFunction<String, ClassInfo, ClassInfo> resolveClass) {
    for (ClassInfo ci : byFqcn.values()) {
      if (ci.tree == null) {
        continue;
      }
      for (Tree member : ci.tree.getMembers()) {
        if (member instanceof VariableTree v && v.getName().toString().endsWith("_KIND")) {
          String kind = v.getName().toString();
          String resName = resourceTypeOf(v.getType());
          ClassInfo res = resName != null ? resolveClass.apply(resName, ci) : null;
          if (res != null) {
            kindToResource.putIfAbsent(kind, res);
            resourceToKind.putIfAbsent(res, kind);
          }
        }
      }
    }
  }

  /** The resource class a top-level kind maps to (for absolute-root resolution). */
  @Nullable
  ClassInfo resourceOf(String kind) {
    return kindToResource.get(kind);
  }

  /** The KIND constant name for a resource class (for child-graph assembly). */
  @Nullable
  String kindOfResource(ClassInfo resource) {
    return resourceToKind.get(resource);
  }

  /** From a KIND field type {@code TypeLiteral<RestView<X>>}, the resource type name {@code X}. */
  @Nullable
  private static String resourceTypeOf(Tree fieldType) {
    if (fieldType instanceof ParameterizedTypeTree p1
        && !p1.getTypeArguments().isEmpty()
        && p1.getTypeArguments().get(0) instanceof ParameterizedTypeTree p2
        && !p2.getTypeArguments().isEmpty()) {
      return p2.getTypeArguments().get(0).toString();
    }
    return null;
  }
}
