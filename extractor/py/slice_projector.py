#!/usr/bin/env python3
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

"""Project a per-domain OpenAPI slice from the finalized all-domain document.

A slice used to be re-emitted from scratch over its domain's modules. That recomputed
schema names over the subset's reachable set, so a type that the all-domain document
disambiguates (two classes share a simple name) kept a bare name in the slice and then
mined a different doc entity -- e.g. the projects DescriptionInput became a bare
``DescriptionInput`` and picked up the changes-area prose instead of the project one.

This tool removes that second emission. Given the finalized all-domain document and a
domain's endpoint inventory (method + path), it emits the slice as a pure projection:
the domain's operations and the transitive closure of the schemas they reference, copied
verbatim -- names, descriptions and ``required`` -- from the all-domain document, with its
framing (info, servers, security, tags, securitySchemes) and serialization preserved. A
slice is therefore a literal subset and can never disagree with the whole.

Usage: slice_projector.py --monolith <all-domain.json> --inventory <domain-inv.json> --out <slice.json>
"""

import argparse
import json
import sys

_VERBS = ("get", "post", "put", "delete", "patch")
_REF_PREFIX = "#/components/schemas/"


# -- serialization: byte-compatible with the Java emitter's JsonWriter --------------
# Top-level keys expanded one per line; `paths` and `components.schemas` expanded with
# each entry on its own compact line; every other value compact. Reproducing the emitter
# exactly keeps the slice diff limited to real content changes, not reformatting. This is
# verified byte-for-byte over the monolith and every published slice; it holds for the
# characters those specs contain. Python and Java escape a few control characters (\b, \f)
# differently, so a value containing one would round-trip to valid-but-not-identical JSON --
# none occur in Gerrit's specs today.
def _compact(value):
    return json.dumps(value, separators=(", ", ": "), ensure_ascii=False)


def _key(name):
    return json.dumps(name, ensure_ascii=False)


def _write_doc(doc):
    lines = ["{"]
    keys = list(doc)
    for ki, k in enumerate(keys):
        comma = "" if ki == len(keys) - 1 else ","
        if k == "paths":
            lines.append('  "paths": {')
            items = list(doc[k].items())
            for i, (p, v) in enumerate(items):
                c = "" if i == len(items) - 1 else ","
                lines.append("    %s: %s%s" % (_key(p), _compact(v), c))
            lines.append("  }" + comma)
        elif k == "components":
            lines.append('  "components": {')
            comps = list(doc[k].items())
            for ci, (ck, cv) in enumerate(comps):
                cc = "" if ci == len(comps) - 1 else ","
                if ck == "schemas":
                    lines.append('    "schemas": {')
                    it = list(cv.items())
                    for i, (n, sv) in enumerate(it):
                        c = "" if i == len(it) - 1 else ","
                        lines.append("      %s: %s%s" % (_key(n), _compact(sv), c))
                    lines.append("    }" + cc)
                else:
                    lines.append("    %s: %s%s" % (_key(ck), _compact(cv), cc))
            lines.append("  }" + comma)
        else:
            lines.append("  %s: %s%s" % (_key(k), _compact(doc[k]), comma))
    lines.append("}")
    return "\n".join(lines) + "\n"


# -- projection ----------------------------------------------------------------------
def _collect_refs(obj, acc):
    """Collect the names of #/components/schemas/* targets. Any other $ref form is
    rejected loudly: this tool projects only the schemas component, so a reference to
    another component (responses, parameters, requestBodies, ...) would be dropped and
    leave the slice dangling. Reject it here rather than emit a broken slice; generic
    component projection can be added if the spec ever grows such references."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "$ref":
                if isinstance(v, str) and v.startswith(_REF_PREFIX):
                    acc.add(v[len(_REF_PREFIX):])
                else:
                    sys.exit(
                        "slice_projector: unsupported $ref %r -- only %s* is projected; "
                        "add generic component projection to support it" % (v, _REF_PREFIX)
                    )
            else:
                _collect_refs(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            _collect_refs(v, acc)


def _schema_closure(schemas, seeds):
    """Every schema transitively reachable by $ref from the seed operations."""
    need = set()
    frontier = set()
    for seed in seeds:
        _collect_refs(seed, frontier)
    while frontier:
        name = frontier.pop()
        if name in need:
            continue
        need.add(name)
        schema = schemas.get(name)
        if schema is None:
            sys.exit("slice_projector: operation references unknown schema %r" % name)
        nxt = set()
        _collect_refs(schema, nxt)
        frontier |= nxt - need
    return need


def project(monolith, selected):
    """Build the slice document from `monolith`, keeping only `selected` operations
    (a set of (METHOD, path)) and the transitive schema closure they reach."""
    out = {}
    for k in ("openapi", "info", "servers"):
        if k in monolith:
            out[k] = monolith[k]

    paths = {}
    for p, methods in monolith.get("paths", {}).items():
        kept = {m: o for m, o in methods.items() if m not in _VERBS or (m.upper(), p) in selected}
        if any(m in _VERBS for m in kept):
            paths[p] = kept
    out["paths"] = paths

    mono_schemas = (monolith.get("components") or {}).get("schemas") or {}
    seeds = [o for methods in paths.values() for m, o in methods.items() if m in _VERBS]
    need = _schema_closure(mono_schemas, seeds)
    components = {"schemas": {n: mono_schemas[n] for n in mono_schemas if n in need}}
    security_schemes = (monolith.get("components") or {}).get("securitySchemes")
    if security_schemes is not None:
        components["securitySchemes"] = security_schemes
    out["components"] = components

    for k in ("security", "tags"):
        if k in monolith:
            out[k] = monolith[k]
    return out


def _selected_ops(inventory, monolith_ops):
    """The domain's operations, keyed (METHOD, path). The inventory may list endpoints the
    all-domain document does not emit (unsupported ones it drops); keep only the intersection
    -- the all-domain document is the source of truth for what is emitted."""
    inv = {(e["http_method"].upper(), e["path_template"]) for e in inventory.get("endpoints", [])}
    return inv & monolith_ops


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--monolith", required=True, help="the finalized all-domain OpenAPI document")
    ap.add_argument("--inventory", required=True, help="the domain's endpoint inventory JSON")
    ap.add_argument("--out", required=True, help="output slice path")
    args = ap.parse_args()

    with open(args.monolith, encoding="utf-8") as fh:
        monolith = json.load(fh)
    with open(args.inventory, encoding="utf-8") as fh:
        inventory = json.load(fh)

    monolith_ops = {
        (m.upper(), p)
        for p, methods in monolith.get("paths", {}).items()
        for m in methods
        if m in _VERBS
    }
    selected = _selected_ops(inventory, monolith_ops)
    doc = project(monolith, selected)
    with open(args.out, "w", encoding="utf-8") as fh:
        fh.write(_write_doc(doc))


if __name__ == "__main__":
    main()
