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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.openapi.parse.AstUtil;
import com.google.gerrit.openapi.parse.SourceIndex;
import com.google.gerrit.openapi.parse.SourceIndex.ClassInfo;
import com.google.gerrit.openapi.parse.TypeNames;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.lang.model.element.Modifier;

/**
 * Generates OpenAPI schemas for Gerrit's request/response types (Input/Info classes) from the
 * parsed source, and accumulates the named object/enum schemas into a {@code components/schemas}
 * registry.
 *
 * <p>A type reference resolves to an inline schema for scalars, arrays ({@code List<X>} / {@code
 * X[]}), and maps ({@code Map<K,V>} → {@code additionalProperties}), or to a {@code $ref} for a
 * named Info/Input class or enum — whose schema is registered once (cycle-guarded) via {@link
 * #components()}. Field names follow Gerrit's Gson policy (lower_underscore); inherited fields are
 * included (superclass chain), subclass-wins on a name clash, with the superclass's type parameters
 * substituted from the {@code extends} arguments ({@code AbstractBatchInput<LabelDefinitionInput>}
 * → {@code T} = {@code LabelDefinitionInput}).
 *
 * <p>Named types resolve in the <em>declaring context</em> (the view class or the field's declaring
 * class) via {@code SourceIndex.resolveClass}, walking the superclass chain (an inherited request
 * type is imported by the superclass, not the bound view); a unique simple name resolves without a
 * context. A concrete type that still cannot be resolved is emitted as {@code {type: object}} with
 * an {@code x-gerrit-unresolved} marker, so it is visible rather than indistinguishable from a
 * genuinely freeform {@code Object}. Component names are the simple class name when unique among
 * the reachable schemas (see {@link #discover} / {@link #finalizeNames}), else the shortest unique
 * CamelCase qualifier built from the enclosing class/package segments (never a dotted name).
 *
 * <p>Enums emit {@code {type: string, enum: [...]}} with their constants; a Map subtype ({@code
 * extends TreeMap<K,V>}) becomes a free-form object with {@code additionalProperties}. Not yet
 * modeled (to sharpen later): {@code @SerializedName} and {@code @Nullable}/required.
 */
public final class SchemaGenerator {
  private final SourceIndex index;
  private final Map<String, String> uniqueSimpleToFqcn = new HashMap<>();
  private final Set<String> ambiguousSimple = new HashSet<>();
  private final Map<String, Object> components = new TreeMap<>();
  private final Set<String> building = new HashSet<>();
  // Schemas actually reached from the API (the emitted set), and the simple names that collide
  // *within that set*. Populated by the discovery pass; used for naming only after finalizeNames().
  private final Set<String> reachable = new HashSet<>();
  private final Map<String, String> finalNameByFqcn = new HashMap<>();

  /**
   * The schema-naming regime; see {@link #schemaName}. Advances DIRECT -> DISCOVERING -> FINALIZED.
   */
  private enum Phase {
    /** Direct API use with no discovery pass (e.g. unit tests): simple name, fqcn on collision. */
    DIRECT,
    /** Discovery pass: key every schema by fqcn so same-simple-name classes don't merge. */
    DISCOVERING,
    /** After discovery: precomputed minimal-qualifier names over the reachable set. */
    FINALIZED
  }

  private Phase phase = Phase.DIRECT;

  public SchemaGenerator(SourceIndex index) {
    this.index = index;
    for (String fqcn : index.byFqcn().keySet()) {
      String simple = simpleName(fqcn);
      if (ambiguousSimple.contains(simple)) {
        continue;
      }
      if (uniqueSimpleToFqcn.putIfAbsent(simple, fqcn) != null) {
        uniqueSimpleToFqcn.remove(simple);
        ambiguousSimple.add(simple);
      }
    }
  }

  /** The accumulated {@code components/schemas} map (schema name -> schema), sorted by name. */
  public Map<String, Object> components() {
    return components;
  }

  /**
   * Discovery pass: traverse the schema graph from an operation root type (request/response) to
   * record which schemas are actually reached, without committing to their final names. Call for
   * every operation root type, then {@link #finalizeNames()}, before the real emission. Mirrors
   * what a reflection-based generator observes (only reachable models), so a static parser does not
   * over-qualify names against classes that never become schemas.
   */
  public void discover(@Nullable String type, @Nullable String viewFqcn) {
    phase = Phase.DISCOVERING;
    schemaForView(type, viewFqcn);
  }

  /**
   * Freeze schema names once discovery is complete: a schema keeps its simple name unless two
   * *reachable* schemas collide on it, in which case each takes the shortest unique CamelCase
   * qualifier from its enclosing class/package. Clears the throwaway schemas built during discovery
   * so the real emission rebuilds them under final names.
   */
  public void finalizeNames() {
    // Group the reachable (emitted) schemas by simple name.
    Map<String, List<String>> bySimple = new TreeMap<>();
    for (String fqcn : reachable) {
      bySimple.computeIfAbsent(simpleName(fqcn), s -> new ArrayList<>()).add(fqcn);
    }
    Set<String> used = new HashSet<>();
    // A unique simple name wins the clean name.
    for (Map.Entry<String, List<String>> e : bySimple.entrySet()) {
      if (e.getValue().size() == 1) {
        finalNameByFqcn.put(e.getValue().get(0), e.getKey());
        used.add(e.getKey());
      }
    }
    // Genuine collisions get the shortest CamelCase qualifier (enclosing class / package segments)
    // that is unique -- never a dotted fully-qualified name.
    for (Map.Entry<String, List<String>> e : bySimple.entrySet()) {
      if (e.getValue().size() == 1) {
        continue;
      }
      List<String> group = new ArrayList<>(e.getValue());
      group.sort(null); // deterministic assignment for a diffable document
      for (String fqcn : group) {
        String[] seg = fqcn.split("\\.");
        String name = qualifier(seg, seg.length - 1); // full path, guaranteed-unique fallback
        for (int level = 1; level < seg.length; level++) {
          String cand = qualifier(seg, level);
          if (!used.contains(cand)) {
            name = cand;
            break;
          }
        }
        finalNameByFqcn.put(fqcn, name);
        used.add(name);
      }
    }
    components.clear();
    building.clear();
    phase = Phase.FINALIZED;
  }

  /**
   * Join the last {@code level+1} fqcn segments, capitalizing each: {@code
   * ...common.GpgKeyInfo.Status} at level 1 -> {@code GpgKeyInfoStatus}. Package segments
   * capitalize too ({@code common} -> {@code Common}) so top-level collisions disambiguate without
   * dots.
   */
  private static String qualifier(String[] seg, int level) {
    int take = Math.min(level + 1, seg.length);
    StringBuilder sb = new StringBuilder();
    for (int i = seg.length - take; i < seg.length; i++) {
      String s = seg[i];
      if (!s.isEmpty()) {
        sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1));
      }
    }
    return sb.toString();
  }

  /** Schema for a type reference resolved without a context (unique simple names / fqcns). */
  public Map<String, Object> schema(@Nullable String type) {
    return schema(type, (ClassInfo) null);
  }

  /** Schema for a type reference resolved in the context of the given view class (by fqcn). */
  public Map<String, Object> schemaForView(@Nullable String type, @Nullable String viewFqcn) {
    return schema(type, viewFqcn == null ? null : index.byFqcn().get(viewFqcn));
  }

  private Map<String, Object> schema(@Nullable String type, @Nullable ClassInfo ctx) {
    String t = type == null ? "" : type.strip();
    // A wildcard resolves to its bound: List<? extends GroupAuditEventInfo> -> the info type.
    if (t.startsWith("? extends ")) {
      t = t.substring("? extends ".length()).strip();
    } else if (t.startsWith("? super ")) {
      t = t.substring("? super ".length()).strip();
    }
    if (t.isEmpty() || t.equals("?")) {
      return object();
    }
    if (t.endsWith("[]")) {
      return array(schema(t.substring(0, t.length() - 2), ctx));
    }
    int lt = t.indexOf('<');
    if (lt >= 0 && t.endsWith(">")) {
      String base = simpleName(t.substring(0, lt).strip());
      List<String> args = typeArgs(t.substring(lt + 1, t.length() - 1));
      if (TypeNames.isCollection(base) && args.size() == 1) {
        return array(schema(args.get(0), ctx));
      }
      if (TypeNames.isMap(base) && args.size() == 2) {
        Map<String, Object> schema = object();
        schema.put("additionalProperties", schema(args.get(1), ctx));
        return schema;
      }
      if (base.equals("Optional") && args.size() == 1) {
        return schema(args.get(0), ctx);
      }
      return named(t.substring(0, lt).strip(), ctx); // other generic type: model the raw base
    }
    Map<String, Object> scalar = scalar(simpleName(t));
    return scalar != null ? scalar : named(t, ctx);
  }

  /**
   * Resolve a named type to a {@code $ref} (registering its schema) or a marked freeform object.
   */
  private Map<String, Object> named(String name, @Nullable ClassInfo ctx) {
    ClassInfo ci = resolveType(name, ctx);
    if (ci == null) {
      Map<String, Object> unresolved = object();
      unresolved.put("x-gerrit-unresolved", name); // concrete type we could not resolve; visible
      return unresolved;
    }
    reachable.add(ci.fqcn); // this type becomes a schema; record it for collision analysis
    String schemaName = schemaName(ci);
    if (ci.tree != null && ci.tree.getKind() == Tree.Kind.ENUM) {
      components.putIfAbsent(schemaName, enumSchema(ci));
    } else {
      registerObject(ci, schemaName);
    }
    Map<String, Object> ref = new LinkedHashMap<>();
    ref.put("$ref", "#/components/schemas/" + schemaName);
    return ref;
  }

  private void registerObject(ClassInfo ci, String schemaName) {
    if (components.containsKey(schemaName) || !building.add(schemaName)) {
      return; // already registered, or currently building (cycle)
    }
    Map<String, Object> schema = object();
    // A Map subtype (e.g. RepositoryStatistics extends TreeMap<String, Object>) is a free-form map,
    // not a fixed-field object; model its value type as additionalProperties.
    Tree ext = ci.tree.getExtendsClause();
    String extBase = ext == null ? null : simpleName(orEmpty(AstUtil.lastIdent(ext)));
    if (extBase != null && TypeNames.isMapType(extBase)) {
      List<String> args = AstUtil.typeArgs(ext);
      if (args.size() == 2) {
        schema.put("additionalProperties", schema(args.get(1), ci));
      }
      components.put(schemaName, schema);
      building.remove(schemaName);
      return;
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    schema.put("properties", properties);
    components.put(schemaName, schema); // register before recursing so cycles resolve to $ref
    collectFields(ci, properties);
    building.remove(schemaName);
  }

  /** A string-valued enum schema with its declared constants. */
  private Map<String, Object> enumSchema(ClassInfo ci) {
    Map<String, Object> schema = type("string");
    List<String> values = new ArrayList<>();
    String enumName = simpleName(ci.fqcn);
    for (Tree member : ci.tree.getMembers()) {
      // Enum constants parse as fields typed as the enum itself (RED, GREEN in `enum Color`).
      if (member instanceof VariableTree v && enumName.equals(AstUtil.lastIdent(v.getType()))) {
        values.add(v.getName().toString());
      }
    }
    if (!values.isEmpty()) {
      schema.put("enum", values);
    }
    return schema;
  }

  private static String orEmpty(@Nullable String s) {
    return s == null ? "" : s;
  }

  /**
   * Own + inherited serialized fields, lower_underscore keyed; subclass wins on a name clash. The
   * superclass's type parameters are substituted with the concrete {@code extends} arguments so a
   * generic field type ({@code List<T>}) resolves to the concrete element.
   */
  private void collectFields(ClassInfo ci, Map<String, Object> properties) {
    ClassInfo cur = ci;
    Map<String, String> subst =
        new LinkedHashMap<>(); // type var -> concrete type (fqcn if resolved)
    Set<ClassInfo> seen = new HashSet<>();
    while (cur != null && cur.tree != null && seen.add(cur)) {
      for (Tree member : cur.tree.getMembers()) {
        if (!(member instanceof VariableTree field)) {
          continue;
        }
        Set<Modifier> mods = field.getModifiers().getFlags();
        if (mods.contains(Modifier.STATIC) || mods.contains(Modifier.TRANSIENT)) {
          continue; // Gson serializes only non-static, non-transient fields
        }
        String fieldType = applySubst(field.getType().toString(), subst);
        properties.putIfAbsent(lowerUnderscore(field.getName().toString()), schema(fieldType, cur));
      }
      // Descend to the superclass, mapping its type parameters to this class's extends arguments
      // (each resolved to a concrete fqcn in the current context) so the substitution is stable.
      Tree ext = cur.tree.getExtendsClause();
      String superName = AstUtil.lastIdent(ext);
      ClassInfo sup = superName == null ? null : resolveType(superName, cur);
      if (sup == null || sup == cur) {
        break;
      }
      List<String> supParams = AstUtil.typeParamNames(sup.tree);
      List<String> extArgs = AstUtil.typeArgs(ext);
      Map<String, String> next = new LinkedHashMap<>();
      for (int i = 0; i < Math.min(supParams.size(), extArgs.size()); i++) {
        String arg = applySubst(extArgs.get(i), subst);
        ClassInfo argCi = resolveType(arg, cur);
        next.put(supParams.get(i), argCi != null ? argCi.fqcn : arg);
      }
      subst = next;
      cur = sup;
    }
  }

  /** Resolve a type name in a context, walking the superclass chain, then unique simple names. */
  @Nullable
  private ClassInfo resolveType(@Nullable String name, @Nullable ClassInfo ctx) {
    if (name == null) {
      return null;
    }
    ClassInfo direct = index.byFqcn().get(name);
    if (direct != null) {
      return direct;
    }
    ClassInfo cur = ctx;
    Set<ClassInfo> seen = new HashSet<>();
    while (cur != null && seen.add(cur)) {
      ClassInfo r = index.resolveClass(name, cur);
      if (r != null) {
        return r;
      }
      if (cur.tree == null) {
        break;
      }
      String superName = AstUtil.lastIdent(cur.tree.getExtendsClause());
      cur = superName == null ? null : index.resolveClass(superName, cur);
    }
    String fqcn = uniqueSimpleToFqcn.get(simpleName(name));
    return fqcn == null ? null : index.byFqcn().get(fqcn);
  }

  /** Simple class name when unique across the parsed set, else the fully qualified name. */
  private String schemaName(ClassInfo ci) {
    return switch (phase) {
      // Discovery pass: key every schema by its unique fqcn so two classes sharing a simple name
      // do not merge (which would truncate transitive discovery).
      case DISCOVERING -> ci.fqcn;
      // After discovery: the precomputed name (simple, or a minimal CamelCase qualifier for genuine
      // collisions among the reachable schemas -- never a dotted fully-qualified name).
      case FINALIZED -> finalNameByFqcn.getOrDefault(ci.fqcn, simpleName(ci.fqcn));
      // Direct API use without a discovery pass (e.g. unit tests): fall back to the parsed-universe
      // collision set computed in the constructor.
      case DIRECT -> {
        String simple = simpleName(ci.fqcn);
        yield ambiguousSimple.contains(simple) ? ci.fqcn : simple;
      }
    };
  }

  /** Replace whole-identifier type variables in a type string with their substitutions. */
  private static String applySubst(String type, Map<String, String> subst) {
    if (subst.isEmpty()) {
      return type;
    }
    String out = type;
    for (Map.Entry<String, String> e : subst.entrySet()) {
      out =
          out.replaceAll(
              "\\b" + e.getKey() + "\\b", java.util.regex.Matcher.quoteReplacement(e.getValue()));
    }
    return out;
  }

  @Nullable
  private static Map<String, Object> scalar(String name) {
    return switch (name) {
      case "boolean", "Boolean" -> type("boolean");
      case "byte", "short", "int", "long", "Byte", "Short", "Integer", "Long" -> type("integer");
      case "float", "double", "Float", "Double", "BigDecimal" -> type("number");
      case "char", "Character", "String", "CharSequence" -> type("string");
      // Gerrit serializes timestamps as "yyyy-MM-dd HH:mm:ss.SSSSSSSSS" (UTC, space-separated,
      // no zone) -- NOT RFC 3339. Emitting "date-time" makes conformant generators produce a
      // date type that fails to deserialize every response, so use a Gerrit-specific format that
      // standard generators fall back to plain string for.
      case "Timestamp", "Instant", "Date" -> typeFormat("string", "gerrit-timestamp");
      case "BinaryResult" -> typeFormat("string", "binary");
      case "Object" -> object();
      case "void", "Void" -> new LinkedHashMap<>(); // empty schema (no body)
      default -> null;
    };
  }

  /** Split top-level type arguments, respecting nested {@code <...>}. */
  private static List<String> typeArgs(String inside) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < inside.length(); i++) {
      char c = inside.charAt(i);
      if (c == '<') {
        depth++;
      } else if (c == '>') {
        depth--;
      } else if (c == ',' && depth == 0) {
        out.add(inside.substring(start, i).strip());
        start = i + 1;
      }
    }
    out.add(inside.substring(start).strip());
    return out;
  }

  private static String simpleName(String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 ? name.substring(dot + 1) : name;
  }

  /** camelCase -> lower_underscore, matching Gson's LOWER_CASE_WITH_UNDERSCORES policy. */
  private static String lowerUnderscore(String field) {
    StringBuilder sb = new StringBuilder(field.length() + 4);
    for (int i = 0; i < field.length(); i++) {
      char c = field.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          sb.append('_');
        }
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static Map<String, Object> object() {
    return type("object");
  }

  private static Map<String, Object> array(Map<String, Object> items) {
    Map<String, Object> schema = type("array");
    schema.put("items", items);
    return schema;
  }

  private static Map<String, Object> type(String type) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", type);
    return schema;
  }

  private static Map<String, Object> typeFormat(String type, String format) {
    Map<String, Object> schema = type(type);
    schema.put("format", format);
    return schema;
  }
}
