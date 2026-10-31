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

"""Structural gate for the generated OpenAPI overrides (a build output).

Deliberately *not* a byte-diff golden: the overrides carry handwritten prose, so a
golden would churn on every doc edit. Instead we assert the structural contract the
emitter relies on -- the generated overrides are a `{"operations": [...]}` document
whose every `(method, path)` resolves to a real operation in the override-less base
spec, with unique keys and well-formed example bodies.

Usage: overrides_structural_test.py <generated-overrides.json> <base-spec.json>
"""

import json
import re
import sys


def canon(path):
    return re.sub(r"\{[^}]*\}", "{}", path).rstrip("/") or "/"


def main():
    gen = json.load(open(sys.argv[1], encoding="utf-8"))
    base = json.load(open(sys.argv[2], encoding="utf-8"))
    errors = []

    allowed = {"operations", "components", "info", "tags"}
    extra = [k for k in gen if k not in allowed]
    if extra or "operations" not in gen:
        errors.append(f"top-level keys {list(gen.keys())} not within {sorted(allowed)}")

    # info (mined document envelope): an object whose title/description, if present, are
    # strings -- the compose() step layers it under the curated overrides.
    info = gen.get("info")
    if info is not None:
        if not isinstance(info, dict):
            errors.append("info must be an object")
        else:
            for k in ("title", "description"):
                if k in info and not (isinstance(info[k], str) and info[k]):
                    errors.append(f"info.{k} must be a non-empty string")

    # tags (mined tag definitions): an array of {name, description?} objects.
    tags = gen.get("tags")
    if tags is not None:
        if not isinstance(tags, list):
            errors.append("tags must be an array")
        else:
            for n, tag in enumerate(tags):
                if not isinstance(tag, dict) or not isinstance(tag.get("name"), str):
                    errors.append(f"tags[{n}] must be an object with a string 'name'")
                elif "description" in tag and not isinstance(tag["description"], str):
                    errors.append(f"tags[{n}].description must be a string")

    # components (mined schema field descriptions): {schemas: {Entity: {properties:
    # {field: {description: str}}}}} -- an additive, well-formed OpenAPI fragment.
    for entity, schema in (gen.get("components", {}).get("schemas") or {}).items():
        for fieldname, prop in (schema.get("properties") or {}).items():
            if (
                not isinstance(prop, dict)
                or not isinstance(prop.get("description"), str)
                or not prop["description"]
            ):
                errors.append(f"malformed field description: {entity}.{fieldname}")
        req = schema.get("required")
        if req is not None and (
            not isinstance(req, list) or not all(isinstance(r, str) for r in req)
        ):
            errors.append(f"malformed required list: {entity}")

    base_keys = {
        (m.upper(), canon(p)) for p, methods in base.get("paths", {}).items() for m in methods
    }

    seen = set()
    for op in gen.get("operations", []):
        method, path = op.get("method"), op.get("path")
        if not method or not path:
            errors.append(f"operation missing method/path: {op}")
            continue
        key = (method, canon(path))
        if key in seen:
            errors.append(f"duplicate operation key: {method} {path}")
        seen.add(key)
        if key not in base_keys:
            errors.append(f"phantom operation not in base spec: {method} {path}")
        rb = op.get("requestBody")
        if rb is not None and "application/json" not in (rb.get("content") or {}):
            errors.append(f"malformed requestBody: {method} {path}")
        for code, resp in (op.get("responses") or {}).items():
            if "application/json" not in (resp.get("content") or {}):
                errors.append(f"malformed response {code}: {method} {path}")

    if errors:
        print("STRUCTURAL FAILURES:")
        for e in errors[:50]:
            print("  -", e)
        sys.exit(1)
    print(
        f"structural OK: {len(gen['operations'])} operations, all keys in base, "
        "unique, well-formed example bodies"
    )


if __name__ == "__main__":
    main()
