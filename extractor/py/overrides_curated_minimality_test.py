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

"""Gate: the curated overrides layer stays minimal.

Now that the emitter builds base + generated + curated, the checked-in
`openapi-overrides.json` is the *curated layer* -- the document envelope plus the
operation entries the generator cannot supply: hand-authored operations (no
generated match) and genuine per-field corrections that win over the generated
value (a sentence-cased summary, an edited description, an added example).

This test fails if a curated operation override is *fully derivable* -- every field
already equals the generated override -- because that entry is dead weight: the
generator owns it. Redundant entries usually mean the mined prose was re-checked-in,
or a doc fix was skipped in favour of a manual override. The gate keeps the sidecar
from silently re-accreting the bulk prose that belongs to the generator.

(This replaces the pre-swap parity gate, whose job -- proving the generator
reproduces the then-checked-in prose -- was discharged when the prose moved to the
generated build output.)

Usage: overrides_curated_minimality_test.py <curated-overrides.json>
                                            <generated-overrides.json>
"""

import json
import sys


def deep_eq(a, b):
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def main():
    curated = json.load(open(sys.argv[1], encoding="utf-8"))
    generated = {
        (o["method"], o["path"]): o
        for o in json.load(open(sys.argv[2], encoding="utf-8"))["operations"]
    }

    hand_authored, edits, redundant = [], [], []
    for op in curated.get("operations", []):
        key = (op["method"], op["path"])
        gen_op = generated.get(key)
        if gen_op is None:
            hand_authored.append(key)
            continue
        differing = [
            f
            for f, v in op.items()
            if f not in ("method", "path") and not deep_eq(v, gen_op.get(f))
        ]
        (edits if differing else redundant).append(key)

    print(
        f"curated layer: {len(curated.get('operations', []))} operation overrides "
        f"({len(hand_authored)} hand-authored, {len(edits)} per-field edits, "
        f"{len(redundant)} redundant)"
    )
    for key in redundant[:25]:
        print(f"  redundant (fully derivable): {key[0]} {key[1]}")

    if redundant:
        print(
            "MINIMALITY FAILURE: curated overrides duplicate the generated output; "
            "drop them, or fix the doc so the generator emits the intended value."
        )
        sys.exit(1)
    print("curated layer OK: every operation override is non-derivable")


if __name__ == "__main__":
    main()
