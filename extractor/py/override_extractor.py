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

"""Mine OpenAPI override entries from the handwritten rest-api docs (CLI).

Parse-only extraction (no rendering) split into single-responsibility modules,
mirroring the Java side's package decomposition:

  doc_common        text/path normalization + shared section-assembly helpers
  asciidoc_parser   AsciiDoc front-end  (Gerrit core rest-api-*.txt)
  markdown_parser   Markdown front-end  (plugins rest-api*.md: code-owners, checks)
  doc_dispatch      route each file to a front-end (``--doc-format``, default auto)
  overrides_builder match doc records against a built spec and emit overrides
  override_extractor (this file) argument parsing, output + report writing

Two doc dialects, one downstream half: only the front-end tokenizer differs; the
record it produces (``method, path, title, description, req, resp``) and everything
after it are shared. ``--doc-format auto`` picks per file by extension (``.md`` ->
Markdown, else AsciiDoc), so core and plugins run the identical tool.

Outputs:
  --out     generated overrides: {"operations": [ {method, path, summary,
            description, requestBody?, responses?} ... ]} sorted for clean diffs.
  --report  a worklist for the iterative "fix the doc glitch" loop: coverage
            counts, ``unmatched_doc_sections`` (no matching operation),
            ``skipped_examples`` (invalid-JSON / alternate blocks),
            ``dropped_request_examples`` (doc body the spec doesn't model), and
            ``merged_sections`` (>1 doc section for one operation).
"""

import argparse
import glob
import json
import sys

# Re-exported so `import override_extractor as ox` stays the stable public surface
# (the unit test and any ad-hoc callers use ox.canon / ox.parse_docs / ox.build ...).
from doc_common import (  # noqa: F401
    apply_endpoint_aliases,
    apply_entity_aliases,
    apply_inherited_fields,
    canon,
    clean_path,
    clean_text,
    json_body,
    trim,
)
from doc_dispatch import parse_docs  # noqa: F401
from overrides_builder import build, load_spec, match  # noqa: F401


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    src = ap.add_mutually_exclusive_group(required=True)
    src.add_argument(
        "--docs-manifest",
        help="file with one declared doc path per line (hermetic; "
        "Bazel writes the rest-api doc labels here)",
    )
    src.add_argument("--docs", help="glob for the rest-api docs (CLI)")
    ap.add_argument(
        "--doc-format",
        choices=["auto", "asciidoc", "markdown"],
        default="auto",
        help="doc dialect; auto picks per file by .md extension",
    )
    ap.add_argument(
        "--plugin", default=None, help="plugin install name; expands the @PLUGIN@ doc token to it"
    )
    ap.add_argument(
        "--spec", required=True, help="a built OpenAPI document for (method,path) keys + shape"
    )
    ap.add_argument(
        "--schema-required",
        default=None,
        help="openapi-schema-required.json to union with the doc-mined "
        "field optionality (required = docs + seed)",
    )
    ap.add_argument("--out", required=True, help="generated overrides JSON")
    ap.add_argument("--report", help="matching/unmatched report JSON")
    ap.add_argument(
        "--entity-aliases",
        default=None,
        help="plugin-scoped {spec_name: doc_heading} JSON merged over the "
        "shared core alias table; lets a plugin alias its own entities "
        "without touching core's DOC_ENTITY_ALIAS",
    )
    ap.add_argument(
        "--context-overrides",
        default=None,
        help="an overrides JSON (e.g. core's mined overrides) whose "
        "components/schemas field descriptions fill gaps for shared "
        "gerrit-core entities present in this spec (e.g. NotifyInfo). "
        "Components only -- operations are ignored -- and the plugin's "
        "own mined descriptions always win.",
    )
    args = ap.parse_args()

    extra_aliases = None
    if args.entity_aliases:
        with open(args.entity_aliases, encoding="utf-8") as fh:
            raw = json.load(fh)
        # Drop "_comment"-style documentation keys; keep only real spec->doc mappings.
        extra_aliases = {k: v for k, v in raw.items() if not k.startswith("_")}

    if args.docs_manifest:
        with open(args.docs_manifest, encoding="utf-8") as fh:
            doc_paths = [l.strip() for l in fh if l.strip()]
    else:
        doc_paths = glob.glob(args.docs)
    if not doc_paths:
        sys.exit("no docs to read")
    records, skipped, dupes, entity_fields, _ = parse_docs(doc_paths, args.doc_format, args.plugin)
    # Propagate base-entity field descriptions to subtypes documented as "has the
    # same fields as <Base>" (the docs table only their extras; the spec is flat).
    apply_inherited_fields(entity_fields)
    # Record entities the docs name differently from their spec/DTO class name.
    apply_entity_aliases(entity_fields, extra_aliases)
    # Fill field descriptions for shared gerrit-core entities the plugin references but
    # does not document locally (e.g. NotifyInfo), from a context overrides layer (core's
    # mined overrides). Components only and restricted to entities present in this spec,
    # so no cross-domain operations leak in; the plugin's own mined descriptions win.
    if args.context_overrides:
        with open(args.context_overrides, encoding="utf-8") as fh:
            ctx = json.load(fh)
        with open(args.spec, encoding="utf-8") as fh:
            spec_entities = set(
                ((json.load(fh).get("components") or {}).get("schemas") or {}).keys()
            )
        ctx_schemas = (ctx.get("components") or {}).get("schemas") or {}
        for ent, sch in ctx_schemas.items():
            # Skip entities not in this spec and non-object entries ("_comment" keys).
            if ent not in spec_entities or not isinstance(sch, dict):
                continue
            for fld, meta in (sch.get("properties") or {}).items():
                desc = meta.get("description") if isinstance(meta, dict) else None
                if desc:
                    entity_fields.setdefault(ent, {}).setdefault(fld, desc)
    facts, shape = load_spec(args.spec)
    ops, unmatched, diag = build(records, facts, shape)
    # Document alternative-form endpoints (e.g. POST .../private.delete) by copying their
    # documented twin's prose; appends op entries for endpoints with no doc section.
    apply_endpoint_aliases(ops)

    # invariant the structural gate also enforces: one entry per (method, path)
    keys = [(o["method"], o["path"]) for o in ops]
    if len(keys) != len(set(keys)):
        dups = sorted({k for k in keys if keys.count(k) > 1})
        sys.exit(f"duplicate operation keys in generated overrides: {dups}")

    # Mined field descriptions as an OpenAPI-shaped components.schemas fragment,
    # so the emitter deep-merges each into components/schemas/<Entity>/properties.
    schemas = {
        name: {"properties": {fld: {"description": desc} for fld, desc in sorted(fields.items())}}
        for name, fields in sorted(entity_fields.items())
    }
    out_doc = {"operations": ops}
    if schemas:
        out_doc["components"] = {"schemas": schemas}
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(out_doc, f, indent=2)
        f.write("\n")

    total = len(facts)
    with_summary = sum(1 for o in ops if o.get("summary"))
    with_example = sum(1 for o in ops if "requestBody" in o or "responses" in o)
    report = {
        "spec_operations": total,
        "generated_entries": len(ops),
        "with_summary": with_summary,
        "with_example": with_example,
        "coverage_pct": round(100 * with_summary / total, 1) if total else 0,
        "unmatched_doc_sections": sorted(unmatched, key=lambda u: u["endpoint"]),
        "skipped_examples": skipped,
        "dropped_request_examples": diag["dropped_request_examples"],
        # >1 doc section for one operation: exact-duplicate canon keys (parse) +
        # distinct doc keys that fall back to the same emitted op (build).
        "merged_sections": dupes + diag["merged_sections"],
    }
    if args.report:
        with open(args.report, "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
            f.write("\n")
    print(
        f"generated {len(ops)} entries; summaries {with_summary}/{total} "
        f"({report['coverage_pct']}%); examples {with_example}; "
        f"unmatched {len(unmatched)}; skipped {len(skipped)}; "
        f"dropped-req {len(diag['dropped_request_examples'])}; "
        f"merged {len(report['merged_sections'])}"
    )


if __name__ == "__main__":
    main()
