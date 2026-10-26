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

"""Unit tests for override_extractor: fixture AsciiDoc + a tiny spec, asserting
the exact output. Cases are built to reproduce specific parser bugs (verb lines
inside literal blocks, over-broad fallback, duplicate keys) so they stay fixed."""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import override_extractor as ox  # noqa: E402

FIXTURE = r"""= Test REST API

[[get-thing]]
=== Get Thing
--
'GET /things/link:#thing-id[\{thing-id\}]'
--

Retrieves a thing.

.Request
----
  GET /things/foo HTTP/1.0
----

.Response
----
  HTTP/1.1 200 OK

  )]}'
  {"id": "foo"}
----

[[list-things]]
=== List Things
--
'GET /things/'
--

Lists things. The quoted decoy inside the next block must be ignored:

----
  'GET /decoy/should-not-appear'
----

[[reindex]]
=== Reindex
--
'POST /idx/versions/#v[\{v\}]/reindex HTTP/1.0'
--

Reindex an index version.

[[search-docs]]
=== Search Documentation
--
'GET /Documentation/'
--

Searches the documentation.
"""

# Minimal spec: note /{ANY} is an all-placeholder one-segment path that the
# fallback must NOT match /Documentation/ to.
SPEC = {
    "paths": {
        "/things/{id}": {
            "get": {"responses": {"200": {"content": {"application/json": {"schema": {}}}}}}
        },
        "/things": {"get": {"responses": {"200": {}}}},
        "/idx/versions/{version}/reindex": {"post": {"responses": {"200": {}}}},
        "/{ANY}": {"get": {"responses": {"200": {}}}},
    }
}


class OverrideExtractorTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.doc = os.path.join(self.dir, "rest-api-fixture.txt")
        with open(self.doc, "w") as f:
            f.write(FIXTURE)
        self.records, self.skipped, self.dupes, *_ = ox.parse_docs([self.doc])
        # facts/shape from the in-memory spec
        specfile = os.path.join(self.dir, "spec.json")
        import json

        with open(specfile, "w") as sf:
            json.dump(SPEC, sf)
        self.facts, self.shape = ox.load_spec(specfile)
        self.ops, self.unmatched, self.diag = ox.build(self.records, self.facts, self.shape)
        self.by_key = {(o["method"], o["path"]): o for o in self.ops}

    def test_unique_keys(self):
        keys = [(o["method"], o["path"]) for o in self.ops]
        self.assertEqual(len(keys), len(set(keys)), "duplicate operation keys")

    def test_get_thing_single_entry_with_prose_and_example(self):
        e = self.by_key[("GET", "/things/{id}")]
        self.assertEqual(e["summary"], "Get Thing")
        self.assertEqual(e["description"], "Retrieves a thing.")
        self.assertEqual(
            e["responses"]["200"]["content"]["application/json"]["example"], {"id": "foo"}
        )

    def test_verb_line_inside_block_is_ignored(self):
        # the quoted decoy inside a ---- block must not become an endpoint
        self.assertNotIn(("GET", "/decoy/should-not-appear"), self.by_key)
        self.assertFalse(any("decoy" in o["path"] for o in self.ops))

    def test_trailing_http_and_bare_anchor_normalized(self):
        # 'POST /idx/versions/#v[{v}]/reindex HTTP/1.0' -> the spec param path
        self.assertIn(("POST", "/idx/versions/{version}/reindex"), self.by_key)

    def test_fallback_rejects_all_placeholder_candidate(self):
        # /Documentation/ must NOT be matched to the all-wildcard /{ANY}
        self.assertNotIn(("GET", "/{ANY}"), self.by_key)
        self.assertIn("get /documentation", [u["endpoint"].lower() for u in self.unmatched])

    def test_list_things_trailing_slash_matches(self):
        self.assertIn(("GET", "/things"), self.by_key)


def _facts_shape(spec_dict):
    import json

    d = tempfile.mkdtemp()
    f = os.path.join(d, "spec.json")
    with open(f, "w") as fh:
        json.dump(spec_dict, fh)
    return ox.load_spec(f)


class HelperTest(unittest.TestCase):
    """Unit tests for the pure text/path helpers (fine-grained regression pins)."""

    def test_canon(self):
        self.assertEqual(ox.canon("/a/{id}/b/"), "/a/{}/b")
        self.assertEqual(ox.canon("/"), "/")
        self.assertEqual(ox.canon("/a/{x}/{y}"), "/a/{}/{}")

    def test_clean_path(self):
        self.assertEqual(
            ox.clean_path("/x/snapshot.indexes HTTP/1.0"), "/x/snapshot.indexes"
        )  # trailing HTTP/x.y
        self.assertEqual(ox.clean_path(r"/a/link:#id[\{id\}]/b"), "/a/{id}/b")
        self.assertEqual(ox.clean_path(r"/a/#v[\{v\}]/b"), "/a/{v}/b")  # bare #anchor
        self.assertEqual(ox.clean_path(r"/a/link:x.html#id[\{id\}]"), "/a/{id}")  # cross-page link
        self.assertEqual(ox.clean_path("/x?q=1&n=5"), "/x")  # query dropped
        self.assertEqual(ox.clean_path("/a/myproj%2Fsub/b"), "/a/{}/b")  # url-encoded seg
        # url-encoded segment must collapse regardless of case/hyphens (project names)
        self.assertEqual(ox.clean_path("/a/MyProj%2Fsub/b"), "/a/{}/b")
        self.assertEqual(ox.clean_path("/a/My-Proj%2Fbranch/b"), "/a/{}/b")
        self.assertEqual(ox.clean_path(r"/a/<<id,\{id\}>>/b"), "/a/{id}/b")  # xref in path

    def test_clean_text_preserves_plus_strips_markup(self):
        # Finding 1: '+' must survive (do not corrupt "+2" / "C++")
        self.assertEqual(ox.clean_text("increase by +2 in C++"), "increase by +2 in C++")
        # Finding 2: bare #anchor[label] is unwrapped like link:/xref, but a bare
        # '#' with no [label] (e.g. "C#") must survive -- same hazard class as "+2"
        self.assertEqual(ox.clean_text("see #id[the id]"), "see the id")
        self.assertEqual(ox.clean_text("C# and F#"), "C# and F#")
        self.assertEqual(ox.clean_text("see link:x#id[the id]"), "see the id")
        self.assertEqual(ox.clean_text("<<id,the id>> and <<x>>"), "the id and x")
        self.assertEqual(ox.clean_text("a `mono`  word"), "a mono word")

    def test_trim(self):
        self.assertEqual(ox.trim("Short."), "Short.")
        self.assertLessEqual(len(ox.trim("Sent. " * 100)), 300)
        big = "x" * 400  # a single over-long sentence is not cut mid-sentence
        self.assertEqual(ox.trim(big), big)

    def test_json_body(self):
        self.assertEqual(ox.json_body(['  {"a": 1}'], False), {"a": 1})  # indented
        self.assertEqual(
            ox.json_body(["HTTP/1.1 200 OK", "", "  )]}'", '  {"b": 2}'], True), {"b": 2}
        )  # XSSI prefix dropped
        self.assertIsNone(ox.json_body(["no body here"], False))
        self.assertIsNone(ox.json_body(["  {bad,}"], False))  # invalid JSON


class MatchTest(unittest.TestCase):
    def test_exact_and_fallback(self):
        facts, shape = _facts_shape({"paths": {"/a/{id}/b": {"get": {}}, "/x/{p}": {"get": {}}}})
        self.assertEqual(ox.match(("get", "/a/{}/b"), facts, shape), ("get", "/a/{}/b"))  # exact
        self.assertEqual(
            ox.match(("get", "/x/concrete"), facts, shape), ("get", "/x/{}")
        )  # concrete seg -> param

    def test_rejects_all_wildcard_candidate(self):
        facts, shape = _facts_shape({"paths": {"/{ANY}": {"get": {}}}})
        self.assertIsNone(ox.match(("get", "/Documentation"), facts, shape))

    def test_ambiguous_returns_none(self):
        facts, shape = _facts_shape({"paths": {"/{x}/foo": {"get": {}}, "/a/{y}": {"get": {}}}})
        self.assertIsNone(ox.match(("get", "/a/foo"), facts, shape))


class ParseRobustnessTest(unittest.TestCase):
    def test_stray_request_marker_does_not_drop_rest(self):
        # Finding 3: a '.Request' with no following ---- block must not swallow
        # the rest of the file.
        doc = (
            "[[one]]\n=== One\n--\n'GET /one'\n--\n\n"
            ".Request appears in prose but has no block here.\n\n"
            "[[two]]\n=== Two\n--\n'GET /two'\n--\n\nTwo prose.\n"
        )
        d = tempfile.mkdtemp()
        p = os.path.join(d, "rest-api-x.txt")
        with open(p, "w") as f:
            f.write(doc)
        records, *_ = ox.parse_docs([p])
        self.assertIn(("get", "/two"), records)  # still parsed after the stray marker

    def test_exact_duplicate_sections_reported(self):
        # Two doc sections resolving to the same canonical
        # (method, path) with different prose -- the first wins, and the duplicate
        # must be reported (not silently merged away by records.setdefault).
        doc = (
            r"[[a]]\n=== Get Thing A\n--\n'GET /things/link:#id[\{id\}]'\n--\n"
            "\nFirst prose.\n\n"
            r"[[b]]\n=== Get Thing B\n--\n'GET /things/link:#id[\{id\}]'\n--\n"
            "\nSecond prose.\n"
        ).replace(r"\n", "\n")
        d = tempfile.mkdtemp()
        p = os.path.join(d, "rest-api-x.txt")
        with open(p, "w") as f:
            f.write(doc)
        records, _, dupes, *_ = ox.parse_docs([p])
        self.assertEqual(
            records[("get", "/things/{}")]["description"], "First prose."
        )  # first section wins
        self.assertEqual(len(dupes), 1)  # the duplicate is reported
        self.assertIn("Get Thing B", dupes[0]["source"])
        self.assertIn("Get Thing A", dupes[0]["first_source"])

    def test_endpoint_subheading_keeps_current_section_for_examples(self):
        # Level-4 endpoint subsections (for example "Task Options") must not detach
        # the following Request/Response examples from the level-3 endpoint section.
        doc = (
            "[[list-things]]\n=== List Things\n--\n'GET /things/'\n--\n\n"
            "Lists things.\n\n"
            "==== Thing Options\n"
            "Filter::\nOnly list matching things.\n+\n"
            ".Request\n----\n  GET /things/?filter=active HTTP/1.0\n----\n\n"
            '.Response\n----\n  HTTP/1.1 200 OK\n\n  )]}\'\n  [{"id": "a"}]\n----\n'
        )
        d = tempfile.mkdtemp()
        p = os.path.join(d, "rest-api-x.txt")
        with open(p, "w") as f:
            f.write(doc)
        records, *_ = ox.parse_docs([p])
        facts, shape = _facts_shape(
            {
                "paths": {
                    "/things": {
                        "get": {"responses": {"200": {"content": {"application/json": {}}}}}
                    }
                }
            }
        )
        ops, _, _ = ox.build(records, facts, shape)
        by_key = {(o["method"], o["path"]): o for o in ops}
        self.assertEqual(
            by_key[("GET", "/things")]["responses"]["200"]["content"]["application/json"][
                "example"
            ],
            [{"id": "a"}],
        )


# A fixture whose sole purpose is to exercise every report category the tool
# promises (dropped-req, build-side merge, invalid + alternate skips) plus a
# JSON-array response body -- these are the tool's user-facing worklist contract.
REPORT_FIXTURE = r"""= Report Test API

[[drop-req]]
=== Drop Request
--
'POST /noreqbody'
--

Posts a thing.

.Request
----
  POST /noreqbody HTTP/1.0

  {"x": 1}
----

[[alt]]
=== Alt Example
--
'GET /alt'
--

Gets alt.

.Response
----
  HTTP/1.1 200 OK

  )]}'
  {"first": 1}
----

.Response
----
  HTTP/1.1 200 OK

  )]}'
  {"second": 2}
----

[[bad]]
=== Bad Json
--
'GET /bad'
--

Gets bad.

.Response
----
  HTTP/1.1 200 OK

  )]}'
  {
    "x": ...
  }
----

[[arr]]
=== Array Response
--
'GET /arr'
--

Gets an array.

.Response
----
  HTTP/1.1 200 OK

  )]}'
  [{"a": 1}]
----

[[foo-a]]
=== Foo Alpha
--
'GET /coll/foo'
--

Foo alpha.

[[foo-b]]
=== Foo Beta
--
'GET /coll/bar'
--

Foo beta.
"""

REPORT_SPEC = {
    "paths": {
        "/noreqbody": {"post": {"responses": {"200": {}}}},  # no JSON requestBody
        "/alt": {"get": {"responses": {"200": {"content": {"application/json": {}}}}}},
        "/bad": {"get": {"responses": {"200": {"content": {"application/json": {}}}}}},
        "/arr": {"get": {"responses": {"200": {"content": {"application/json": {}}}}}},
        "/coll/{p}": {"get": {"responses": {"200": {}}}},  # foo & bar fall back here
    }
}


class ReportContractTest(unittest.TestCase):
    """The report is the tool's promise (a doc worklist); pin every category."""

    def setUp(self):
        d = tempfile.mkdtemp()
        doc = os.path.join(d, "rest-api-report.txt")
        with open(doc, "w") as f:
            f.write(REPORT_FIXTURE)
        self.records, self.skipped, self.dupes, *_ = ox.parse_docs([doc])
        self.facts, self.shape = _facts_shape(REPORT_SPEC)
        self.ops, self.unmatched, self.diag = ox.build(self.records, self.facts, self.shape)
        self.by_key = {(o["method"], o["path"]): o for o in self.ops}

    def _reasons(self):
        return [s.get("reason") for s in self.skipped]

    def test_request_example_dropped_when_spec_has_no_json_body(self):
        # POST /noreqbody has a .Request body but the spec models no JSON body.
        dropped = self.diag["dropped_request_examples"]
        self.assertEqual([d["endpoint"] for d in dropped], ["POST /noreqbody"])
        self.assertNotIn("requestBody", self.by_key[("POST", "/noreqbody")])

    def test_alternate_response_block_skipped_first_wins(self):
        self.assertIn("alternate example", self._reasons())
        # the first block is the one that lands in the override, not the second
        self.assertEqual(
            self.by_key[("GET", "/alt")]["responses"]["200"]["content"]["application/json"][
                "example"
            ],
            {"first": 1},
        )

    def test_invalid_json_example_skipped(self):
        # GET /bad has a `...`-elided body: reported, and no response attached
        self.assertIn("invalid JSON example", self._reasons())
        self.assertNotIn("responses", self.by_key[("GET", "/bad")])

    def test_json_array_response_body_is_extracted(self):
        # json_body must accept a top-level array, not only an object
        self.assertEqual(
            self.by_key[("GET", "/arr")]["responses"]["200"]["content"]["application/json"][
                "example"
            ],
            [{"a": 1}],
        )

    def test_distinct_doc_sections_folding_to_one_op_are_merged_once(self):
        # /coll/foo and /coll/bar both fall back to /coll/{p}: one emitted op,
        # reported as a build-side merge with the summary conflict surfaced.
        coll = [o for o in self.ops if o["path"] == "/coll/{p}"]
        self.assertEqual(len(coll), 1)  # deduped to a single op
        merged = self.diag["merged_sections"]
        self.assertEqual([m["endpoint"] for m in merged], ["GET /coll/{p}"])
        self.assertEqual(merged[0]["summary_conflict"], ["Foo Alpha", "Foo Beta"])
        self.assertEqual(self.dupes, [])  # not an exact-canon duplicate

    def test_output_is_sorted_by_path_then_method(self):
        keys = [(o["path"], o["method"]) for o in self.ops]
        self.assertEqual(keys, sorted(keys))


if __name__ == "__main__":
    unittest.main()
