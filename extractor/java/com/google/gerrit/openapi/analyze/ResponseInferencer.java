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
import com.google.gerrit.openapi.dto.ResponseFacts;
import com.google.gerrit.openapi.dto.ViewFacts;
import com.google.gerrit.openapi.parse.AstUtil;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Infers a view's response facts from its (own or superclass-inherited) {@code apply()}: the
 * payload type from {@code Response<X>}, and the HTTP status from the {@code Response.*} factory
 * calls seen in its body (a conservative body scan, not return-flow analysis: a value assigned then
 * returned is still matched). Kept out of {@code RestEndpoints} so response modeling can grow
 * (status, headers, content negotiation) without fattening the extractor. Operates on the {@link
 * ViewFacts} produced by {@link ViewAnalyzer}.
 */
public final class ResponseInferencer {

  /** HTTP status for each {@code Response.*} factory ({@code withStatusCode} is dynamic). */
  private static final Map<String, String> FACTORY_STATUS =
      Map.of(
          "ok", "200",
          "withMustRevalidate", "200",
          "created", "201",
          "accepted", "202",
          "none", "204",
          "redirect", "302");

  /**
   * Writes a row's response fields from these facts. {@code viewResolved} gates {@code
   * response_confidence} to {@code "unknown"} when the endpoint's view could not be resolved.
   * Shared by {@code RestEndpoints} and {@code RootListEndpointSynthesizer} so both emit the same
   * shape.
   */
  public static void putResponseFields(
      Map<String, Object> row, ResponseFacts rf, boolean viewResolved) {
    row.put("response_type", rf.type());
    row.put("response_needs_override", rf.needsOverride());
    row.put("response_confidence", viewResolved ? rf.typeConfidence() : "unknown");
    row.put("factory_detected", rf.factories());
    row.put("status_constant", rf.status());
    row.put("status_confidence", rf.statusConfidence());
  }

  public ResponseFacts infer(@Nullable ViewFacts view) {
    // No resolvable view (an unresolved binding, or a collection whose list() view didn't resolve)
    // → response facts unknown.
    if (view == null) {
      return new ResponseFacts(null, true, "unknown", null, "unknown", List.of());
    }
    String type = responseType(view);
    boolean wildcard = unknownPayload(type);
    String typeConfidence = wildcard ? "low" : "high";

    List<String> factories = new ArrayList<>(responseFactories(view));
    List<String> statuses =
        factories.stream()
            .filter(FACTORY_STATUS::containsKey)
            .map(FACTORY_STATUS::get)
            .distinct()
            .sorted()
            .toList();
    String status;
    String statusConfidence;
    if (factories.contains("withStatusCode")) {
      status = "dynamic"; // arbitrary int argument, not statically a constant
      statusConfidence = "low";
    } else if (statuses.size() == 1) {
      status = statuses.get(0);
      statusConfidence = "high";
    } else if (statuses.size() > 1) {
      status = String.join(",", statuses); // several branches -> ambiguous
      statusConfidence = "low";
    } else {
      status = null; // no Response.* factory found (delegates/helpers)
      statusConfidence = "unknown";
    }
    return new ResponseFacts(type, wildcard, typeConfidence, status, statusConfidence, factories);
  }

  /**
   * The payload type from {@code Response<X>}, or a concrete returned {@code Response<Y>} local
   * when the declared method return is wildcarded.
   */
  @Nullable
  private static String responseType(ViewFacts view) {
    if (view.applyReturn() == null) {
      return null;
    }
    String declared = responsePayload(view.applyReturn());
    if (!unknownPayload(declared)) {
      return declared;
    }
    String local = responseTypeFromReturnedLocal(view.applyMethod());
    return local != null ? local : declared;
  }

  private static boolean unknownPayload(@Nullable String type) {
    return type == null || type.equals("?") || type.equals("Object");
  }

  @Nullable
  private static String responsePayload(@Nullable String responseType) {
    if (responseType == null) {
      return null;
    }
    int lt = responseType.indexOf('<');
    int gt = responseType.lastIndexOf('>');
    return lt >= 0 && gt > lt ? responseType.substring(lt + 1, gt).strip() : null;
  }

  @Nullable
  private static String responseTypeFromReturnedLocal(@Nullable MethodTree method) {
    if (method == null || method.getBody() == null) {
      return null;
    }
    Set<String> returnedLocals = returnedLocalNames(method);
    if (returnedLocals.isEmpty()) {
      return null;
    }
    Map<String, String> localResponseTypes = new LinkedHashMap<>();
    for (StatementTree statement : method.getBody().getStatements()) {
      if (statement instanceof VariableTree var
          && returnedLocals.contains(var.getName().toString())) {
        String payload = responsePayload(var.getType().toString());
        if (!unknownPayload(payload)) {
          localResponseTypes.put(var.getName().toString(), payload);
        }
      }
    }
    Set<String> uniqueTypes = new LinkedHashSet<>(localResponseTypes.values());
    return uniqueTypes.size() == 1 ? uniqueTypes.iterator().next() : null;
  }

  private static Set<String> returnedLocalNames(MethodTree method) {
    Set<String> names = new LinkedHashSet<>();
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitReturn(ReturnTree node, Void p) {
        if (node.getExpression() instanceof IdentifierTree id) {
          names.add(id.getName().toString());
        }
        return super.visitReturn(node, p);
      }
    }.scan(method.getBody(), null);
    return names;
  }

  /** All {@code Response.<factory>} calls anywhere in the apply() body (conservative). */
  private static Set<String> responseFactories(ViewFacts view) {
    Set<String> out = new LinkedHashSet<>();
    if (view.applyMethod() == null) {
      return out;
    }
    new TreeScanner<Void, Void>() {
      @Override
      public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
        if (node.getMethodSelect() instanceof MemberSelectTree sel
            && "Response".equals(AstUtil.lastIdent(sel.getExpression()))) {
          out.add(sel.getIdentifier().toString());
        }
        return super.visitMethodInvocation(node, p);
      }
    }.scan(view.applyMethod(), null);
    return out;
  }
}
