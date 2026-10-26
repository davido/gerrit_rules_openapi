"""The extractor toolchain rule.

`gerrit_openapi_extractor_toolchain` wraps a single extractor executable as a
Bazel toolchain. The default is registered in //openapi:BUILD; a consumer swaps
it by declaring its own and `register_toolchains(...)`-ing it ahead of the
default. Preserve the current executable/input/output contract when swapping —
do not redesign extraction while relocating it.
"""

load(":providers.bzl", "GerritOpenApiExtractorToolchainInfo")

def _impl(ctx):
    return [platform_common.ToolchainInfo(
        extractor = GerritOpenApiExtractorToolchainInfo(
            # files_to_run captures the executable AND its runfiles.
            tool = ctx.attr.tool[DefaultInfo].files_to_run,
        ),
    )]

gerrit_openapi_extractor_toolchain = rule(
    implementation = _impl,
    attrs = {
        "tool": attr.label(
            executable = True,
            cfg = "exec",
            mandatory = True,
            doc = "The extractor executable. Its runfiles are preserved via " +
                  "FilesToRunProvider and staged into generation actions.",
        ),
    },
    doc = "Register a source->OpenAPI extractor executable as a Bazel toolchain.",
)
