"""Providers exposed by gerrit_rules_openapi.

A typed artifact provider carrying a generated spec and its provenance.
"""

# The typed spec-artifact handoff. openapi_resource_jar accepts either a target
# carrying this or a committed openapi.json source file.
GerritOpenApiSpecInfo = provider(
    doc = "A generated Gerrit OpenAPI document and its provenance.",
    fields = {
        "spec": "File: the canonical openapi.json.",
        "domains": "depset[File]: per-area slices (core only; empty for plugins).",
        "provenance": "File|None: {source_rev, ruleset_rev, digest, version} JSON.",
        "report": "File|None: the stats / overrides report.",
    },
)
