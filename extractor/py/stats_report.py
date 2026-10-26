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

"""Print surface and documentation-mining statistics for the generated OpenAPI spec.

Reads the composed OpenAPI document and the doc miner's worklist report and prints
endpoint / schema / documentation-coverage counts, colored with Gerrit's own Web UI
palette (the same styling as the gerrit-sdk-python examples). Read-only -- this is a
report, not a gate; the failing drift guards live in openapi_spec_validator.py.

    bazelisk run //tools/openapi:stats
"""

import argparse
import collections
import json
import os
import sys

METHODS = ("get", "put", "post", "delete", "patch", "head")

# ---- color / styling ----------------------------------------------------------
# Zero-dependency ANSI, disabled when stdout is not a TTY, on NO_COLOR, or --no-color;
# forced through a pipe with CLICOLOR_FORCE. Mirrors gerrit-sdk-python/examples.

USE_COLOR = False
BOLD = "1"
DIM = "2"
# Borrowed verbatim from Gerrit's Web UI theme (polygerrit-ui app-theme.ts).
GRAY_700 = (95, 99, 104)
GREEN_700 = (24, 128, 56)  # --status-ready / satisfied / additions
YELLOW_700 = (242, 153, 0)  # --status-active
BLUE_700 = (25, 103, 210)  # links
HEADER_INDIGO = (62, 78, 138)  # Gerrit top-bar background, used for rules


def compute_color(no_color):
    if no_color or os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("CLICOLOR_FORCE"):  # force through a pipe
        return True
    return sys.stdout.isatty()


def sgr(s, code):
    return f"\x1b[{code}m{s}\x1b[0m" if USE_COLOR else s


def fg(s, rgb):
    return f"\x1b[38;2;{rgb[0]};{rgb[1]};{rgb[2]}m{s}\x1b[0m" if USE_COLOR else s


def rule():
    return fg("─" * 46, HEADER_INDIGO)


def section(title):
    print()
    print("  " + sgr(title.upper(), BOLD))


def row(label, value):
    # Pad the visible label BEFORE coloring -- ANSI escape bytes would otherwise be
    # counted and break column alignment when color is on.
    print(f"    {sgr(f'{label:<29}', DIM)}{value}")


def num(n):
    return sgr(str(n), BOLD)


def attn(n):
    # A non-zero count flags work to do (yellow); zero is calm green.
    return fg(str(n), YELLOW_700 if n else GREEN_700)


AREA_METHODS = ("GET", "POST", "PUT", "DELETE")


def area_counts(paths):
    """Per-area (OpenAPI tag) counts: for each tag, operations, per-method counts, and
    the share carrying both a summary and a description (the endpoint-drift guard's
    notion). Shared by the terminal breakdown and the machine-readable --json output."""
    areas = collections.defaultdict(collections.Counter)
    for node in paths.values():
        for method, op in node.items():
            if method not in METHODS:
                continue
            for tag in op.get("tags") or ["(untagged)"]:
                c = areas[tag]
                c["ops"] += 1
                c[method.upper()] += 1
                if op.get("summary") and op.get("description"):
                    c["documented"] += 1
    return areas


def print_areas(areas):
    """Render the per-area breakdown from precomputed area_counts()."""
    section("Areas (by tag)")
    header = "%-13s%5s%s%7s" % ("area", "ops", "".join("%7s" % m for m in AREA_METHODS), "docs")
    print("    " + sgr(header, DIM))
    for tag, c in sorted(areas.items(), key=lambda kv: -kv[1]["ops"]):
        ops = c["ops"]
        cells = sgr("%-13s" % tag, DIM) + num("%5d" % ops)
        cells += "".join(fg("%7d" % c[m], BLUE_700) for m in AREA_METHODS)
        pct = "%d%%" % (100 * c["documented"] // ops if ops else 0)
        cells += fg("%7s" % pct, GREEN_700 if c["documented"] == ops else YELLOW_700)
        print("    " + cells)


def main():
    ap = argparse.ArgumentParser(description="OpenAPI surface + mining statistics.")
    ap.add_argument("spec", help="composed openapi.json")
    ap.add_argument("report", help="openapi-overrides-report.json")
    ap.add_argument("--no-color", action="store_true")
    ap.add_argument(
        "--per-area",
        action="store_true",
        help="append a per-area (tag) breakdown: changes, projects, ...",
    )
    ap.add_argument(
        "--json",
        action="store_true",
        help="emit the computed statistics as JSON (machine-readable, the "
        "single source the published gerrit-openapi stats render from)",
    )
    args = ap.parse_args()

    global USE_COLOR
    USE_COLOR = compute_color(args.no_color)

    spec = json.load(open(args.spec))
    report = json.load(open(args.report))
    paths = spec.get("paths") or {}
    schemas = (spec.get("components") or {}).get("schemas") or {}

    by_method = collections.Counter()
    ops = with_body = with_params = 0
    for node in paths.values():
        for method, op in node.items():
            if method not in METHODS:
                continue
            ops += 1
            by_method[method.upper()] += 1
            if op.get("requestBody") or op.get("x-gerrit-suppressed-request-body"):
                with_body += 1
            if op.get("parameters"):
                with_params += 1
    tags = {
        t
        for node in paths.values()
        for op in node.values()
        if isinstance(op, dict)
        for t in op.get("tags", [])
    }
    fields = sum(len(s.get("properties") or {}) for s in schemas.values())
    enums = sum(1 for s in schemas.values() if s.get("enum"))
    areas = area_counts(paths)

    if args.json:
        # The canonical machine artifact: exactly the numbers rendered below, so the
        # published gerrit-openapi stats render this instead of recomputing anything.
        stats = {
            "version": spec.get("info", {}).get("version") or "?",
            "endpoints": {
                "paths": len(paths),
                "operations": ops,
                "by_method": {m: by_method[m] for m in sorted(by_method)},
                "with_request_body": with_body,
                "with_params": with_params,
                "domains": len(tags),
            },
            "schemas": {"count": len(schemas), "enums": enums, "fields": fields},
            "documentation": {
                "spec_operations": report.get("spec_operations", 0),
                "mined_entries": report.get("generated_entries", 0),
                "with_summary": report.get("with_summary", 0),
                "with_example": report.get("with_example", 0),
                "coverage_pct": report.get("coverage_pct", 0),
                "unmatched_doc_sections": len(report.get("unmatched_doc_sections", [])),
                "skipped_examples": len(report.get("skipped_examples", [])),
                "dropped_request_examples": len(report.get("dropped_request_examples", [])),
                "merged_sections": len(report.get("merged_sections", [])),
            },
            "by_area": {
                tag: {k: c[k] for k in ("ops", *AREA_METHODS, "documented")}
                for tag, c in sorted(areas.items(), key=lambda kv: -kv[1]["ops"])
            },
        }
        json.dump(stats, sys.stdout, indent=2)
        sys.stdout.write("\n")
        return

    print(rule())
    print(
        "  "
        + sgr("OpenAPI statistics", BOLD)
        + "  "
        + fg("Gerrit " + (spec.get("info", {}).get("version") or "?"), GRAY_700)
    )
    print(rule())

    section("Endpoints")
    row("paths (URL templates)", num(len(paths)))
    row("operations (method + path)", num(ops))
    for method, count in sorted(by_method.items(), key=lambda kv: -kv[1]):
        row("  " + method, fg(str(count), BLUE_700))
    row("with request body", num(with_body))
    row("with query/path params", num(with_params))
    row("domains (tags)", num(len(tags)))

    section("Schemas / fields")
    row("component schemas", num(len(schemas)) + sgr(" (%d enums)" % enums, DIM))
    row("properties (fields)", num(fields))

    section("Documentation mining")
    row("spec operations", num(report.get("spec_operations", 0)))
    row("mined entries", num(report.get("generated_entries", 0)))
    row("with summary", num(report.get("with_summary", 0)))
    row("with example", num(report.get("with_example", 0)))
    cov = report.get("coverage_pct", 0)
    cov_ok = isinstance(cov, (int, float)) and cov >= 99
    row("coverage", fg("%s%%" % cov, GREEN_700 if cov_ok else YELLOW_700))
    row("unmatched doc sections", attn(len(report.get("unmatched_doc_sections", []))))
    row("skipped examples", attn(len(report.get("skipped_examples", []))))
    row("dropped request examples", attn(len(report.get("dropped_request_examples", []))))
    row("merged (duplicate) sections", attn(len(report.get("merged_sections", []))))

    if args.per_area:
        print_areas(areas)


if __name__ == "__main__":
    main()
