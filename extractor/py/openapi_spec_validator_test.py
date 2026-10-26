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

"""Unit tests for openapi_spec_validator.mount_placeholders -- the guard that keeps
unresolved mount placeholders (e.g. {PLUGIN_KIND-root}) out of the published spec.

The guard is otherwise dormant: on the current, fixed spec it never fires, so nothing
would notice if it were silently broken. These cases exercise it directly, so breaking
the check turns a test red independently of whether the emitter ever regresses."""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import openapi_spec_validator as v  # noqa: E402


class MountPlaceholderTest(unittest.TestCase):
    def test_unresolved_placeholder_is_flagged(self):
        # The exact shape of the bug this guard exists for.
        self.assertEqual(v.mount_placeholders("/{PLUGIN_KIND-root}/enable"), ["PLUGIN_KIND-root"])
        self.assertEqual(
            v.mount_placeholders("/{PLUGIN_KIND-collection}"), ["PLUGIN_KIND-collection"]
        )

    def test_a_future_undiscoverable_kind_is_also_flagged(self):
        # Not specific to plugins: any <KIND>_KIND-<role> placeholder is caught.
        self.assertEqual(v.mount_placeholders("/{WIDGET_KIND-root}/sub"), ["WIDGET_KIND-root"])

    def test_resolved_resource_ids_are_not_flagged(self):
        # Real, resolved paths carry lowercase resource ids -- never _KIND.
        self.assertEqual(v.mount_placeholders("/plugins/{plugin-id}/enable"), [])
        self.assertEqual(v.mount_placeholders("/changes/{change-id}/detail"), [])
        self.assertEqual(v.mount_placeholders("/plugins"), [])

    def test_multiple_placeholders_sorted(self):
        self.assertEqual(
            v.mount_placeholders("/{B_KIND-root}/{A_KIND-collection}"),
            ["A_KIND-collection", "B_KIND-root"],
        )


class FieldDescriptionTest(unittest.TestCase):
    """check_field_descriptions guards against a new entity/field shipping without a
    mined description. Exercised directly so breaking it turns a test red regardless of
    the current spec's coverage."""

    @staticmethod
    def _doc(schemas):
        return {"components": {"schemas": schemas}}

    def test_all_fields_described_passes(self):
        v.check_field_descriptions(
            self._doc({"FooInput": {"properties": {"a": {"description": "A."}}}})
        )

    def test_missing_description_fails(self):
        with self.assertRaises(SystemExit):
            v.check_field_descriptions(self._doc({"FooInput": {"properties": {"a": {}}}}))

    def test_empty_description_fails(self):
        with self.assertRaises(SystemExit):
            v.check_field_descriptions(
                self._doc({"FooInput": {"properties": {"a": {"description": ""}}}})
            )

    def test_propertyless_schema_skipped(self):
        v.check_field_descriptions(self._doc({"MyEnum": {"enum": ["A", "B"]}}))

    def test_allowlisted_field_skipped(self):
        v.FIELD_DESCRIPTION_ALLOWLIST.add("FooInput.a")
        try:
            v.check_field_descriptions(self._doc({"FooInput": {"properties": {"a": {}}}}))
        finally:
            v.FIELD_DESCRIPTION_ALLOWLIST.discard("FooInput.a")


if __name__ == "__main__":
    unittest.main()
