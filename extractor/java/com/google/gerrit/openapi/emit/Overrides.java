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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.ToNumberPolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * An OpenAPI overrides document: metadata that static analysis cannot infer (human-authored
 * summaries/descriptions, deprecations, and response bodies for the endpoints whose response type
 * the extractor leaves ambiguous), keyed by the stable operation identity {@code "<METHOD>
 * <path_template>"} and applied last so it never has to live as annotations in the core REST
 * classes.
 *
 * <p>At build time the emitter applies the <em>composed</em> overrides ({@link #compose}) =
 * generated (mined from the docs, a build output) ⊕ curated. Only the curated layer -- {@code
 * tools/openapi/openapi-overrides.json}, the document envelope plus the few edits the generator
 * cannot derive -- is checked in; the generated and composed documents are build outputs.
 *
 * <p>File shape:
 *
 * <pre>{@code
 * {
 *   "info": { "description": "..." },
 *   "operations": [
 *     { "method": "GET", "path": "/changes/{change-id}/detail",
 *       "summary": "...", "description": "...", "deprecated": true, "tags": ["changes"],
 *       "responses": { "200": { "description": "...", "content": { ... } } } }
 *   ]
 * }
 * }</pre>
 */
public final class Overrides {
  private final Map<String, Object> doc;
  private final Map<String, Map<String, Object>> byOperation;

  private Overrides(Map<String, Object> doc, Map<String, Map<String, Object>> byOperation) {
    this.doc = doc;
    this.byOperation = byOperation;
  }

  public static Overrides empty() {
    return new Overrides(Map.of(), Map.of());
  }

  // "_comment" is an ignored free-text note (JSON has no comments).
  private static final Set<String> DOC_KEYS =
      Set.of(
          "_comment",
          "info",
          "servers",
          "security",
          "tags",
          "securitySchemes",
          "operations",
          "components");
  private static final Set<String> OPERATION_KEYS =
      Set.of(
          "method",
          "path",
          "summary",
          "description",
          "deprecated",
          "tags",
          "responses",
          "requestBody");

  // LONG_OR_DOUBLE keeps integers as Long (not 1.0), so numeric metadata round-trips through
  // JsonWriter as JSON literals; parsed objects are ordered LinkedTreeMaps.
  private static final Gson GSON =
      new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

  /**
   * Parse and validate an overrides document. Throws {@link IllegalArgumentException} with a clear
   * message on any malformed shape (invalid JSON, unknown keys, wrong types, duplicate operations)
   * rather than silently ignoring it or failing later with a cast.
   */
  @SuppressWarnings("unchecked")
  public static Overrides load(String json) {
    Object root;
    try {
      root = GSON.fromJson(json, Object.class);
    } catch (JsonSyntaxException e) {
      throw new IllegalArgumentException("overrides: invalid JSON: " + e.getMessage(), e);
    }
    if (!(root instanceof Map<?, ?> docRaw)) {
      throw new IllegalArgumentException("overrides: top level must be a JSON object");
    }
    Map<String, Object> doc = (Map<String, Object>) docRaw;
    checkKeys("top level", doc, DOC_KEYS);
    checkObject("info", doc.get("info"));
    checkObjectValues("securitySchemes", doc.get("securitySchemes"));
    checkObjectArray("servers", doc.get("servers"), "url");
    checkObjectArray("security", doc.get("security"), null);
    checkObjectArray("tags", doc.get("tags"), "name");
    checkArray("operations", doc.get("operations"));
    checkObject("components", doc.get("components"));

    Map<String, Map<String, Object>> byOp = new LinkedHashMap<>();
    if (doc.get("operations") instanceof List<?> ops) {
      for (int i = 0; i < ops.size(); i++) {
        if (!(ops.get(i) instanceof Map<?, ?> opRaw)) {
          throw new IllegalArgumentException("overrides: operations[" + i + "] must be an object");
        }
        Map<String, Object> op = (Map<String, Object>) opRaw;
        String opKey = validateOperation(op, i);
        if (byOp.putIfAbsent(opKey, op) != null) {
          throw new IllegalArgumentException(
              "overrides: duplicate operation override for " + opKey);
        }
      }
    }
    return new Overrides(doc, byOp);
  }

  /** Validate one operation override, returning its {@code "<method> <path>"} key. */
  private static String validateOperation(Map<String, Object> op, int index) {
    String where = "operations[" + index + "]";
    checkKeys(where, op, OPERATION_KEYS);
    String method = requireString(where, op, "method");
    String path = requireString(where, op, "path");
    checkString(where + " 'summary'", op.get("summary"));
    checkString(where + " 'description'", op.get("description"));
    if (op.containsKey("deprecated") && !(op.get("deprecated") instanceof Boolean)) {
      throw new IllegalArgumentException("overrides: " + where + " 'deprecated' must be a boolean");
    }
    checkStringArray(where + " 'tags'", op.get("tags"));
    if (op.containsKey("responses")) {
      if (!(op.get("responses") instanceof Map<?, ?> responses)) {
        throw new IllegalArgumentException(
            "overrides: " + where + " 'responses' must be an object");
      }
      for (Map.Entry<?, ?> e : responses.entrySet()) {
        String code = String.valueOf(e.getKey());
        if (!code.equals("default") && !code.matches("\\d{3}")) {
          throw new IllegalArgumentException(
              "overrides: " + where + " response code '" + code + "' is not a status code");
        }
        if (!(e.getValue() instanceof Map)) {
          throw new IllegalArgumentException(
              "overrides: " + where + " response '" + code + "' must be an object");
        }
      }
    }
    return key(method, path);
  }

  private static void checkKeys(String where, Map<String, Object> m, Set<String> allowed) {
    for (String k : m.keySet()) {
      if (!allowed.contains(k)) {
        throw new IllegalArgumentException("overrides: unknown key '" + k + "' in " + where);
      }
    }
  }

  private static String requireString(String where, Map<String, Object> m, String field) {
    if (!(m.get(field) instanceof String s) || s.isEmpty()) {
      throw new IllegalArgumentException(
          "overrides: " + where + " needs a non-empty string '" + field + "'");
    }
    return s;
  }

  private static void checkString(String field, Object v) {
    if (v != null && !(v instanceof String)) {
      throw new IllegalArgumentException("overrides: " + field + " must be a string");
    }
  }

  private static void checkObject(String field, Object v) {
    if (v != null && !(v instanceof Map)) {
      throw new IllegalArgumentException("overrides: '" + field + "' must be an object");
    }
  }

  private static void checkArray(String field, Object v) {
    if (v != null && !(v instanceof List)) {
      throw new IllegalArgumentException("overrides: '" + field + "' must be an array");
    }
  }

  /** Each element must be an object; if {@code requiredKey} is set, a string under that key. */
  private static void checkObjectArray(String field, Object v, @Nullable String requiredKey) {
    if (v == null) {
      return;
    }
    if (!(v instanceof List<?> list)) {
      throw new IllegalArgumentException("overrides: '" + field + "' must be an array");
    }
    for (int i = 0; i < list.size(); i++) {
      if (!(list.get(i) instanceof Map<?, ?> element)) {
        throw new IllegalArgumentException("overrides: " + field + "[" + i + "] must be an object");
      }
      if (requiredKey != null && !(element.get(requiredKey) instanceof String)) {
        throw new IllegalArgumentException(
            "overrides: " + field + "[" + i + "] needs a string '" + requiredKey + "'");
      }
    }
  }

  /** An object whose every value is itself an object (e.g. {@code securitySchemes}). */
  private static void checkObjectValues(String field, Object v) {
    if (v == null) {
      return;
    }
    if (!(v instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("overrides: '" + field + "' must be an object");
    }
    for (Map.Entry<?, ?> e : map.entrySet()) {
      if (!(e.getValue() instanceof Map)) {
        throw new IllegalArgumentException(
            "overrides: " + field + "['" + e.getKey() + "'] must be an object");
      }
    }
  }

  private static void checkStringArray(String field, Object v) {
    if (v == null) {
      return;
    }
    if (!(v instanceof List<?> list)) {
      throw new IllegalArgumentException("overrides: " + field + " must be an array");
    }
    for (Object element : list) {
      if (!(element instanceof String)) {
        throw new IllegalArgumentException("overrides: " + field + " must contain only strings");
      }
    }
  }

  /** Top-level {@code info} override fields (merged over the generated title/version). */
  public Map<String, Object> info() {
    return mapMember("info");
  }

  /** Top-level {@code servers} array (authored — the API base URLs). */
  public List<Object> servers() {
    return listMember("servers");
  }

  /** Top-level {@code security} array (the global security requirement). */
  public List<Object> security() {
    return listMember("security");
  }

  /** Top-level {@code tags} array (tag definitions with descriptions). */
  public List<Object> tags() {
    return listMember("tags");
  }

  /** Authored {@code components/securitySchemes} object. */
  public Map<String, Object> securitySchemes() {
    return mapMember("securitySchemes");
  }

  /** The {@code "<method> <path>"} keys this sidecar declares operation overrides for. */
  public Set<String> operationKeys() {
    return byOperation.keySet();
  }

  /**
   * Mined per-entity field descriptions from the {@code components/schemas} fragment: {@code
   * {Entity: {field: description}}}. The doc parsers emit these from the REST reference field
   * tables; the emitter applies them to the generated schema properties.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Map<String, String>> schemaFieldDescriptions() {
    if (!(doc.get("components") instanceof Map<?, ?> comps)
        || !(((Map<String, Object>) comps).get("schemas") instanceof Map<?, ?> schemas)) {
      return Map.of();
    }
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : schemas.entrySet()) {
      if (!(e.getValue() instanceof Map<?, ?> sch)
          || !(((Map<String, Object>) sch).get("properties") instanceof Map<?, ?> props)) {
        continue;
      }
      Map<String, String> fields = new LinkedHashMap<>();
      for (Map.Entry<?, ?> pe : props.entrySet()) {
        if (pe.getValue() instanceof Map<?, ?> prop
            && ((Map<String, Object>) prop).get("description") instanceof String d) {
          fields.put((String) pe.getKey(), d);
        }
      }
      if (!fields.isEmpty()) {
        out.put((String) e.getKey(), fields);
      }
    }
    return out;
  }

  /**
   * Set each mined field description on the matching generated schema property, in place, only
   * where the property exists and carries no description yet. Lets docs land on {@code
   * components/schemas} without threading them through schema generation.
   */
  @SuppressWarnings("unchecked")
  public void applySchemaDescriptions(Map<String, Object> components) {
    for (Map.Entry<String, Map<String, String>> e : schemaFieldDescriptions().entrySet()) {
      if (!(components.get(e.getKey()) instanceof Map<?, ?> schema)
          || !(((Map<String, Object>) schema).get("properties") instanceof Map<?, ?> props)) {
        continue;
      }
      Map<String, Object> properties = (Map<String, Object>) props;
      for (Map.Entry<String, String> fe : e.getValue().entrySet()) {
        if (properties.get(fe.getKey()) instanceof Map<?, ?> prop) {
          ((Map<String, Object>) prop).putIfAbsent("description", fe.getValue());
        }
      }
    }
  }

  /**
   * Set each schema's {@code required} array from the mined/seeded required-ness, in place, limited
   * to the fields the generated schema actually declares (so a doc or seed field the server later
   * dropped does not produce a phantom required entry).
   */
  @SuppressWarnings("unchecked")
  public void applySchemaRequired(Map<String, Object> components) {
    if (!(doc.get("components") instanceof Map<?, ?> comps)
        || !(((Map<String, Object>) comps).get("schemas") instanceof Map<?, ?> schemas)) {
      return;
    }
    for (Map.Entry<?, ?> e : schemas.entrySet()) {
      if (!(e.getValue() instanceof Map<?, ?> ov)
          || !(((Map<String, Object>) ov).get("required") instanceof List<?> req)
          || !(components.get(e.getKey()) instanceof Map<?, ?> schema)
          || !(((Map<String, Object>) schema).get("properties") instanceof Map<?, ?> props)) {
        continue;
      }
      List<Object> present = new ArrayList<>();
      for (Object f : req) {
        if (props.containsKey(f)) {
          present.add(f);
        }
      }
      if (!present.isEmpty()) {
        ((Map<String, Object>) schema).put("required", present);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> mapMember(String key) {
    return doc.get(key) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  @SuppressWarnings("unchecked")
  private List<Object> listMember(String key) {
    return doc.get(key) instanceof List<?> l ? (List<Object>) l : List.of();
  }

  /**
   * Apply the override for {@code (method, path)} to a built operation, returning a new operation
   * in canonical key order (or the original if there is no override).
   */
  public Map<String, Object> apply(String method, String path, Map<String, Object> op) {
    Map<String, Object> ov = byOperation.get(key(method, path));
    if (ov == null) {
      return op;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("operationId", op.get("operationId"));
    if (ov.containsKey("summary")) {
      out.put("summary", ov.get("summary"));
    }
    if (ov.containsKey("description")) {
      out.put("description", ov.get("description"));
    }
    if (Boolean.TRUE.equals(ov.get("deprecated"))) {
      out.put("deprecated", true);
    }
    out.put("tags", ov.containsKey("tags") ? ov.get("tags") : op.get("tags"));
    // Carry the generated fields (parameters, requestBody, responses, x-gerrit-*) not set above.
    for (Map.Entry<String, Object> e : op.entrySet()) {
      out.putIfAbsent(e.getKey(), e.getValue());
    }
    if (ov.get("responses") instanceof Map<?, ?> ovResponses) {
      mergeResponses(out, ovResponses);
    }
    if (ov.get("requestBody") instanceof Map<?, ?> ovBody
        && out.get("requestBody") instanceof Map<?, ?> curBody) {
      out.put("requestBody", deepMerge(asObjectMap(curBody), ovBody));
    }
    return out;
  }

  /** Operation key: HTTP method (lower-cased, matching the emitted document) + path template. */
  private static String key(String method, String path) {
    return method.toLowerCase(Locale.ROOT) + " " + path;
  }

  /**
   * Compose two overrides documents into one, returned as JSON. The {@code generated} overrides
   * (mined doc prose) are the base layer; the {@code curated} overrides win per field (the same
   * {@link #deepMerge} semantics the emitter applies), so a hand-edit overrides the mined default.
   * Operations only {@code generated} has are kept; operations only {@code curated} has are
   * appended; the document envelope (info/servers/security/tags/...) comes entirely from {@code
   * curated}. This is the single merge implementation used both here and in {@link #apply}.
   *
   * <p>Invariant: the composed override is then merged onto the base-built operation by {@link
   * #apply}, i.e. the pipeline computes {@code merge(base, merge(generated, curated))}. Because
   * {@link #deepMerge} is not associative, that equals the intended {@code merge(merge(base,
   * generated), curated)} only while overrides are purely <em>additive</em> at each nested key --
   * they may add a sibling (e.g. an {@code example} next to a generated {@code schema}) and curated
   * overrides may replace {@code schema} objects. The extractor emits only additive leaves (string
   * summary/description, and {@code responses}/{@code requestBody} carrying an {@code example}), so
   * this holds; keep it so if the extractor is extended to emit schema-level overrides.
   */
  @SuppressWarnings("unchecked")
  public static String compose(String generatedJson, String curatedJson) {
    Map<String, Object> generated = parseDocument(generatedJson);
    Map<String, Object> curated = parseDocument(curatedJson);
    Map<String, Map<String, Object>> genOps = indexOperations(generated);
    Map<String, Map<String, Object>> curOps = indexOperations(curated);

    List<Object> merged = new ArrayList<>();
    for (Map.Entry<String, Map<String, Object>> e : genOps.entrySet()) {
      Map<String, Object> c = curOps.get(e.getKey());
      merged.add(c == null ? e.getValue() : mergeOperationOverrides(e.getValue(), c));
    }
    for (Map.Entry<String, Map<String, Object>> e : curOps.entrySet()) {
      if (!genOps.containsKey(e.getKey())) {
        merged.add(e.getValue());
      }
    }

    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : curated.entrySet()) {
      if (!e.getKey().equals("operations") && !e.getKey().equals("components")) {
        out.put(e.getKey(), e.getValue()); // envelope from curated
      }
    }
    out.put("operations", merged);
    // components (mined schema field descriptions) come from generated; a curated components
    // block, if any, wins per field.
    Object comps = mergeComponents(generated.get("components"), curated.get("components"));
    if (comps != null) {
      out.put("components", comps);
    }
    return GSON.toJson(out);
  }

  @SuppressWarnings("unchecked")
  private static Object mergeComponents(Object generated, Object curated) {
    if (generated instanceof Map<?, ?> g && curated instanceof Map<?, ?> c) {
      return deepMerge((Map<String, Object>) g, c);
    }
    return curated != null ? curated : generated;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseDocument(String json) {
    Object root;
    try {
      root = GSON.fromJson(json, Object.class);
    } catch (JsonSyntaxException e) {
      throw new IllegalArgumentException("overrides: invalid JSON: " + e.getMessage(), e);
    }
    if (!(root instanceof Map<?, ?> m)) {
      throw new IllegalArgumentException("overrides: top level must be a JSON object");
    }
    return (Map<String, Object>) m;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Map<String, Object>> indexOperations(Map<String, Object> doc) {
    Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    if (doc.get("operations") instanceof List<?> ops) {
      for (Object o : ops) {
        if (o instanceof Map<?, ?> op) {
          Map<String, Object> m = (Map<String, Object>) op;
          byKey.put(key((String) m.get("method"), (String) m.get("path")), m);
        }
      }
    }
    return byKey;
  }

  private static Map<String, Object> mergeOperationOverrides(
      Map<String, Object> generated, Map<String, Object> curated) {
    return deepMerge(moveGeneratedDefaultResponseToCuratedStatus(generated, curated), curated);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> moveGeneratedDefaultResponseToCuratedStatus(
      Map<String, Object> generated, Map<String, Object> curated) {
    if (!(generated.get("responses") instanceof Map<?, ?> genResponses)
        || !(curated.get("responses") instanceof Map<?, ?> curResponses)
        || !hasOnlyDefaultResponse(genResponses)) {
      return generated;
    }
    String statusCode = firstStatusCodeResponse(curResponses);
    if (statusCode == null) {
      return generated;
    }
    Map<String, Object> rewritten = new LinkedHashMap<>(generated);
    rewritten.put(
        "responses", Map.of(statusCode, ((Map<String, Object>) genResponses).get("default")));
    return rewritten;
  }

  private static boolean hasOnlyDefaultResponse(Map<?, ?> responses) {
    return responses.size() == 1 && responses.containsKey("default");
  }

  @Nullable
  private static String firstStatusCodeResponse(Map<?, ?> responses) {
    for (Object code : responses.keySet()) {
      String statusCode = String.valueOf(code);
      if (statusCode.matches("\\d{3}")) {
        return statusCode;
      }
    }
    return null;
  }

  /** Field-level merge of override responses into the generated ones (per status code). */
  @SuppressWarnings("unchecked")
  private static void mergeResponses(Map<String, Object> op, Map<?, ?> ovResponses) {
    Object existing = op.get("responses");
    Map<String, Object> responses =
        existing instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) existing)
            : new LinkedHashMap<>();
    if (firstStatusCodeResponse(ovResponses) != null
        && hasOnlyUnresolvedDefaultResponse(responses)) {
      responses.remove("default");
    }
    for (Map.Entry<?, ?> e : ovResponses.entrySet()) {
      String code = (String) e.getKey();
      if (!(e.getValue() instanceof Map<?, ?> ovResp)) {
        continue;
      }
      Object cur = responses.get(code);
      if (cur instanceof Map) {
        responses.put(code, deepMerge((Map<String, Object>) cur, ovResp));
      } else {
        responses.put(code, ovResp);
      }
    }
    op.put("responses", responses);
  }

  private static boolean hasOnlyUnresolvedDefaultResponse(Map<String, Object> responses) {
    if (responses.size() != 1 || !(responses.get("default") instanceof Map<?, ?> response)) {
      return false;
    }
    Object content = response.get("content");
    if (!(content instanceof Map<?, ?> contentByType)) {
      return false;
    }
    for (Object mediaType : contentByType.values()) {
      if (mediaType instanceof Map<?, ?> media
          && Boolean.TRUE.equals(media.get("x-gerrit-response-unresolved"))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Narrow the parsed-JSON {@code Map<?,?>} to {@code Map<String,Object>} (JSON keys are strings).
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> asObjectMap(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }

  /**
   * Recursively merge {@code source} into a copy of {@code target}: nested objects are merged (so a
   * sidecar can add a response/request example alongside the generated schema), schema objects and
   * leaves are overwritten.
   */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> deepMerge(Map<String, Object> target, Map<?, ?> source) {
    Map<String, Object> out = new LinkedHashMap<>(target);
    for (Map.Entry<?, ?> e : source.entrySet()) {
      String k = (String) e.getKey();
      Object sv = e.getValue();
      if (k.equals("schema")) {
        out.put(k, sv);
        out.remove("x-gerrit-response-unresolved");
      } else if (out.get(k) instanceof Map<?, ?> tv && sv instanceof Map<?, ?> svm) {
        out.put(k, deepMerge((Map<String, Object>) tv, svm));
      } else {
        out.put(k, sv);
      }
    }
    return out;
  }
}
