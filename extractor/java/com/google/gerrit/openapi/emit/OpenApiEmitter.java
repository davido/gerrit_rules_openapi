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
import com.google.gerrit.openapi.parse.TypeNames;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Projects the endpoint inventory rows into a minimal OpenAPI 3.1 document.
 *
 * <p>Paths, operations, path + query parameters, request media types, and response codes all come
 * from the same rows the inventory report is built from, so the projection stays in lock-step with
 * the extractor. Request/response payload schemas are produced by a {@link SchemaGenerator} (from
 * the parsed Input/Info classes) when one is supplied to {@link #emit(String, String, List,
 * SchemaGenerator)}; without one, payloads fall back to a {@code {"type": "object"}} placeholder.
 *
 * <p>Two distinct bindings can share a method + path (e.g. {@code create} vs {@code put} on a
 * collection). OpenAPI keys an operation by path + method, so they are <em>merged</em> into one
 * operation — union of response codes, request media types, and query parameters — and the source
 * bindings are preserved under the {@code x-gerrit-bindings} extension so nothing is silently lost.
 */
public final class OpenApiEmitter {
  private OpenApiEmitter() {}

  private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]+)\\}");

  /** HTTP verbs in a stable emission order; anything else sorts after, alphabetically. */
  private static final List<String> VERB_ORDER = List.of("get", "put", "post", "delete");

  /** request_representation token -> request media type. "none" (no body) is absent by design. */
  private static final Map<String, String> REQUEST_MEDIA =
      new LinkedHashMap<>(
          Map.of(
              "json", "application/json",
              "form", "application/x-www-form-urlencoded",
              "text", "text/plain",
              "raw", "application/octet-stream"));

  /** Deterministic media order regardless of the inferencer's list order. */
  private static final List<String> REP_ORDER = List.of("json", "form", "text", "raw");

  private static final Map<String, String> REASON =
      Map.of(
          "200", "OK",
          "201", "Created",
          "202", "Accepted",
          "204", "No Content",
          "302", "Found");

  /** Build the OpenAPI 3.1 document with placeholder payload schemas (no schema generation). */
  public static String emit(String title, String version, List<Map<String, Object>> rows) {
    return emit(title, version, rows, null, Overrides.empty(), false);
  }

  /** Build the OpenAPI 3.1 document with generated schemas but no overrides. */
  public static String emit(
      String title,
      String version,
      List<Map<String, Object>> rows,
      @Nullable SchemaGenerator schemas) {
    return emit(title, version, rows, schemas, Overrides.empty(), false);
  }

  /**
   * Build the OpenAPI 3.1 document (paths + operations, {@code components/schemas} when {@code
   * schemas} is given) from the inventory rows, with the checked-in {@code overrides} applied last.
   */
  public static String emit(
      String title,
      String version,
      List<Map<String, Object>> rows,
      @Nullable SchemaGenerator schemas,
      Overrides overrides,
      boolean allowUnusedOverrides) {
    // path -> method -> the rows that share that (path, method); TreeMap keeps paths sorted for a
    // stable, diffable document. Rows sharing a (path, method) are merged into one operation.
    Map<String, Map<String, List<Map<String, Object>>>> byPath = new TreeMap<>();
    for (Map<String, Object> row : rows) {
      String path = normalizePath((String) row.get("path_template"));
      String method = ((String) row.get("http_method")).toLowerCase(Locale.ROOT);
      byPath
          .computeIfAbsent(path, p -> new LinkedHashMap<>())
          .computeIfAbsent(method, m -> new ArrayList<>())
          .add(row);
    }

    StringBuilder sb = new StringBuilder();
    sb.append("{\n  \"openapi\": \"3.1.0\",\n  \"info\": ");
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("title", title);
    info.put("version", version);
    info.putAll(overrides.info());
    JsonWriter.writeObj(sb, info);

    // servers (authored) come before paths per OpenAPI convention.
    if (!overrides.servers().isEmpty()) {
      sb.append(",\n  \"servers\": ");
      JsonWriter.writeValue(sb, overrides.servers());
    }

    // Discovery pass: learn which schemas are actually reachable from the API before naming them,
    // so
    // a simple name is qualified only when two *emitted* schemas collide. A static parser otherwise
    // sees the whole parsed class universe and over-qualifies; this mirrors what reflection-based
    // generators observe (only reachable models).
    if (schemas != null) {
      for (Map<String, Object> row : rows) {
        schemas.discover((String) row.get("request_type"), (String) row.get("view_fqcn"));
        schemas.discover((String) row.get("response_type"), (String) row.get("view_fqcn"));
      }
      schemas.finalizeNames();
    }

    sb.append(",\n  \"paths\": {\n");
    Set<String> generatedKeys = new LinkedHashSet<>();
    int i = 0;
    for (Map.Entry<String, Map<String, List<Map<String, Object>>>> e : byPath.entrySet()) {
      Map<String, Object> methods = new LinkedHashMap<>();
      for (Map.Entry<String, List<Map<String, Object>>> me : e.getValue().entrySet()) {
        generatedKeys.add(me.getKey() + " " + e.getKey());
        Map<String, Object> op = operation(e.getKey(), me.getValue(), schemas);
        methods.put(me.getKey(), overrides.apply(me.getKey(), e.getKey(), op));
      }
      sb.append("    \"").append(JsonWriter.esc(e.getKey())).append("\": ");
      JsonWriter.writeObj(sb, orderMethods(methods));
      sb.append(++i < byPath.size() ? ",\n" : "\n");
    }
    sb.append("  }");

    // A checked-in override that matches no generated operation (a typo, or an endpoint that was
    // removed) would silently do nothing — fail loudly so the sidecar can't rot. Exception:
    // per-domain slices apply the whole-API composed overrides to a subset of the endpoints, so
    // most operation overrides are legitimately unused; those set allowUnusedOverrides.
    List<String> unused = new ArrayList<>();
    for (String opKey : overrides.operationKeys()) {
      if (!generatedKeys.contains(opKey)) {
        unused.add(opKey);
      }
    }
    if (!allowUnusedOverrides && !unused.isEmpty()) {
      unused.sort(null);
      throw new IllegalArgumentException("overrides: no generated operation matches " + unused);
    }

    // components: generated schemas (one per line, diffable) + authored securitySchemes.
    Map<String, Object> comps = schemas == null ? Map.of() : schemas.components();
    // Land mined field descriptions + required-ness on the generated schemas (in place).
    overrides.applySchemaDescriptions(comps);
    overrides.applySchemaRequired(comps);
    Map<String, Object> securitySchemes = overrides.securitySchemes();
    if (!comps.isEmpty() || !securitySchemes.isEmpty()) {
      sb.append(",\n  \"components\": {\n");
      if (!comps.isEmpty()) {
        sb.append("    \"schemas\": {\n");
        int j = 0;
        for (Map.Entry<String, Object> e : comps.entrySet()) {
          @SuppressWarnings("unchecked")
          Map<String, Object> schema = (Map<String, Object>) e.getValue();
          sb.append("      \"").append(JsonWriter.esc(e.getKey())).append("\": ");
          JsonWriter.writeObj(sb, schema);
          sb.append(++j < comps.size() ? ",\n" : "\n");
        }
        sb.append(securitySchemes.isEmpty() ? "    }\n" : "    },\n");
      }
      if (!securitySchemes.isEmpty()) {
        sb.append("    \"securitySchemes\": ");
        JsonWriter.writeObj(sb, securitySchemes);
        sb.append("\n");
      }
      sb.append("  }");
    }

    // Global security requirement and tag definitions (authored), last.
    if (!overrides.security().isEmpty()) {
      sb.append(",\n  \"security\": ");
      JsonWriter.writeValue(sb, overrides.security());
    }
    if (!overrides.tags().isEmpty()) {
      sb.append(",\n  \"tags\": ");
      JsonWriter.writeValue(sb, overrides.tags());
    }
    sb.append("\n}\n");
    return sb.toString();
  }

  private static Map<String, Object> placeholderObject() {
    Map<String, Object> placeholder = new LinkedHashMap<>();
    placeholder.put("type", "object");
    return placeholder;
  }

  private static Map<String, Object> bodySchema(
      @Nullable SchemaGenerator schemas, @Nullable String type, @Nullable String viewFqcn) {
    return schemas == null ? placeholderObject() : schemas.schemaForView(type, viewFqcn);
  }

  private static boolean unknownType(@Nullable String type) {
    return type == null || type.strip().isEmpty() || type.strip().equals("?");
  }

  private static Map<String, Object> unresolvedSchema() {
    return new LinkedHashMap<>();
  }

  /**
   * The request-body schema across the merged rows: one type's schema, or a {@code oneOf} when the
   * collapsed bindings declare genuinely different input types.
   */
  private static Map<String, Object> requestSchema(
      List<Map<String, Object>> rows, @Nullable SchemaGenerator schemas) {
    if (schemas == null) {
      return placeholderObject();
    }
    List<Map<String, Object>> distinct = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> schema =
          schemas.schemaForView((String) row.get("request_type"), (String) row.get("view_fqcn"));
      if (!distinct.contains(schema)) {
        distinct.add(schema);
      }
    }
    if (distinct.isEmpty()) {
      return placeholderObject();
    }
    if (distinct.size() == 1) {
      return distinct.get(0);
    }
    Map<String, Object> oneOf = new LinkedHashMap<>();
    oneOf.put("oneOf", distinct);
    return oneOf;
  }

  /**
   * OpenAPI requires "/"-rooted paths; unresolved-mount rows carry a bare "{KIND-root}" template.
   */
  private static String normalizePath(String path) {
    return path.startsWith("/") ? path : "/" + path;
  }

  /** Re-key a path's methods into VERB_ORDER (then alphabetical) for stable output. */
  private static Map<String, Object> orderMethods(Map<String, Object> methods) {
    List<String> keys = new ArrayList<>(methods.keySet());
    keys.sort(
        Comparator.comparingInt(
                (String m) -> {
                  int idx = VERB_ORDER.indexOf(m);
                  return idx < 0 ? VERB_ORDER.size() : idx;
                })
            .thenComparing(Comparator.naturalOrder()));
    Map<String, Object> ordered = new LinkedHashMap<>();
    for (String k : keys) {
      ordered.put(k, methods.get(k));
    }
    return ordered;
  }

  /** One merged operation for all rows that share this (path, method). */
  private static Map<String, Object> operation(
      String path, List<Map<String, Object>> rows, @Nullable SchemaGenerator schemas) {
    Map<String, Object> first = rows.get(0);
    Map<String, Object> op = new LinkedHashMap<>();
    op.put("operationId", operationId((String) first.get("http_method"), path));
    op.put("tags", List.of(tag(path)));

    List<Object> params = new ArrayList<>();
    Matcher m = PATH_PARAM.matcher(path);
    while (m.find()) {
      params.add(pathParameter(m.group(1)));
    }
    // Merge query parameters across the collapsed rows by canonical name: OR required/repeated,
    // union aliases, and flag a declared-type disagreement rather than letting one silently win.
    Map<String, QueryParamAcc> byName = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      collectOptions(row, byName);
    }
    List<Map<String, Object>> queries = new ArrayList<>();
    for (QueryParamAcc acc : byName.values()) {
      queries.add(acc.toParameter());
    }
    queries.sort(Comparator.comparing(p -> (String) p.get("name")));
    params.addAll(queries);
    if (!params.isEmpty()) {
      op.put("parameters", params);
    }

    // Request body. Only POST/PUT/PATCH carry one with well-defined semantics; OpenAPI/Swagger
    // flag a body on GET/HEAD/DELETE. A Gerrit delete that accepts an input delivers it via the
    // POST method-override transport, so for those methods the body is kept off the operation but
    // preserved under x-gerrit-suppressed-request-body so the information is not lost.
    Set<String> reps = new LinkedHashSet<>();
    for (Map<String, Object> row : rows) {
      if (row.get("request_representations") instanceof List<?> l) {
        for (Object o : l) {
          reps.add((String) o);
        }
      }
    }
    Map<String, Object> body = requestBody(reps, rows, schemas);
    if (body != null) {
      String method = ((String) first.get("http_method")).toLowerCase(Locale.ROOT);
      boolean bodyBearing = method.equals("post") || method.equals("put") || method.equals("patch");
      op.put(bodyBearing ? "requestBody" : "x-gerrit-suppressed-request-body", body);
    }

    op.put("responses", responses(rows, schemas));

    // Preserve the source bindings when more than one collapsed into this operation.
    if (rows.size() > 1) {
      List<Object> bindings = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("binding", row.get("binding"));
        b.put("view_fqcn", row.get("view_fqcn"));
        b.put("request_type", row.get("request_type"));
        b.put("request_representations", row.get("request_representations"));
        b.put("status_constant", row.get("status_constant"));
        b.put("response_type", row.get("response_type"));
        bindings.add(b);
      }
      op.put("x-gerrit-bindings", bindings);
    }
    return op;
  }

  private static Map<String, Object> pathParameter(String name) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("name", name);
    p.put("in", "path");
    p.put("required", true);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    p.put("schema", schema);
    return p;
  }

  /** Fold a row's args4j {@code @Option} params into the per-canonical-name merge accumulators. */
  private static void collectOptions(Map<String, Object> row, Map<String, QueryParamAcc> acc) {
    if (!(row.get("query_params") instanceof List<?> list)) {
      return;
    }
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> qp) || !"option".equals(qp.get("kind"))) {
        continue; // positional @Argument params are not query parameters in this pass
      }
      if (!(qp.get("name") instanceof String name) || name.isEmpty()) {
        continue;
      }
      acc.computeIfAbsent(stripDashes(name), QueryParamAcc::new) // --project -> project, -n -> n
          .add(
              Boolean.TRUE.equals(qp.get("required")),
              Boolean.TRUE.equals(qp.get("repeated")),
              (String) qp.get("type"),
              stripDashes(qp.get("aliases")));
    }
  }

  /** The facts of one query parameter merged across every row collapsed into an operation. */
  private static final class QueryParamAcc {
    private final String name;
    private boolean required;
    private boolean repeated;
    private final Set<String> types = new LinkedHashSet<>(); // distinct declared source types
    private final Set<String> aliases = new LinkedHashSet<>();

    QueryParamAcc(String name) {
      this.name = name;
    }

    void add(boolean required, boolean repeated, @Nullable String type, List<String> aliases) {
      this.required |= required;
      this.repeated |= repeated;
      if (type != null) {
        this.types.add(type);
      }
      this.aliases.addAll(aliases);
    }

    Map<String, Object> toParameter() {
      Map<String, Object> p = new LinkedHashMap<>();
      p.put("name", name);
      p.put("in", "query");
      if (required) {
        p.put("required", true);
      }
      // One agreed declared type -> its mapped schema; a disagreement across merged bindings falls
      // back to string (array if any side is repeated) and is flagged rather than silently
      // resolved.
      String type = types.size() == 1 ? types.iterator().next() : "String";
      p.put("schema", schemaForType(type, repeated));
      if (!aliases.isEmpty()) {
        p.put("x-gerrit-aliases", new ArrayList<>(aliases)); // short forms, --query/-q -> ["q"]
      }
      if (types.size() > 1) {
        p.put("x-gerrit-query-param-conflict", new ArrayList<>(types));
      }
      return p;
    }
  }

  /** Map an args4j field type to a scalar (or array) schema. Enums degrade to string. */
  private static Map<String, Object> schemaForType(@Nullable String type, boolean repeated) {
    if (repeated || (type != null && TypeNames.isCollection(type))) {
      Map<String, Object> schema = new LinkedHashMap<>();
      schema.put("type", "array");
      schema.put("items", scalarSchema(elementType(type)));
      return schema;
    }
    return scalarSchema(type);
  }

  private static Map<String, Object> scalarSchema(@Nullable String type) {
    Map<String, Object> schema = new LinkedHashMap<>();
    switch (type == null ? "" : type) {
      case "boolean", "Boolean" -> schema.put("type", "boolean");
      case "int", "Integer", "long", "Long", "short", "Short" -> schema.put("type", "integer");
      case "float", "Float", "double", "Double" -> schema.put("type", "number");
      case "Instant" -> {
        // Gerrit timestamps are not RFC 3339 (see SchemaGenerator); use a Gerrit-specific
        // format so conformant generators fall back to plain string instead of a date type.
        schema.put("type", "string");
        schema.put("format", "gerrit-timestamp");
      }
      default -> schema.put("type", "string");
    }
    return schema;
  }

  private static String elementType(@Nullable String type) {
    if (type == null) {
      return "String";
    }
    if (type.endsWith("[]")) {
      return type.substring(0, type.length() - 2);
    }
    int lt = type.indexOf('<');
    int gt = type.lastIndexOf('>');
    return lt >= 0 && gt > lt ? type.substring(lt + 1, gt).strip() : "String";
  }

  @Nullable
  private static Map<String, Object> requestBody(
      Set<String> reps, List<Map<String, Object>> rows, @Nullable SchemaGenerator schemas) {
    Map<String, Object> content = new LinkedHashMap<>();
    Map<String, Object> jsonSchema = null; // computed lazily, shared by json + form
    for (String rep : REP_ORDER) {
      if (!reps.contains(rep)) {
        continue;
      }
      Map<String, Object> schema;
      if (rep.equals("raw")) {
        schema = new LinkedHashMap<>();
        schema.put("type", "string");
        schema.put("format", "binary");
      } else if (rep.equals("text")) {
        schema = new LinkedHashMap<>();
        schema.put("type", "string");
      } else {
        if (jsonSchema == null) {
          jsonSchema = requestSchema(rows, schemas); // json, form: the input type's schema
        }
        schema = jsonSchema;
      }
      Map<String, Object> mediaType = new LinkedHashMap<>();
      mediaType.put("schema", schema);
      content.put(REQUEST_MEDIA.get(rep), mediaType);
    }
    if (content.isEmpty()) {
      return null; // "none" only
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("content", content);
    return body;
  }

  /**
   * Union of every merged row's status codes; content type follows each row's response_type. A row
   * that contributes no numeric status (null / dynamic {@code withStatusCode}) adds a {@code
   * default} response even when other rows have numeric codes — otherwise that binding's response
   * would vanish from the merged operation.
   */
  private static Map<String, Object> responses(
      List<Map<String, Object>> rows, @Nullable SchemaGenerator schemas) {
    Map<String, Object> responses = new TreeMap<>();
    boolean needsDefault = false;
    String defaultResponseType = null;
    String defaultViewFqcn = null;
    for (Map<String, Object> row : rows) {
      String responseType = (String) row.get("response_type");
      String viewFqcn = (String) row.get("view_fqcn");
      int numeric = 0;
      if (row.get("status_constant") instanceof String sc) {
        for (String code : sc.split(",", -1)) {
          code = code.strip();
          if (code.matches("\\d{3}")) {
            responses.putIfAbsent(code, responseBody(code, responseType, viewFqcn, schemas));
            numeric++;
          }
        }
      }
      if (numeric == 0 && !needsDefault) {
        needsDefault = true;
        defaultResponseType = responseType;
        defaultViewFqcn = viewFqcn;
      }
    }
    if (needsDefault) {
      responses.put(
          "default", responseBody("default", defaultResponseType, defaultViewFqcn, schemas));
    }
    return responses;
  }

  private static Map<String, Object> responseBody(
      String code,
      @Nullable String responseType,
      @Nullable String viewFqcn,
      @Nullable SchemaGenerator schemas) {
    Map<String, Object> resp = new LinkedHashMap<>();
    resp.put("description", REASON.getOrDefault(code, "Response"));
    if (code.equals("204")) {
      return resp; // no content
    }
    boolean binary = responseType != null && responseType.contains("BinaryResult");
    boolean unresolved = schemas != null && unknownType(responseType);
    Map<String, Object> schema;
    if (binary) {
      schema = new LinkedHashMap<>();
      schema.put("type", "string");
      schema.put("format", "binary");
    } else if (unresolved) {
      schema = unresolvedSchema();
    } else {
      schema = bodySchema(schemas, responseType, viewFqcn); // the response type's schema
    }
    Map<String, Object> mediaType = new LinkedHashMap<>();
    mediaType.put("schema", schema);
    if (unresolved) {
      mediaType.put("x-gerrit-response-unresolved", true);
    }
    Map<String, Object> content = new LinkedHashMap<>();
    content.put(binary ? "application/octet-stream" : "application/json", mediaType);
    resp.put("content", content);
    return resp;
  }

  private static String stripDashes(String name) {
    return name.replaceFirst("^-+", "");
  }

  private static List<String> stripDashes(@Nullable Object aliases) {
    if (!(aliases instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object a : list) {
      if (a instanceof String s && !s.isEmpty()) {
        out.add(stripDashes(s));
      }
    }
    return out;
  }

  /**
   * Domain tag for a path: its first plain segment ({@code /changes/{id}} -> {@code changes}), or —
   * for an unresolved root mount whose leading segment is a {@code {KIND-root}} placeholder — the
   * domain derived from the kind ({@code /{PLUGIN_KIND-root}/enable} -> {@code plugins}), so the
   * tag is a declared domain rather than the action segment.
   */
  private static String tag(String path) {
    String[] segs = path.split("/", -1);
    int start = 0;
    while (start < segs.length && segs[start].isEmpty()) {
      start++;
    }
    // A plugin's own servlet endpoint is mounted at /plugins/<name>/<resource>...; tag it by
    // its resource ("checkers"), not the mount prefix. Only when <name> is a literal and a
    // segment follows -- so core's /plugins/{plugin-id} collection (param second segment) is
    // unchanged.
    if (start + 2 < segs.length
        && segs[start].equals("plugins")
        && !segs[start + 1].isEmpty()
        && !segs[start + 1].startsWith("{")) {
      start += 2;
    }
    for (int i = start; i < segs.length; i++) {
      String seg = segs[i];
      if (seg.isEmpty()) {
        continue;
      }
      if (seg.startsWith("{") && seg.endsWith("}")) {
        String inner = seg.substring(1, seg.length() - 1);
        int kind = inner.indexOf("_KIND");
        if (kind > 0) {
          return inner.substring(0, kind).toLowerCase(Locale.ROOT)
              + "s"; // PLUGIN_KIND-root -> plugins
        }
        continue; // a resource id like {change-id} is not a tag source
      }
      return seg;
    }
    return "default";
  }

  /**
   * Deterministic id, unique per (method, path): GET /changes/{id}/detail -> getChangesIdDetail.
   */
  private static String operationId(String httpMethod, String path) {
    StringBuilder sb = new StringBuilder(httpMethod.toLowerCase(Locale.ROOT));
    for (String seg : path.split("/", -1)) {
      if (seg.isEmpty()) {
        continue;
      }
      for (String part : seg.replaceAll("[^A-Za-z0-9]", " ").strip().split("\\s+", -1)) {
        if (!part.isEmpty()) {
          sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
      }
    }
    return sb.toString();
  }
}
