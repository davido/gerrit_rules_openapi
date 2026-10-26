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

"""Markdown front-end for the override extractor (plugins, rest-api*.md).

Tokenizes ``### Title`` headings (with an optional leading ``<a id="...">`` anchor),
italic-wrapped ``_'VERB /path'_`` verb lines whose path carries ``[label](url)``
links, and ``#### Request`` / ``#### Response`` + fenced (```` ``` ````) blocks into
the shared endpoint record (see ``doc_common``). The AsciiDoc sibling is
``asciidoc_parser``; both feed the identical downstream in ``overrides_builder``."""

import re

import doc_common as dc

HEADING = re.compile(r"^#{1,6}\s+(.*)$")
ANCHOR = re.compile(r'<a id="[^"]*">\s*')
VERB = re.compile(r"^\s*_?'(GET|PUT|POST|DELETE|HEAD)\s+([^']*)'_?")
MDLINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")  # [label](url) -> label
ENTITY = re.compile(r"^`?([A-Z][A-Za-z0-9]*(?:Info|Input))`?$")  # entity heading text
TABLE_SEP = re.compile(r"^\s*\|[-:\s|]+\|\s*$")  # | --- | --- | separator row

# Structural prefixes that end an endpoint's lead-paragraph description.
STOPS = ("#", "```", "|", "*", "-", ">")


def parse_file(lines, base, state):
    title = None
    current = None
    entity = None  # the *Info/*Input entity whose field table we're under
    in_fence = False  # inside a ``` fenced block?
    i = 0
    while i < len(lines):
        line = lines[i]
        if line.strip().startswith("```"):  # fenced-block delimiter
            in_fence = not in_fence
            i += 1
            continue
        if in_fence:  # never read headings/verbs inside a fence
            i += 1
            continue
        h = HEADING.match(line)
        htext = ANCHOR.sub("", h.group(1)).strip() if h else None
        # A `#### Request`/`#### Response` heading is an example block, not a section.
        if h and current and htext.lower() in ("request", "response"):
            block, i = dc.read_block(lines, i, lambda s: s.strip().startswith("```"))
            if block is not None:
                dc.consume_example(block, htext.capitalize(), current, state)
            continue
        if h:
            title = htext
            current = None
            em = ENTITY.match(htext)
            entity = em.group(1) if em else None
            i += 1
            continue
        # entity field table: | Field Name | | Description | + separator + rows
        if (
            entity
            and line.lstrip().startswith("|")
            and "Field Name" in line
            and "Description" in line
        ):
            i += 1
            if i < len(lines) and TABLE_SEP.match(lines[i]):
                i += 1
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if len(cells) >= 2:
                    dc.record_field(entity, cells[0], cells[-1], state)
                i += 1
            continue
        v = VERB.match(line)
        if v and title:
            path = MDLINK.sub(r"\1", v.group(2))  # unwrap md links before shared clean
            current = dc.record_section(
                v.group(1), dc.canon(dc.clean_path(path)), title, base, state
            )
            dc.grab_description(lines, i, state, current, ("",), STOPS, lstrip=True)
        i += 1
