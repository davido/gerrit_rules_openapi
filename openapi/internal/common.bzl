"""Shared implementation helpers. Not a public API."""

load("//openapi:providers.bzl", "GerritOpenApiSpecInfo")

visibility(["//openapi:__subpackages__"])

def spec_file(spec_target, fallback_file):
    """The spec File from a GerritOpenApiSpecInfo target, else the source file."""
    if GerritOpenApiSpecInfo in spec_target:
        return spec_target[GerritOpenApiSpecInfo].spec
    return fallback_file
