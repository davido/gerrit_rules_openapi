"""Impl: the extractor toolchain rule. Not public (facade: //openapi:toolchains.bzl)."""

load("//openapi:providers.bzl", "GerritOpenApiExtractorToolchainInfo")

visibility(["//openapi:__subpackages__"])

def _impl(ctx):
    return [platform_common.ToolchainInfo(
        extractor = GerritOpenApiExtractorToolchainInfo(
            tool = ctx.attr.tool[DefaultInfo].files_to_run,  # executable + runfiles
        ),
    )]

gerrit_openapi_extractor_toolchain = rule(
    implementation = _impl,
    attrs = {
        "tool": attr.label(
            executable = True,
            cfg = "exec",
            mandatory = True,
            doc = "The extractor executable; runfiles preserved via FilesToRunProvider.",
        ),
    },
    doc = "Register a source->OpenAPI extractor executable as a Bazel toolchain.",
)
