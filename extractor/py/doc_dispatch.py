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

"""Route each doc file to the right front-end and merge the results.

The only place that knows both dialects exist: it picks ``asciidoc_parser`` or
``markdown_parser`` per file (explicit ``doc_format`` or ``auto`` by ``.md``
extension) and lets each fill the shared accumulators, so a manifest may mix core
``.txt`` and plugin ``.md`` files in one run."""

import os

import asciidoc_parser
import doc_common
import markdown_parser


def parse_docs(paths, doc_format="auto", plugin=None):
    """Extract endpoint records from the doc files; most-specific section wins.

    ``doc_format`` selects the front-end tokenizer: ``asciidoc``, ``markdown``, or
    ``auto`` (per file, by ``.md`` extension). ``plugin``, when set, is the plugin's
    install name: the Gerrit doc token ``@PLUGIN@`` is expanded to it (as the doc build
    does) before tokenizing, so plugin verb-line paths like ``/plugins/@PLUGIN@/checkers``
    resolve to the real ``/plugins/<name>/...`` URL the generator emits.

    Returns (records, skipped_examples, duplicate_sections, entity_fields).
    ``duplicate_sections`` lists >1 doc section resolving to the same canonical
    ``(method, path)`` -- the first section's prose wins, the rest are reported (not
    silently merged). ``entity_fields`` maps each *Info/*Input entity to its
    {field: description} mined from the entity field tables."""
    state = doc_common.ParseState()
    for path in sorted(paths):
        base = os.path.basename(path)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        if plugin:
            text = text.replace("@PLUGIN@", plugin)
        lines = text.split("\n")
        fmt = doc_format
        if fmt == "auto":
            fmt = "markdown" if path.endswith(".md") else "asciidoc"
        parser = markdown_parser if fmt == "markdown" else asciidoc_parser
        parser.parse_file(lines, base, state)
    return (
        state.records,
        state.skipped_examples,
        state.duplicate_sections,
        state.entity_fields,
        state.entity_required,
    )
