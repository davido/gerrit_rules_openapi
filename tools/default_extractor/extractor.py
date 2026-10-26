#!/usr/bin/env python3
"""Default extractor backend (SKELETON stand-in).

Emits a canonical OpenAPI 3.1 document from declared inputs so the ruleset's
toolchain/packaging/provider plumbing can be exercised end-to-end and tested for
byte-equality.

This is a PLACEHOLDER. The real backend is lifted *verbatim* from gerrit6 —
`java/com/google/gerrit/openapi/**` (the javac-AST REST inventory) plus
`tools/openapi/py/**` (the prose miner / overrides composer) — behind this same
CLI contract. Do NOT redesign extraction while relocating it (proposal §5).

CLI contract (stable; the real backend honors the same flags):
    --version V         info.version (passed in by the consumer)
    --out PATH          where to write the canonical openapi.json
    --src PATH          repeatable; consumer source inputs
                        (skeleton: --src[0] is a {"paths": {...}} fixture)
    --doc PATH          repeatable; REST prose doc inputs (skeleton: ignored)
    --overrides PATH    curated overrides JSON (skeleton: {"title": ...})
    --context PATH      pinned core-context input (skeleton: ignored)

Output is deterministic: json.dumps(indent=2, sort_keys=True) + trailing newline.
"""

import argparse
import json


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--version", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--src", action="append", default=[])
    p.add_argument("--doc", action="append", default=[])
    p.add_argument("--overrides")
    p.add_argument("--context")
    a = p.parse_args()

    paths = {}
    if a.src:
        with open(a.src[0], encoding="utf-8") as f:
            paths = json.load(f).get("paths", {})

    title = "Gerrit REST API"
    if a.overrides:
        with open(a.overrides, encoding="utf-8") as f:
            title = json.load(f).get("title", title)

    doc = {
        "openapi": "3.1.0",
        "info": {"title": title, "version": a.version},
        "paths": paths,
    }
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    main()
