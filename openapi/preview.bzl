"""Dev entry point: a local Swagger preview of a spec. Dev-only; off the normal
build path. In the real integration this serves the viewer plugin's assets with
`spec` swapped in as the served document.
"""

load(":providers.bzl", "GerritOpenApiSpecInfo")

_SERVE = """#!/usr/bin/env bash
set -euo pipefail
SPEC_SHORT="{short}"
for c in "${{RUNFILES_DIR:-}}/_main/$SPEC_SHORT" "$0.runfiles/_main/$SPEC_SHORT" "$SPEC_SHORT"; do
  [ -f "$c" ] && SPEC="$c" && break
done
: "${{SPEC:?spec not found in runfiles}}"
dir="$(dirname "$SPEC")"
echo "serving $SPEC on http://localhost:${{1:-8080}}/ (Ctrl-C to stop)"
exec python3 -m http.server "${{1:-8080}}" --directory "$dir"
"""

def _dev_serve_impl(ctx):
    spec = ctx.attr.spec[GerritOpenApiSpecInfo].spec if GerritOpenApiSpecInfo in ctx.attr.spec else ctx.file.spec
    launcher = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(output = launcher, is_executable = True, content = _SERVE.format(short = spec.short_path))
    return [DefaultInfo(executable = launcher, runfiles = ctx.runfiles(files = [spec]))]

openapi_dev_serve = rule(
    implementation = _dev_serve_impl,
    executable = True,
    attrs = {"spec": attr.label(allow_single_file = [".json"], mandatory = True)},
    doc = "Serve a spec locally for a Swagger preview (dev-only).",
)
