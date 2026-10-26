# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Semantic validation of the generated OpenAPI document.

The full OpenAPI document is a build output, not a checked-in golden. Instead of
diffing it against a versioned file, this test asserts the properties a consumer
relies on: it parses, is 3.x, has paths, resolves every $ref, leaves no unresolved
type, keeps request bodies off GET/HEAD/DELETE, keeps tags/security consistent, and
-- given the endpoint inventory report -- covers every extracted (method, path).

Usage: openapi_spec_validator.py <openapi.json> <inventory-report.json>
"""

import json
import re
import sys


def fail(message):
    sys.stderr.write("OpenAPI validation FAILED: " + message + "\n")
    sys.exit(1)


def normalize_path(path):
    return path if path.startswith("/") else "/" + path


def mount_placeholders(path):
    """Unresolved mount placeholders among a path's template params (e.g.
    {PLUGIN_KIND-root}).

    A resolved resource id is lowercase -- {plugin-id}, {change-id}. The emitter uses a
    <KIND>_KIND-<role> name only when a collection's absolute servlet mount could not be
    resolved statically, leaving a path no client generator can consume. Such a path must
    never reach the published spec; add the mount to
    PathRootResolver.UNDISCOVERABLE_MOUNTS if it is served outside the serveRegex
    mechanism. Returns the offending param names, sorted (empty when the path is clean).
    """
    return sorted(t for t in re.findall(r"\{([^}]+)\}", path) if "_KIND" in t)


# Methods for which a request body has undefined semantics.
BODYLESS_METHODS = ("get", "head", "delete")


def check_version(doc):
    if str(doc.get("openapi", "")).split(".")[0] != "3":
        fail("missing or non-3.x openapi version: %r" % doc.get("openapi"))


def check_refs_resolve(doc):
    """Every $ref points at a defined schema, and no x-gerrit-unresolved markers remain."""
    schemas = (doc.get("components") or {}).get("schemas") or {}
    text = json.dumps(doc)
    refs = set(re.findall(r'"\$ref": "#/components/schemas/([^"]+)"', text))
    dangling = sorted(r for r in refs if r not in schemas)
    if dangling:
        fail("dangling $refs: %s" % dangling[:5])
    if '"x-gerrit-unresolved"' in text:
        fail("spec still has x-gerrit-unresolved markers")


def check_operations(paths):
    """Validate every operation (ids, responses, bodyless bodies, params); return
    (operation_ids, used_tags, actual_ops) for the cross-operation checks that follow."""
    ids = []
    used_tags = set()
    actual_ops = set()
    for p, operations in paths.items():
        if not p.startswith("/"):
            fail("path is not /-rooted: %s" % p)
        template_params = set(re.findall(r"\{([^}]+)\}", p))
        placeholders = mount_placeholders(p)
        if placeholders:
            fail("unresolved mount placeholder(s) in path %s: %s" % (p, placeholders))
        for raw_method, op in operations.items():
            # OpenAPI method keys are lowercase; normalize defensively so the bodyless
            # (GET/HEAD/DELETE) checks below can never silently no-op on a stray uppercase key.
            method = raw_method.lower()
            actual_ops.add((method, p))
            op_id = op.get("operationId")
            if not isinstance(op_id, str) or not op_id:
                fail("missing/empty operationId: %s %s" % (method.upper(), p))
            ids.append(op_id)
            used_tags |= set(op.get("tags", []))
            if not op.get("responses"):
                fail("no responses: %s %s" % (method.upper(), p))
            if method in BODYLESS_METHODS and "requestBody" in op:
                fail("requestBody on %s %s (undefined semantics)" % (method.upper(), p))
            for param in op.get("parameters", []):
                if not param.get("name") or not param.get("in") or "schema" not in param:
                    fail("parameter missing name/in/schema: %s %s" % (method.upper(), p))
                if param.get("in") == "path" and param.get("required") is not True:
                    fail(
                        "path parameter %r not required:true: %s %s"
                        % (param.get("name"), method.upper(), p)
                    )
            declared = {x["name"] for x in op.get("parameters", []) if x.get("in") == "path"}
            missing = template_params - declared
            if missing:
                fail("undeclared path params %s: %s %s" % (missing, method.upper(), p))
    return ids, used_tags, actual_ops


def check_unique_operation_ids(ids):
    dups = sorted(x for x in set(ids) if ids.count(x) > 1)
    if dups:
        fail("duplicate operationIds: %s" % dups[:5])


def check_tags_declared(doc, used_tags):
    declared_tags = {t.get("name") for t in doc.get("tags", [])}
    undeclared = sorted(used_tags - declared_tags)
    if undeclared:
        fail("tags used but not declared: %s" % undeclared)


def check_security_schemes(doc):
    schemes = set(((doc.get("components") or {}).get("securitySchemes") or {}).keys())
    for requirement in doc.get("security", []):
        for scheme in requirement:
            if scheme not in schemes:
                fail("security references an undefined scheme: %s" % scheme)


def check_inventory_coverage(inventory, actual_ops):
    """The spec's operations must equal the collapsed inventory operations -- nothing
    dropped, nothing invented. Returns (expected_ops, request_representations_by_op)."""
    expected = set()
    reps_by_op = {}
    for row in inventory.get("endpoints", []):
        key = (str(row["http_method"]).lower(), normalize_path(row["path_template"]))
        expected.add(key)
        reps_by_op.setdefault(key, set()).update(row.get("request_representations") or [])
    missing = sorted(expected - actual_ops)
    if missing:
        fail("inventory operations absent from the spec: %s" % missing[:5])
    extra = sorted(actual_ops - expected)
    if extra:
        fail("spec operations absent from the inventory: %s" % extra[:5])
    return expected, reps_by_op


def check_suppressed_bodies(paths, reps_by_op):
    """A GET/HEAD/DELETE that carries a real request representation must preserve the
    suppressed body under x-gerrit-suppressed-request-body. Returns the count."""
    suppressed = 0
    for (method, p), reps in reps_by_op.items():
        if method in BODYLESS_METHODS and (reps - {"none"}):
            op = paths[p][method]
            ext = op.get("x-gerrit-suppressed-request-body")
            if not isinstance(ext, dict) or not ext.get("content"):
                fail("missing x-gerrit-suppressed-request-body: %s %s" % (method.upper(), p))
            suppressed += 1
    return suppressed


# Component-schema fields intentionally allowed to ship without a description (empty
# today: the generated spec is at full field-description coverage). Add an entry only
# with a comment justifying why the field cannot be documented from the REST docs.
FIELD_DESCRIPTION_ALLOWLIST = set()  # {"Schema.field", ...}


def check_field_descriptions(doc):
    """Every component-schema property carries a non-empty description. Descriptions are
    mined from the rest-api docs, so a property without one means a new entity or field
    shipped undocumented -- the regression this guard exists to catch. Property-less
    schemas (enums, free-form objects) are naturally skipped."""
    schemas = (doc.get("components") or {}).get("schemas") or {}
    missing = []
    for name, schema in sorted(schemas.items()):
        for field, spec in (schema.get("properties") or {}).items():
            if "%s.%s" % (name, field) in FIELD_DESCRIPTION_ALLOWLIST:
                continue
            if not (isinstance(spec, dict) and spec.get("description")):
                missing.append("%s.%s" % (name, field))
    if missing:
        fail(
            "%d schema field(s) lack a mined description (document the entity in "
            "rest-api-*.txt, or alias it in DOC_ENTITY_ALIAS): %s" % (len(missing), missing[:8])
        )


# Operations intentionally allowed to ship without a mined summary/description (empty
# today: the generated spec is at full endpoint-description coverage). Add an entry only
# with a comment justifying why the endpoint cannot be documented from the REST docs.
ENDPOINT_DESCRIPTION_ALLOWLIST = set()  # {"GET /path", ...}


def check_endpoint_descriptions(paths):
    """Every operation carries a non-empty summary and description, mined from the
    rest-api docs and joined to the Java-parsed route by (method, path). A missing one is
    documentation drift: the route exists in the tree but is not matched to a documented
    section -- either the endpoint is undocumented, or its doc verb-line path/method does
    not canon-match the emitted path. Fix by documenting it in rest-api-*.txt (or aliasing
    the doc section), not by widening this allowlist without a reason."""
    missing = []
    for p, operations in sorted(paths.items()):
        for raw_method, op in operations.items():
            key = "%s %s" % (raw_method.upper(), p)
            if key in ENDPOINT_DESCRIPTION_ALLOWLIST:
                continue
            if not op.get("summary") or not op.get("description"):
                missing.append(key)
    if missing:
        fail(
            "%d operation(s) lack a mined summary/description (document the endpoint in "
            "rest-api-*.txt): %s" % (len(missing), missing[:8])
        )


def main():
    try:
        doc = json.load(open(sys.argv[1]))
    except Exception as e:  # noqa: BLE001 - surface any parse error as a failure
        fail("not valid JSON: %s" % e)
    inventory = json.load(open(sys.argv[2]))

    check_version(doc)
    paths = doc.get("paths") or {}
    if not paths:
        fail("paths is empty")
    check_refs_resolve(doc)
    ids, used_tags, actual_ops = check_operations(paths)
    check_unique_operation_ids(ids)
    check_tags_declared(doc, used_tags)
    check_security_schemes(doc)
    check_endpoint_descriptions(paths)
    check_field_descriptions(doc)
    expected, reps_by_op = check_inventory_coverage(inventory, actual_ops)
    suppressed = check_suppressed_bodies(paths, reps_by_op)

    schemas = (doc.get("components") or {}).get("schemas") or {}
    print(
        "OpenAPI OK: %d paths, %d operations, %d schemas, %d inventory ops covered, "
        "%d suppressed bodies" % (len(paths), len(ids), len(schemas), len(expected), suppressed)
    )


if __name__ == "__main__":
    main()
