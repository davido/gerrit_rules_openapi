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

"""Front-end-agnostic helpers shared by the doc parsers.

The endpoint record the parsers produce (``method, path, title, description, req,
resp``) and the primitives that build it are identical for AsciiDoc and Markdown --
only the tokenizer differs. Those two tokenizers live in ``asciidoc_parser`` and
``markdown_parser``; this module holds everything they share so neither dialect
re-implements path/text normalization, section bookkeeping, or example parsing.
``override_extractor`` dispatches to a parser per file and consumes the records."""

import json
import re
from dataclasses import dataclass, field


@dataclass
class ParseState:
    """The accumulators the parsers fill while tokenizing one manifest of doc files:
    endpoint ``records`` keyed by canonical ``(method, path)``, plus two diagnostic
    lists. Passed as one object so the helpers below don't thread three trailing args."""

    records: dict = field(default_factory=dict)
    skipped_examples: list = field(default_factory=list)  # alternate / invalid-JSON blocks
    duplicate_sections: list = field(default_factory=list)  # >1 section for one (method, path)
    entity_fields: dict = field(default_factory=dict)  # {Entity: {field: description}}
    entity_required: dict = field(default_factory=dict)  # {Entity: [required field, ...]}
    doc_title: str = ""  # document title heading (first file's H1 wins)
    doc_description: str = ""  # lead intro paragraph after the title (first wins)


def canon(path):
    """Structural key: param names collapsed, trailing slash dropped."""
    path = re.sub(r"\{[^}]*\}", "{}", path)
    path = path.rstrip("/")
    return path or "/"


def clean_path(path):
    """Normalize a doc verb-line path to the spec's templated shape:
    strip trailing ``HTTP/x.y``, unwrap link:/#anchor/xref markup, unescape
    braces, drop query strings, and collapse url-encoded example segments."""
    path = re.sub(r"\s+HTTP/\d+\.\d+\s*$", "", path)  # strip trailing HTTP/1.0
    # link:...[text], cross-page link:page.html#a[text], and bare #anchor[text] -> text
    path = re.sub(r"(?:link:)?[^\s\[/]*\[([^\]]*)\]", r"\1", path)
    path = re.sub(r"<<[^,>]*,([^>]*)>>", r"\1", path)  # xref with label -> label
    path = re.sub(r"<<([^>]*)>>", r"\1", path)  # bare xref -> anchor
    path = path.replace("\\{", "{").replace("\\}", "}").replace("\\~", "~")
    path = re.sub(r"\?.*$", "", path)  # drop query string
    # url-encoded example segment (a %2f-bearing path param, any case/hyphen): -> {}
    path = re.sub(r"/[^/]*%2[fF][^/]*", "/{}", path)
    return path.strip()


def clean_text(text):
    """Reduce AsciiDoc prose to plain text: unwrap cross-references and inline
    formatting to their visible content, then collapse whitespace."""
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)  # [label](url) -> label (Markdown)
    text = re.sub(r"link:[^\[]*\[([^\]]*)\]", r"\1", text)  # link:...[label] (URLs ok)
    text = re.sub(r"#[^\s\[/]*\[([^\]]*)\]", r"\1", text)  # bare #anchor[label]
    text = re.sub(r"<<[^,>]*,([^>]*)>>", r"\1", text)  # <<anchor,label>>
    text = re.sub(r"<<([^>]*)>>", r"\1", text)  # <<anchor>>
    text = re.sub(r"`([^`]*)`", r"\1", text)  # `monospace` -> monospace
    # NB: AsciiDoc `+passthrough+` is left as-is on purpose -- stripping `+` is
    # ambiguous (it would corrupt prose like "+2" or "C++"); a stray `+` around a
    # rare passthrough is cosmetic, mangled numbers/operators are not.
    text = re.sub(r"\s+", " ", text).strip()
    return text


def trim(desc, limit=300):
    """Trim a description to the first sentence(s) within ``limit`` chars."""
    if len(desc) <= limit:
        return desc
    out = ""
    for sentence in re.split(r"(?<=[.!?])\s+", desc):
        if out and len(out) + len(sentence) > limit:
            break
        out = (out + " " + sentence).strip()
    return out


def json_body(block_lines, is_response):
    """Extract a JSON body from a request/response example block, or None."""
    body = "\n".join(block_lines)
    if is_response:
        # drop status line, headers, and the XSSI magic prefix
        m = re.search(r"\)\]\}'\s*\n", body)
        if m:
            body = body[m.end() :]
    start = re.search(r"[\{\[]", body)
    if not start:
        return None
    try:  # json.loads is whitespace-insensitive, so the doc's indentation is fine
        return json.loads(body[start.start() :])
    except ValueError:
        return None


FIELD_NAME = re.compile(r"[a-z_][a-z0-9_]*")


def record_field(entity, name, desc, state):
    """Record one entity field's description (first non-empty wins). ``name`` is the
    snake_case field, matching the schema property name; the header row and any
    non-field cell are dropped by the ``FIELD_NAME`` guard."""
    name = name.strip().strip("`").strip()
    desc = trim(clean_text(desc))
    if entity and desc and FIELD_NAME.fullmatch(name):
        state.entity_fields.setdefault(entity, {}).setdefault(name, desc)


# Subtypes the docs describe as "has the same fields as <Base>. In addition ...":
# the rest-api docs are DRY and table only the extra fields, but the emitted spec is
# flat -- every inherited field is inlined into the subtype -- so the inherited fields
# come out undescribed. Hard-coded rather than data-filed: the set is tiny and purely
# structural, changing only when the API adds an entity that reuses another's fields.
SAME_FIELDS_AS = {
    "AccountDetailInfo": "AccountInfo",
    "ReviewerInfo": "AccountInfo",
    "ApprovalInfo": "AccountInfo",
    # The comment family: RobotCommentInfo extends CommentInfo, and DraftInput shares
    # the Comment base with it. CommentInfo is the richly-described source (CommentInput
    # itself is undescribed), so both draw from it for their shared fields.
    "RobotCommentInfo": "CommentInfo",
    "DraftInput": "CommentInfo",
    # TestSubmitRuleInfo documents itself as "has the same fields as SubmitRecord".
    "TestSubmitRuleInfo": "SubmitRecord",
}


def apply_inherited_fields(entity_fields):
    """Fill each subtype in ``SAME_FIELDS_AS`` with its base entity's mined field
    descriptions, for same-named fields it does not already describe (``setdefault``
    so a subtype that re-documents a field keeps its own text). Mutates in place."""
    for sub, base in SAME_FIELDS_AS.items():
        dst = entity_fields.setdefault(sub, {})
        for fld, desc in entity_fields.get(base, {}).items():
            dst.setdefault(fld, desc)


# Entities the docs name differently from the Java class the spec is keyed on: the
# rest-api docs use human-friendly names (GitPersonInfo, CommentRange, ...) while the
# emitted schema uses the DTO class name (GitPerson, Range, ...). Map spec name ->
# doc section name so the doc section's descriptions can be recorded under the spec
# name. Verified field-for-field equal to the spec entity (see
# missing_entities_documentation.md).
DOC_ENTITY_ALIAS = {
    "GitPerson": "GitPersonInfo",
    "Range": "CommentRange",
    "FileMeta": "DiffFileMetaInfo",
    "ContentEntry": "DiffContent",
    "AgreementInfo": "ContributorAgreementInfo",
    "MenuItem": "TopMenuItemInfo",
    "GarbageCollectInput": "GCInput",
    "EditIdentityInput": "ChangeEditIdentityInput",
    "PostInput": "ChangeEditInput",
    "CleanupChangesInput": "CleanChanges.Input",
    "CommonFileInfo": "FileInfo",
    "ParentInput": "ProjectParentInput",
    "NameInput": "AccountNameInput",
    "StatusInput": "AccountStatusInput",
    "AgreementInput": "ContributorAgreementInput",
    "PostCachesInput": "CacheOperationInput",
    "ConfirmEmailInput": "EmailConfirmationInput",
    "AccountsInfo": "AccountsConfigInfo",
    "GroupsInfo": "GroupsConfigInfo",
    "MenuEntry": "TopMenuEntryInfo",
    "SnapshotIndexInput": "SnapshotIndex.Input",
    "MigratePasswordsToTokensInput": "MigratePasswordsToTokens.Input",
    "EditMessageInput": "ChangeEditMessageInput",
    "WorkInProgressOpInput": "WorkInProgressInput",
    "CommonDescriptionInput": "DescriptionInput",
    "AddMembersInput": "MembersInput",
    "BanCommitInput": "BanInput",
    "SetDashboardInput": "DashboardInput",
    "ProjectsDescriptionInput": "ProjectDescriptionInput",
    "AddSubgroupsInput": "GroupsInput",
    "TestSubmitRuleInput": "RuleInput",
    "InstallPluginInput": "PluginInput",
    "ReduceMaxTokenLifetimeInput": "ReduceMaxTokenLifetime.Input",
    "ReindexIndexVersionInput": "ReindexIndexVersion.Input",
    "SnapshotIndexVersionInput": "SnapshotIndexVersion.Input",
    "SnapshotIndexesInput": "SnapshotIndexes.Input",
    "GetMetaFileInfo": "GetMeta.FileInfo",
    "Label": "SubmitRecordInfo.Label",
}


def apply_entity_aliases(entity_fields, extra=None):
    """Record each spec entity's fields from its differently-named doc section (see
    DOC_ENTITY_ALIAS). ``setdefault`` keeps any field mined directly under the spec
    name. ``extra`` is an optional plugin-scoped {spec_name: doc_name} map merged on
    top of the shared core table, so a plugin can alias its own entities without
    adding them to the core-shared table. Mutates in place."""
    aliases = dict(DOC_ENTITY_ALIAS)
    if extra:
        aliases.update(extra)
    for spec_name, doc_name in aliases.items():
        src = entity_fields.get(doc_name)
        if not src:
            continue
        dst = entity_fields.setdefault(spec_name, {})
        for fld, desc in src.items():
            dst.setdefault(fld, desc)


# Alternative-form endpoints: a spec operation that shares its handler with an already
# documented operation under a second (method, path) but has no doc section of its own
# -- e.g. POST .../private.delete is the form twin of DELETE .../private, and the
# revision-scoped reviewer endpoints mirror their change-scoped originals. Keyed alias
# operation -> source operation, using the real spec paths (not canonicalized).
ENDPOINT_ALIAS = {
    ("POST", "/changes/{change-id}/private.delete"): ("DELETE", "/changes/{change-id}/private"),
    ("POST", "/plugins/{plugin-id}/gerrit~disable"): ("DELETE", "/plugins/{plugin-id}"),
    ("POST", "/changes/{change-id}/revisions/{revision-id}/reviewers"): (
        "POST",
        "/changes/{change-id}/reviewers",
    ),
    ("GET", "/changes/{change-id}/revisions/{revision-id}/reviewers/{reviewer-id}"): (
        "GET",
        "/changes/{change-id}/reviewers/{reviewer-id}",
    ),
    ("DELETE", "/changes/{change-id}/revisions/{revision-id}/reviewers/{reviewer-id}"): (
        "DELETE",
        "/changes/{change-id}/reviewers/{reviewer-id}",
    ),
    ("POST", "/changes/{change-id}/revisions/{revision-id}/reviewers/{reviewer-id}/delete"): (
        "POST",
        "/changes/{change-id}/reviewers/{reviewer-id}/delete",
    ),
    (
        "DELETE",
        "/changes/{change-id}/revisions/{revision-id}/reviewers/{reviewer-id}/votes/{vote-id}",
    ): ("DELETE", "/changes/{change-id}/reviewers/{reviewer-id}/votes/{vote-id}"),
    # The branch-scoped file diff is the same GetDiffFile handler (FILE_KIND) as the
    # documented commit-scoped file diff.
    ("GET", "/projects/{project-id}/branches/{branch-id}/files/{file-id}/diff"): (
        "GET",
        "/projects/{project-id}/commits/{commit-id}/files/{file-id}/diff",
    ),
}


def apply_endpoint_aliases(ops):
    """Fill an alternative-form endpoint's summary/description/tags from its documented
    twin (see ENDPOINT_ALIAS): a spec endpoint that shares a handler with a documented
    one but has no doc section of its own. Creates the alias op if absent, or fills only
    the fields it is missing (so an alias that a section already half-documents -- e.g.
    an OR-form with a summary but no description -- is completed, not clobbered). A
    missing source is skipped."""
    by_key = {(o["method"], o["path"]): o for o in ops}
    for (am, ap), (sm, sp) in ENDPOINT_ALIAS.items():
        src = by_key.get((sm, sp))
        if not src:
            continue
        dst = by_key.get((am, ap))
        if dst is None:
            dst = {"method": am, "path": ap}
            ops.append(dst)
            by_key[(am, ap)] = dst
        for k in ("summary", "description", "tags"):
            if src.get(k) and not dst.get(k):
                dst[k] = src[k]


def record_required(entity, name, optionality, state):
    """A field whose optionality column is empty is always present -> required. Any
    text there ('optional', 'only set for ...', 'not set if ...') means not required."""
    name = name.strip().strip("`").strip()
    if entity and FIELD_NAME.fullmatch(name) and not optionality.strip():
        state.entity_required.setdefault(entity, [])
        if name not in state.entity_required[entity]:
            state.entity_required[entity].append(name)


def note_duplicate(current, title, base, state):
    """Record a second doc section resolving to an already-seen (method, path)."""
    if current in state.records:
        state.duplicate_sections.append(
            {
                "endpoint": f"{current[0].upper()} {current[1]}",
                "source": f"{base}:{title}",
                "first_source": state.records[current]["source"],
            }
        )


def record_section(method, path_key, title, base, state):
    """Open (or find) the record for one endpoint section, tracking a repeat of the
    same canonical (method, path). Returns the ``current`` key."""
    current = (method.lower(), path_key)
    note_duplicate(current, title, base, state)
    state.records.setdefault(
        current,
        {
            "method": method.upper(),
            "title": title,
            "description": "",
            "req": None,
            "resp": None,
            "source": f"{base}:{title}",
        },
    )
    return current


def grab_description(lines, i, state, current, blank_skip, stops, lstrip):
    """The first prose paragraph after the verb line at index ``i``. ``stops`` are
    the dialect's structural prefixes; ``lstrip`` matches them after leading
    whitespace (Markdown) or against the raw line (AsciiDoc)."""
    j = i + 1
    while j < len(lines) and lines[j].strip() in blank_skip:
        j += 1
    para = []
    while j < len(lines) and lines[j].strip():
        probe = lines[j].lstrip() if lstrip else lines[j]
        if probe.startswith(stops):
            break
        para.append(lines[j])
        j += 1
    desc = trim(clean_text(" ".join(para)))
    if desc and desc not in ("OR", "AND") and not state.records[current]["description"]:
        state.records[current]["description"] = desc


def record_doc_title(title, state):
    """The document's title heading (first non-empty wins across the file manifest)."""
    title = (title or "").strip()
    if title and not state.doc_title:
        state.doc_title = title


def record_doc_description(lines, i, state, blank_skip, stops, lstrip):
    """The lead prose paragraph after the document title at index ``i`` (first wins).
    Mirrors ``grab_description`` but fills the document-level envelope rather than an
    endpoint record: ``stops`` are the dialect's structural prefixes, ``lstrip``
    matches them after leading whitespace (Markdown) or against the raw line
    (AsciiDoc)."""
    if state.doc_description:
        return
    j = i + 1
    while j < len(lines) and lines[j].strip() in blank_skip:
        j += 1
    para = []
    while j < len(lines) and lines[j].strip():
        probe = lines[j].lstrip() if lstrip else lines[j]
        if probe.startswith(stops):
            break
        para.append(lines[j])
        j += 1
    desc = trim(clean_text(" ".join(para)))
    if desc:
        state.doc_description = desc


def read_block(lines, start, is_end):
    """Collect a delimited example block's body lines. ``is_end(line)`` marks the
    delimiter. Returns (body_lines, index_after_closing_delimiter) or (None, start+1)
    when no block follows (a stray marker)."""
    j = start + 1
    while j < len(lines) and not is_end(lines[j]):
        j += 1
    if j >= len(lines):  # no block follows: a stray marker
        return None, start + 1
    j += 1  # first content line
    block = []
    while j < len(lines) and not is_end(lines[j]):
        block.append(lines[j])
        j += 1
    return block, j + 1  # skip past the closing delimiter


def consume_example(block, kind, current, state):
    """Parse a request/response example block into the current record, or report it
    (invalid JSON / alternate). Dialect-agnostic -- the delimiters are stripped by
    the caller, only the body lines reach here."""
    body = json_body(block, kind == "Response")
    slot = "req" if kind == "Request" else "resp"
    where = {
        "endpoint": f"{current[0].upper()} {current[1]}",
        "kind": kind,
        "source": state.records[current]["source"],
    }
    if body is None:
        if re.search(r"[\{\[]", "\n".join(block)):  # had a body, but bad JSON
            where["reason"] = "invalid JSON example"
            state.skipped_examples.append(where)
    elif state.records[current][slot] is None:
        state.records[current][slot] = body
    else:
        where["reason"] = "alternate example"
        state.skipped_examples.append(where)
