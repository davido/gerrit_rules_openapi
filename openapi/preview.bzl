"""Public (dev): a local Swagger preview of a spec. Dev-only; off the normal
build path.
"""

load("//openapi/internal:preview.bzl", _openapi_dev_serve = "openapi_dev_serve")

openapi_dev_serve = _openapi_dev_serve
