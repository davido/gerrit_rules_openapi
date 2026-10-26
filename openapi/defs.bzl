"""Light entry point: package a committed spec. No generator dependency.

A normal plugin BUILD loads ONLY this file. `openapi_resource_jar` accepts a
committed `openapi.json` (a source file) or a GerritOpenApiSpecInfo, and merges
it into a jar at `Documentation/rest-api-openapi.json` (merged at final
packaging — the packaging-cycle avoidance from the current pipeline).
"""

load(":providers.bzl", "GerritOpenApiSpecInfo")

def _resource_jar_impl(ctx):
    spec = ctx.attr.spec[GerritOpenApiSpecInfo].spec if GerritOpenApiSpecInfo in ctx.attr.spec else ctx.file.spec
    out = ctx.actions.declare_file(ctx.label.name + ".jar")
    ctx.actions.run_shell(
        inputs = [spec],
        outputs = [out],
        command = """set -eu
tmp="$(mktemp -d)"
mkdir -p "$tmp/Documentation"
cp "{spec}" "$tmp/Documentation/rest-api-openapi.json"
abs="$(cd "$(dirname "{out}")" && pwd)/$(basename "{out}")"
( cd "$tmp" && zip -qr "$abs" Documentation )
rm -rf "$tmp"
""".format(spec = spec.path, out = out.path),
        mnemonic = "GerritOpenApiResourceJar",
        progress_message = "Packaging committed OpenAPI spec into %{label}",
    )
    return [DefaultInfo(files = depset([out]))]

openapi_resource_jar = rule(
    implementation = _resource_jar_impl,
    attrs = {
        "spec": attr.label(
            allow_single_file = [".json"],
            mandatory = True,
            doc = "A committed openapi.json (normal build) or a GerritOpenApiSpecInfo.",
        ),
    },
    doc = "Package a spec into a plugin jar at Documentation/rest-api-openapi.json.",
)
