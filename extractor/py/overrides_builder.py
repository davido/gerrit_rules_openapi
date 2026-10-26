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

"""Match doc records against a built spec and assemble the override entries.

The dialect-agnostic back half: it indexes an already-built OpenAPI document
(``load_spec``), resolves each parsed doc endpoint to a real operation
(``match`` -- exact, then a structural fallback), and emits the override ops plus
the worklist diagnostics (``build``). Input records come from ``doc_dispatch``;
the CLI wrapper is ``override_extractor``."""

import json

import doc_common as dc


def load_spec(spec_path):
    """Return (facts, shape): structural key -> emitted-shape facts, plus a
    by-(method, segment-count) index for the structural fallback matcher."""
    with open(spec_path, encoding="utf-8") as fh:
        spec = json.load(fh)
    facts = {}
    shape = {}
    for path, methods in spec["paths"].items():
        for method, op in methods.items():
            if not isinstance(op, dict):
                continue
            m, ck = method.lower(), dc.canon(path)
            resp_code = next(
                (
                    c
                    for c, r in (op.get("responses") or {}).items()
                    if isinstance(r, dict) and "application/json" in (r.get("content") or {})
                ),
                None,
            )
            facts[(m, ck)] = {
                "path": path,
                "resp_code": resp_code,
                "has_json_req": "application/json"
                in ((op.get("requestBody") or {}).get("content") or {}),
            }
            segs = tuple(ck.strip("/").split("/")) if ck != "/" else ()
            shape.setdefault((m, len(segs)), []).append((segs, (m, ck)))
    return facts, shape


def match(doc_key, facts, shape):
    """Resolve a doc endpoint to a spec facts-key: exact, then a structural
    fallback (same method + segment count, literals equal, `{}` accepts any doc
    segment) — for doc paths with concrete example segments where the spec has a
    parameter (e.g. .../indexes/changes vs .../indexes/{index-name}). Ambiguous
    fallbacks (more than one candidate) are left unmatched."""
    if doc_key in facts:
        return doc_key
    m, p = doc_key
    dsegs = tuple(p.strip("/").split("/")) if p != "/" else ()
    cands = []
    for segs, ck in shape.get((m, len(dsegs)), []):
        pairs = list(zip(dsegs, segs))
        # every segment matches literally or against a spec `{}` param, AND at least
        # one literal segment is shared -- rejects all-wildcard candidates like
        # GET /Documentation/ -> GET /{PLUGIN_KIND-collection}.
        if all(d == s or s == "{}" for d, s in pairs) and any(
            d == s and s != "{}" for d, s in pairs
        ):
            cands.append(ck)
    return cands[0] if len(cands) == 1 else None


def build(records, facts, shape):
    by_key = {}  # (method, actual path) -> entry; guarantees unique operation keys
    unmatched = []
    dropped = []  # request examples dropped: the op has no JSON requestBody in spec
    merged = []  # >1 doc section merged into one op (visibility for prose conflicts)
    for key, rec in records.items():
        fkey = match(key, facts, shape)
        fact = facts.get(fkey) if fkey else None
        if fact is None:
            unmatched.append({"endpoint": f"{rec['method']} {key[1]}", "source": rec["source"]})
            continue
        okey = (rec["method"], fact["path"])
        if okey in by_key:  # a second+ doc section maps to the same operation
            note = {"endpoint": f"{okey[0]} {okey[1]}", "source": rec["source"]}
            prior = by_key[okey].get("summary")
            if rec["title"] and prior and prior != rec["title"]:
                note["summary_conflict"] = [prior, rec["title"]]
            merged.append(note)
        entry = by_key.setdefault(okey, {"method": rec["method"], "path": fact["path"]})
        if rec["title"] and "summary" not in entry:
            entry["summary"] = rec["title"]
        if rec["description"] and "description" not in entry:
            entry["description"] = rec["description"]
        if rec["req"] is not None and "requestBody" not in entry:
            if fact["has_json_req"]:
                entry.setdefault("requestBody", {}).setdefault("content", {})[
                    "application/json"
                ] = {"example": rec["req"]}
            else:  # spec gap: the doc has a request body the spec does not model
                dropped.append(
                    {
                        "endpoint": f"{okey[0]} {okey[1]}",
                        "source": rec["source"],
                        "reason": "no JSON request body in spec",
                    }
                )
        if rec["resp"] is not None and "responses" not in entry:
            # attach even when the base response has no application/json content:
            # a valid doc response block is exactly the example the override supplies.
            code = fact["resp_code"] or "200"
            entry.setdefault("responses", {})[code] = {
                "content": {"application/json": {"example": rec["resp"]}}
            }
    ops = [e for e in by_key.values() if len(e) > 2]  # carry something beyond method/path
    ops.sort(key=lambda e: (e["path"], e["method"]))
    return ops, unmatched, {"dropped_request_examples": dropped, "merged_sections": merged}
