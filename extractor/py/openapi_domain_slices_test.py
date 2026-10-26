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

"""Guard that the per-domain OpenAPI slices stay a faithful, fully-documented partition
of the all-domain monolith -- so the two published "worlds" can never drift.

Fed (as $(location) args, any order): the monolith spec, every per-domain slice spec,
all-domain-modules.txt (the monolith's module set) and domain-modules.txt (the union of
OPENAPI_DOMAINS). The checks:

  1. module partition -- union(OPENAPI_DOMAINS) == ALL_DOMAIN_MODULES, so no module is in
     the monolith but no slice (the omission that silently dropped gpgkeys/oauthtoken/
     metrics from the slices), nor in a slice but not the monolith.
  2. endpoint coverage -- union(slice endpoints) == monolith endpoints, disjoint slices.
  3. documentation parity -- no slice endpoint or field is LESS documented than the
     monolith's; endpoints the monolith itself leaves undocumented (a separate core-docs
     gap) stay parity-neutral rather than failing here.
  4. schema-name consistency -- every slice schema exists in the monolith, minus a
     recorded allowlist of collision-disambiguated names (FileInfo/DescriptionInput/
     Status) that an isolated per-domain emit does not rename.
"""

import json
import os
import sys
import unittest

_VERBS = ("get", "post", "put", "delete", "patch")


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

    def test_endpoint_documentation_parity(self):
        """A slice endpoint is documented iff the monolith documents it. This is the
        publishable bar: the slice must not LOSE prose the monolith has. Endpoints the
        monolith itself leaves undocumented (a separate core-docs gap) stay parity-neutral
        rather than failing here."""
        mono = {}
        for p, methods in MONOLITH.get("paths", {}).items():
            for v, o in methods.items():
                if v in _VERBS and isinstance(o, dict):
                    mono[(v, p)] = (bool(o.get("summary")), bool(o.get("description")))
        offenders = []
        for domain, spec in SLICES.items():
            for p, methods in spec.get("paths", {}).items():
                for v, o in methods.items():
                    if v not in _VERBS or not isinstance(o, dict):
                        continue
                    got = (bool(o.get("summary")), bool(o.get("description")))
                    want = mono.get((v, p))
                    if want is not None and got != want:
                        offenders.append(
                            "%s: %s %s slice=%s monolith=%s" % (domain, v.upper(), p, got, want)
                        )
        self.assertEqual(
            [],
            sorted(offenders),
            "slice endpoints less documented than the monolith: %s" % sorted(offenders),
        )

    def test_field_documentation_parity(self):
        """For every schema present under the same name in both the monolith and a slice,
        each shared field's description-presence must match -- the slice cannot drop a
        field description the monolith carries."""
        mono = (MONOLITH.get("components", {}) or {}).get("schemas", {}) or {}

        def described(schema, field):
            meta = (schema.get("properties") or {}).get(field)
            return isinstance(meta, dict) and bool(meta.get("description"))

        offenders = []
        for domain, spec in SLICES.items():
            schemas = (spec.get("components", {}) or {}).get("schemas", {}) or {}
            for name, schema in schemas.items():
                if name not in mono:
                    continue  # name divergence is asserted separately below
                for field in schema.get("properties") or {}:
                    if field in (mono[name].get("properties") or {}):
                        if described(schema, field) != described(mono[name], field):
                            offenders.append("%s: %s.%s" % (domain, name, field))
        self.assertEqual(
            [],
            sorted(offenders),
            "slice fields whose description-presence differs from the monolith: %s"
            % sorted(offenders),
        )

    # Collision-disambiguated types the monolith renames but an isolated per-domain emit
    # does not (e.g. the monolith's ProjectsDescriptionInput is a bare DescriptionInput in
    # the projects slice). A known artifact of per-module re-emit; recorded here so it is
    # explicit, not silent, and so a NEW divergence still fails. Closing these is tracked
    # as the follow-up to fully align slice schema names with the monolith.
    KNOWN_NAME_DIVERGENCES = {
        "FileInfo": "monolith disambiguates to CommonFileInfo/DiffFileMetaInfo variants",
        "DescriptionInput": "monolith renames to Projects/Groups DescriptionInput",
        "Status": "monolith disambiguates the account/config Status inputs",
    }

    def test_slice_schema_names_exist_in_monolith(self):
        mono = set((MONOLITH.get("components", {}) or {}).get("schemas", {}) or {})
        offenders = []
        for domain, spec in SLICES.items():
            for name in (spec.get("components", {}) or {}).get("schemas", {}) or {}:
                if name not in mono and name not in self.KNOWN_NAME_DIVERGENCES:
                    offenders.append("%s: %s" % (domain, name))
        self.assertEqual(
            [],
            sorted(offenders),
            "slice schema names absent from the monolith (new collision divergence -- add "
            "to KNOWN_NAME_DIVERGENCES only after confirming, or fix the naming): %s"
            % sorted(offenders),
        )


if __name__ == "__main__":
    # unittest ignores the $(location) args; strip them so it does not treat them as tests.
    unittest.main(argv=[sys.argv[0]])
