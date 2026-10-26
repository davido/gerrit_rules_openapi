"""Public (heavy): generation. Load ONLY in dedicated generation packages and
tag the targets `manual` so `bazel build //...` does not select them.

  openapi_spec     — the extractor rule; returns GerritOpenApiSpecInfo.
  openapi_export   — runfiles-aware executable that reports the produced spec.
  openapi_stats    — runnable: prints the stats contract for a spec.
  openapi_publish  — runnable: stages a spec into a chosen dir (no commit/push).
"""

load(
    "//openapi/internal:generate.bzl",
    _openapi_export = "openapi_export",
    _openapi_publish = "openapi_publish",
    _openapi_spec = "openapi_spec",
    _openapi_stats = "openapi_stats",
)

openapi_spec = _openapi_spec
openapi_export = _openapi_export
openapi_stats = _openapi_stats
openapi_publish = _openapi_publish
