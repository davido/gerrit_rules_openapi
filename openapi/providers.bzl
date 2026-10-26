"""Providers exposed by gerrit_rules_openapi.

The design mirrors rules_openapi / rules_jsonschema in *shape* (a small
toolchain provider + a typed artifact provider), but inverts the direction:
ours produces a spec from source; theirs consumes a spec to produce code.
"""

# The typed handoff. Emitted by openapi_spec; consumed by openapi_resource_jar
# (packaging), openapi_publish (distribution), the viewer plugin (runtime
# bundle), and optionally a downstream client-codegen ruleset.
GerritOpenApiSpecInfo = provider(
    doc = "A generated Gerrit OpenAPI document and its provenance.",
    fields = {
        "spec": "File: the canonical openapi.json.",
        "domains": "depset[File]: per-area slices (core only; empty for plugins).",
        "provenance": "File|None: {source_rev, ruleset_rev, digest, version} JSON.",
        "report": "File|None: the stats / overrides report.",
    },
)

# The swappable extractor backend. Stores a FilesToRunProvider — the executable
# PLUS its runfiles (jars, modules, interpreter) — not a bare File, because the
# real Java/Python launcher cannot run without its runfiles. Generation actions
# stage this through `tools` / `executable`.
GerritOpenApiExtractorToolchainInfo = provider(
    doc = "A source -> OpenAPI extractor backend.",
    fields = {
        "tool": "FilesToRunProvider: the extractor executable plus its runfiles.",
    },
)
