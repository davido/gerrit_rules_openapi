"""Public (light): package a committed spec. Loads NO generation code.

A normal plugin BUILD loads only this file. `openapi_resource_jar` accepts a
committed `openapi.json` (a source file) or a GerritOpenApiSpecInfo and merges
it into a jar at `Documentation/rest-api-openapi.json`.
"""

load("//openapi/internal:packaging.bzl", _openapi_resource_jar = "openapi_resource_jar")

openapi_resource_jar = _openapi_resource_jar
