"""Impl: openapi_resource_jar (light, committed-spec packaging). Not public.

Loaded only by the public //openapi:defs.bzl facade. Pulls NO generation code.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load(":common.bzl", "spec_file")

visibility(["//openapi:__subpackages__"])

def _resource_jar_impl(ctx):
    spec = spec_file(ctx.attr.spec, ctx.file.spec)
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

    # Return JavaInfo (not just DefaultInfo) so the jar is accepted in gerrit_plugin's
    # resource_jars, which bazlets routes into the plugin java_binary's runtime_deps --
    # that attribute demands JavaInfo. This is a resource-only jar (no classes), so unlike
    # rules_jvm_external's jvm_import there is nothing to ijar: the jar itself is both the
    # output and the (unused) compile jar. It is merged into the final plugin jar, which
    # Gerrit serves at /plugins/<name>/Documentation/rest-api-openapi.json.
    return [
        DefaultInfo(files = depset([out])),
        JavaInfo(output_jar = out, compile_jar = out),
    ]

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
