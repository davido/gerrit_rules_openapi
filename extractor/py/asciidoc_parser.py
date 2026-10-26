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

"""AsciiDoc front-end for the override extractor (Gerrit core rest-api-*.txt).

Tokenizes ``=== Title`` headings, single-quoted ``'VERB /path'`` verb lines, and
``.Request`` / ``.Response`` + ``----`` listing blocks into the shared endpoint
record (see ``doc_common``). The Markdown sibling is ``markdown_parser``."""

import re

import doc_common as dc

VERB = re.compile(r"^\s*'(GET|PUT|POST|DELETE|HEAD)\s+(/[^']*)'")
HEADING = re.compile(r"^====?\s+(.*)$")
BLOCK = re.compile(r"^\.(Request|Response)\b")
# Any CamelCase heading is a potential entity. We do NOT require an Info/Input
# suffix: entities like ReviewResult, FileChange, SubmitRecord, Requirement and
# ContextLine carry field tables too. This is safe because an entity name is only
# consumed when a FIELD_TABLE immediately follows (see parse_file), and
# field_table() only records rows when the header has a "Description" cell -- so a
# non-entity heading followed by some other table produces nothing, and an unknown
# schema name is a no-op in the emitter's applySchemaDescriptions.
ENTITY = re.compile(r"^====?\s+`?([A-Z][\w.]*)`?\s*$")
# An entity field table: [options="header",cols="..."] then a |=== ... |=== block.
# The column layout varies across the docs (1,^1,5 / 1,^2,4 / 1,6); accept any.
FIELD_TABLE = re.compile(r'^\[options="header",[^\]]*cols="[^"]*"[^\]]*\]')

# Structural prefixes that end an endpoint's lead-paragraph description.
STOPS = (".", "----", "==", "[[", "|", "*", "//")


def field_table(block, entity, state):
    """Parse a |Field Name|...|Description| table body into field descriptions. The
    column count varies (2 or 3); Field Name is always the first column and
    Description the last. The header row locates the column count (its last cell is
    ``Description``) and is then dropped by ``record_field``'s field-name guard."""
    cells = "\n".join(block).split("|")
    cells = cells[1:] if cells and cells[0].strip() == "" else cells
    ncols = next((i + 1 for i, c in enumerate(cells) if c.strip() == "Description"), 0)
    if not ncols:
        return
    for t in range(0, len(cells) - ncols + 1, ncols):
        dc.record_field(entity, cells[t], cells[t + ncols - 1], state)


def parse_file(lines, base, state):
    title = None
    current = None  # (method, path) of the last verb line in this section
    entity = None  # the CamelCase entity whose field table we're under
    in_literal = False  # inside a ---- listing block?
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip() == "----":  # listing-block delimiter (four dashes)
            in_literal = not in_literal
            i += 1
            continue
        if in_literal:  # never read headings/verbs inside a listing block
            i += 1
            continue
        h = HEADING.match(line)
        if h:
            title = h.group(1).strip()
            if line.lstrip().startswith("=== "):
                current = None
            em = ENTITY.match(line)
            entity = em.group(1) if em else None
        if entity and FIELD_TABLE.match(line):  # entity field table
            block, i = dc.read_block(lines, i, lambda s: s.startswith("|="))
            if block is not None:
                field_table(block, entity, state)
            continue
        v = VERB.match(line)
        if v and title:
            current = dc.record_section(
                v.group(1), dc.canon(dc.clean_path(v.group(2))), title, base, state
            )
            dc.grab_description(lines, i, state, current, ("", "--"), STOPS, lstrip=False)
        b = BLOCK.match(line)
        if b and current:  # .Request/.Response: consume the ---- block, skip past it
            block, i = dc.read_block(lines, i, lambda s: s.strip() == "----")
            if block is not None:
                dc.consume_example(block, b.group(1), current, state)
            continue
        i += 1
