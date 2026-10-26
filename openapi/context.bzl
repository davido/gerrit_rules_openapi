"""Public (heavy): bootstrap/produce the versioned core-context archive the
plugin extractor consumes as a pinned input. Load only in generation packages
and tag targets `manual`.
"""

load("//openapi/internal:context.bzl", _core_context_archive = "core_context_archive")

core_context_archive = _core_context_archive
