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

"""Diagnostic (non-failing): review the curated overrides layer against the current
generated overrides so stale hand-edits can be retired as the docs improve.

The curated layer wins per field, which is deliberate -- but a per-field edit
permanently masks the mined value, so an edit can quietly go stale when the doc it
was correcting later improves. Nothing else surfaces that (the minimality gate only
catches a *whole* operation becoming fully derivable). For each per-field edit this
report classifies it as:

  * absorbed  -- the generated value now equals the curated edit: dead weight, drop
                 it. (Once every field of an operation is absorbed, the minimality
                 gate fails.)
  * override  -- the curated value differs from a value the generator DOES produce:
                 the edit masks the mined value. Shown side by side so a human can
                 judge whether the doc has caught up and the edit can be dropped.
  * augment   -- the generator produces no such field; the curator adds it (e.g. a
                 hand-written example). Legitimately curator-owned.

A build output, not a gate: staleness is a judgement call, not a build error.

Usage: overrides_stale_edits.py <curated-overrides.json> <generated-overrides.json>
                                [<out-report.json>]
"""

import json
import sys


def deep_eq(a, b):
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def _short(v, n=60):
    s = v if isinstance(v, str) else json.dumps(v)
    return s if len(s) <= n else s[: n - 1] + "…"


def main():
    curated = json.load(open(sys.argv[1], encoding="utf-8"))
    generated = {
        (o["method"], o["path"]): o
        for o in json.load(open(sys.argv[2], encoding="utf-8"))["operations"]
    }

    hand_authored, absorbed, overrides, augments = [], [], [], []
    for op in curated.get("operations", []):
        key = f"{op['method']} {op['path']}"
        gen_op = generated.get((op["method"], op["path"]))
        if gen_op is None:
            hand_authored.append(key)
            continue
        for field, value in op.items():
            if field in ("method", "path"):
                continue
            gen_value = gen_op.get(field)
            if deep_eq(value, gen_value):
                absorbed.append({"op": key, "field": field})
            elif gen_value is None:
                augments.append({"op": key, "field": field})
            else:
                overrides.append(
                    {"op": key, "field": field, "curated": value, "generated": gen_value}
                )

    report = {
        "curated_operations": len(curated.get("operations", [])),
        "hand_authored": hand_authored,
        "absorbed": absorbed,
        "overrides": overrides,
        "augments": augments,
    }
    if len(sys.argv) > 3:
        with open(sys.argv[3], "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2)
            f.write("\n")

    print(
        f"curated layer: {len(hand_authored)} hand-authored ops, "
        f"{len(overrides)} overriding fields (review for doc catch-up), "
        f"{len(augments)} augmenting fields, "
        f"{len(absorbed)} absorbed fields (droppable)"
    )
    for o in overrides:
        print(
            f"  override {o['op']} .{o['field']}: "
            f"curated={_short(o['curated'])!r} generated={_short(o['generated'])!r}"
        )
    for a in absorbed:
        print(f"  absorbed {a['op']} .{a['field']} (drop: generator now supplies it)")


if __name__ == "__main__":
    main()
