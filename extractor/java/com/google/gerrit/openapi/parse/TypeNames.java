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

/**
 * Canonical {@code java.util}/Guava type-name predicates shared by the schema generator, the
 * OpenAPI emitter, and the args4j query-param scanner.
 *
 * <p>Each predicate accepts either a full type string ({@code List<Foo>}, {@code Foo[]}, a
 * package-qualified name) or an already-extracted base name; {@link #base} normalizes both. The
 * collection set is the union of what the three call sites previously recognized (interfaces, the
 * Guava immutables, the common concrete implementations, and {@code Iterable}) so they can no
 * longer disagree about whether a type maps to a JSON array.
 */
public final class TypeNames {
  private TypeNames() {}

  /** The raw type name: strips a trailing {@code []}, any {@code <...>}, and the package. */
  public static String base(String type) {
    String head = type;
    if (head.endsWith("[]")) {
      head = head.substring(0, head.length() - 2);
    }
    int lt = head.indexOf('<');
    if (lt >= 0) {
      head = head.substring(0, lt);
    }
    int dot = head.lastIndexOf('.');
    if (dot >= 0) {
      head = head.substring(dot + 1);
    }
    return head.strip();
  }

  /**
   * A collection (interface, Guava immutable, concrete impl, or array) that maps to a JSON array.
   */
  public static boolean isCollection(String type) {
    if (type.endsWith("[]")) {
      return true;
    }
    return switch (base(type)) {
      case "List", "ArrayList", "LinkedList" -> true;
      case "Set", "HashSet", "LinkedHashSet", "SortedSet", "NavigableSet", "TreeSet", "EnumSet" ->
          true;
      case "Collection", "Iterable" -> true;
      case "ImmutableList", "ImmutableSet", "ImmutableCollection", "ImmutableSortedSet" -> true;
      default -> false;
    };
  }

  /** A map interface that maps to a JSON object with {@code additionalProperties}. */
  public static boolean isMap(String type) {
    return switch (base(type)) {
      case "Map", "SortedMap", "NavigableMap", "ImmutableMap", "ImmutableSortedMap" -> true;
      default -> false;
    };
  }

  /** A map interface or a concrete implementation a DTO might {@code extends}. */
  public static boolean isMapType(String type) {
    if (isMap(type)) {
      return true;
    }
    return switch (base(type)) {
      case "TreeMap", "HashMap", "LinkedHashMap", "ConcurrentHashMap", "ConcurrentSkipListMap" ->
          true;
      default -> false;
    };
  }
}
