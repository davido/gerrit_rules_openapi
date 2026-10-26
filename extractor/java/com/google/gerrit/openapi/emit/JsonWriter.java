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

package com.google.gerrit.openapi.emit;

import java.util.List;
import java.util.Map;

/**
 * Minimal deterministic JSON serialization shared by the OpenAPI tools: one report row (a {@code
 * Map<String, Object>} of scalars, booleans, nulls, string lists, and nested object lists) and
 * string escaping. Each tool keeps its own document wrapper (header fields + array key); this owns
 * the row + escaping, which is where the two tools would otherwise duplicate.
 */
public final class JsonWriter {
  private JsonWriter() {}

  /**
   * Append one object row: {@code {"k": "v", "b": true, "n": null, "list": ["a", "b"], "objs":
   * [{...}]}}. A list element that is itself a {@code Map} is serialized as a nested object (used
   * for {@code query_params}); every other element is emitted as a quoted string.
   */
  public static void writeObj(StringBuilder sb, Map<String, Object> obj) {
    sb.append('{');
    int i = 0;
    for (Map.Entry<String, Object> e : obj.entrySet()) {
      if (i++ > 0) {
        sb.append(", ");
      }
      sb.append('"').append(esc(e.getKey())).append("\": ");
      writeValue(sb, e.getValue());
    }
    sb.append('}');
  }

  /** Append any JSON value (object, array, string, number, boolean, or null). */
  public static void writeValue(StringBuilder sb, Object v) {
    if (v == null) {
      sb.append("null");
    } else if (v instanceof Boolean || v instanceof Number) {
      sb.append(v); // unquoted: booleans and numbers (Long/Double) are JSON literals
    } else if (v instanceof Map<?, ?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> obj = (Map<String, Object>) map;
      writeObj(sb, obj);
    } else if (v instanceof List<?> list) {
      sb.append('[');
      for (int j = 0; j < list.size(); j++) {
        if (j > 0) {
          sb.append(", ");
        }
        writeValue(sb, list.get(j)); // recurse so numbers/booleans in arrays stay literals
      }
      sb.append(']');
    } else {
      sb.append('"').append(esc(String.valueOf(v))).append('"');
    }
  }

  /** JSON-escape a string (quotes, backslash, control chars). */
  public static String esc(String s) {
    StringBuilder b = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.toString();
  }
}
