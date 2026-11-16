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

"""Guard that every per-domain OpenAPI slice is a faithful projection of the all-domain
monolith -- the invariant the projector (slice_projector.py) provides and this test
locks in, so the two published "worlds" can never drift.

Fed (as $(location) args, any order): the monolith spec, every per-domain slice spec,
all-domain-modules.txt (the monolith's module set) and domain-modules.txt (the union of
OPENAPI_DOMAINS). The checks:

  1. module partition -- union(OPENAPI_DOMAINS) == ALL_DOMAIN_MODULES, so no module is in
     the monolith but no slice (the omission that silently dropped gpgkeys/oauthtoken/
     metrics from the slices), nor in a slice but not the monolith.
  2. endpoint coverage -- union(slice endpoints) == monolith endpoints, disjoint slices.
  3. operation equality -- each slice operation is byte-equal (deep-equal) to the
     monolith's. Covers summary/description/parameters/responses in one assertion.
  4. schema equality -- every slice schema exists in the monolith under the SAME name and
     is deep-equal to it. This is what catches mis-attribution (plausible prose copied
     from the wrong entity), which a presence-only check cannot. No naming allowlist: a
     projected slice carries the monolith's names verbatim, so a divergent name is a bug.
  5. reference resolution -- every $ref in a slice resolves within that slice's own
     components (the projector must pull the full transitive schema closure).
"""

import json
import os
import sys
import unittest

_VERBS = ("get", "post", "put", "delete", "patch")
_REF_PREFIX = "#/components/schemas/"


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def _endpoints(spec):
    return {
        (v.upper(), p)
        for p, methods in spec.get("paths", {}).items()
        for v, o in methods.items()
        if v in _VERBS and isinstance(o, dict)
    }


def _operations(spec):
    return {
        (v.upper(), p): o
        for p, methods in spec.get("paths", {}).items()
        for v, o in methods.items()
        if v in _VERBS and isinstance(o, dict)
    }


def _schemas(spec):
    return (spec.get("components", {}) or {}).get("schemas", {}) or {}


def _ref_targets(obj, acc):
    """Collect every $ref target string (any component kind), so the test can both check
    schema refs resolve and reject non-schema refs the projector does not project."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k == "$ref" and isinstance(v, str):
                acc.add(v)
            else:
                _ref_targets(v, acc)
    elif isinstance(obj, list):
        for v in obj:
            _ref_targets(v, acc)


def _modules(path):
    with open(path, encoding="utf-8") as fh:
        return {line.strip() for line in fh if line.strip()}


def _classify(argv):
    """Sort the $(location) inputs by basename into (monolith, {domain: slice}, txts)."""
    monolith, slices, all_mods, domain_mods = None, {}, None, None
    for path in argv:
        base = os.path.basename(path)
        if base == "all-domain-openapi.generated.json":
            monolith = _load(path)
        elif base == "all-domain-modules.txt":
            all_mods = _modules(path)
        elif base == "domain-modules.txt":
            domain_mods = _modules(path)
        elif base.endswith("-openapi.generated.json"):
            slices[base[: -len("-openapi.generated.json")]] = _load(path)
    return monolith, slices, all_mods, domain_mods


MONOLITH, SLICES, ALL_MODULES, DOMAIN_MODULES = _classify(sys.argv[1:])


class DomainSlicesTest(unittest.TestCase):
    def test_inputs_present(self):
        self.assertIsNotNone(MONOLITH, "monolith spec not passed")
        self.assertIsNotNone(ALL_MODULES, "all-domain-modules.txt not passed")
        self.assertIsNotNone(DOMAIN_MODULES, "domain-modules.txt not passed")
        self.assertTrue(SLICES, "no per-domain slices passed")

    def test_module_partition(self):
        missing = ALL_MODULES - DOMAIN_MODULES
        extra = DOMAIN_MODULES - ALL_MODULES
        self.assertEqual(
            set(),
            missing,
            "modules in ALL_DOMAIN_MODULES but assigned to no OPENAPI_DOMAINS slice "
            "(their endpoints vanish from the split world): %s" % sorted(missing),
        )
        self.assertEqual(
            set(),
            extra,
            "modules in an OPENAPI_DOMAINS slice but not in ALL_DOMAIN_MODULES: %s" % sorted(extra),
        )

    def test_endpoint_coverage_is_a_partition(self):
        mono = _endpoints(MONOLITH)
        union = set()
        overlaps = {}
        for domain, spec in SLICES.items():
            for op in _endpoints(spec):
                if op in union:
                    overlaps.setdefault(op, []).append(domain)
                union.add(op)
        self.assertEqual(
            set(),
            mono - union,
            "endpoints in the monolith but in no slice: %s" % sorted(mono - union),
        )
        self.assertEqual(
            set(),
            union - mono,
            "endpoints in a slice but not the monolith: %s" % sorted(union - mono),
        )
        self.assertEqual({}, overlaps, "endpoints emitted by more than one slice: %s" % overlaps)

    def test_operations_equal_monolith(self):
        """Each slice operation is deep-equal to the monolith's -- a projection copies the
        operation object verbatim, so any difference (summary, description, parameters,
        responses) is drift."""
        mono = _operations(MONOLITH)
        offenders = []
        for domain, spec in SLICES.items():
            for key, op in _operations(spec).items():
                if mono.get(key) != op:
                    offenders.append("%s: %s %s" % (domain, key[0], key[1]))
        self.assertEqual(
            [],
            sorted(offenders),
            "slice operations that differ from the monolith: %s" % sorted(offenders),
        )

    def test_schemas_equal_monolith(self):
        """Every slice schema exists in the monolith under the SAME name and is deep-equal
        to it. Catches both missing descriptions and prose mis-attributed from a different
        entity (the projects DescriptionInput defect). No naming allowlist: a projected
        slice carries the monolith's disambiguated names, so a divergent name is a bug, not
        an accepted artifact."""
        mono = _schemas(MONOLITH)
        name_offenders = []
        content_offenders = []
        for domain, spec in SLICES.items():
            for name, schema in _schemas(spec).items():
                if name not in mono:
                    name_offenders.append("%s: %s" % (domain, name))
                elif mono[name] != schema:
                    content_offenders.append("%s: %s" % (domain, name))
        self.assertEqual(
            [],
            sorted(name_offenders),
            "slice schema names absent from the monolith (a projection must reuse the "
            "monolith's names -- fix the naming, do not allowlist): %s" % sorted(name_offenders),
        )
        self.assertEqual(
            [],
            sorted(content_offenders),
            "slice schemas whose content differs from the monolith (descriptions, required, "
            "fields): %s" % sorted(content_offenders),
        )

    def test_refs_resolve_within_slice(self):
        """Every $ref in a slice is a schema ref that resolves within that slice's own
        components. The projector projects ONLY #/components/schemas/*, so a $ref to any
        other component would be dropped and dangle; the test rejects such refs (not only
        unresolved schema refs) so the schemas-only assumption stays honest."""
        dangling = []
        unsupported = []
        for domain, spec in SLICES.items():
            present = set(_schemas(spec))
            targets = set()
            _ref_targets(spec.get("paths", {}), targets)
            _ref_targets(_schemas(spec), targets)
            for t in targets:
                if not t.startswith(_REF_PREFIX):
                    unsupported.append("%s: %s" % (domain, t))
                elif t[len(_REF_PREFIX):] not in present:
                    dangling.append("%s: %s" % (domain, t))
        self.assertEqual(
            [],
            sorted(unsupported),
            "slice $refs to a non-schema component (the projector projects only schemas, "
            "so these dangle -- add generic component projection): %s" % sorted(unsupported),
        )
        self.assertEqual(
            [],
            sorted(dangling),
            "slice $refs with no matching schema in the slice: %s" % sorted(dangling),
        )

    def test_framing_equals_monolith(self):
        """The document framing a slice copies wholesale from the monolith must be deep-equal
        to it: the dialect, info, servers, security and the securitySchemes component. A
        projection that dropped or altered any of these (e.g. lost the security config) would
        otherwise pass every other check."""
        mono_security_schemes = (MONOLITH.get("components", {}) or {}).get("securitySchemes")
        offenders = []
        for domain, spec in SLICES.items():
            for key in ("openapi", "info", "servers", "security", "tags"):
                if spec.get(key) != MONOLITH.get(key):
                    offenders.append("%s: %s" % (domain, key))
            if (spec.get("components", {}) or {}).get("securitySchemes") != mono_security_schemes:
                offenders.append("%s: components.securitySchemes" % domain)
        self.assertEqual(
            [],
            sorted(offenders),
            "slice framing/security that differs from the monolith: %s" % sorted(offenders),
        )


if __name__ == "__main__":
    # unittest ignores the $(location) args; strip them so it does not treat them as tests.
    unittest.main(argv=[sys.argv[0]])
