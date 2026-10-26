"""Public (heavy): the REAL source->spec pipeline, lifted verbatim from gerrit's
tools/openapi/openapi.bzl.

`openapi_endpoint_report` runs the Java `endpoints` generator (via report_tool)
over the consumer's `roots` (java_library targets -> transitive source jars) for
a `modules` list, optionally applying `overrides`, emitting an inventory or an
OpenAPI document. `openapi_plugin_spec` wraps the full base -> mined-overrides ->
compose -> emit chain for a plugin. Load only in generation packages; tag targets
`manual`.
"""

load(
    "//openapi/internal:pipeline.bzl",
    _openapi_endpoint_golden_test = "openapi_endpoint_golden_test",
    _openapi_endpoint_report = "openapi_endpoint_report",
    _openapi_json_gate_test = "openapi_json_gate_test",
    _openapi_plugin_spec = "openapi_plugin_spec",
)

openapi_endpoint_report = _openapi_endpoint_report
openapi_plugin_spec = _openapi_plugin_spec
openapi_endpoint_golden_test = _openapi_endpoint_golden_test
openapi_json_gate_test = _openapi_json_gate_test
