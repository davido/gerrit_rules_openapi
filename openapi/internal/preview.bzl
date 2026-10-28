"""Impl: openapi_dev_serve (dev preview). Not public (facade: //openapi:preview.bzl)."""

load(":common.bzl", "spec_file")

visibility(["//openapi:__subpackages__"])

# Pinned Swagger UI for the local preview, loaded from a CDN at serve time --
# dev-only, so no vendored assets or extra build deps.
_SWAGGER_VERSION = "5.34.0"

_SERVE = """#!/usr/bin/env bash
set -euo pipefail
SPEC_SHORT="{short}"
for c in "${{RUNFILES_DIR:-}}/_main/$SPEC_SHORT" "$0.runfiles/_main/$SPEC_SHORT" "$SPEC_SHORT"; do
  [ -f "$c" ] && SPEC="$c" && break
done
: "${{SPEC:?spec not found in runfiles}}"
base="$(basename "$SPEC")"
root="$(mktemp -d)"
cp "$SPEC" "$root/$base"
cat > "$root/index.html" <<HTML
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>OpenAPI preview</title>
<link rel="stylesheet" href="https://unpkg.com/swagger-ui-dist@{swagger}/swagger-ui.css">
</head>
<body>
<div id="swagger-ui"></div>
<script src="https://unpkg.com/swagger-ui-dist@{swagger}/swagger-ui-bundle.js"></script>
<script>window.ui = SwaggerUIBundle({{url: "$base", dom_id: "#swagger-ui"}});</script>
</body>
</html>
HTML
port="${{1:-8080}}"
echo "serving a Swagger UI preview of $base on http://localhost:$port/ (Ctrl-C to stop)"
exec python3 -m http.server "$port" --directory "$root"
"""

def _dev_serve_impl(ctx):
    spec = spec_file(ctx.attr.spec, ctx.file.spec)
    launcher = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(output = launcher, is_executable = True, content = _SERVE.format(short = spec.short_path, swagger = _SWAGGER_VERSION))
    return [DefaultInfo(executable = launcher, runfiles = ctx.runfiles(files = [spec]))]

openapi_dev_serve = rule(
    implementation = _dev_serve_impl,
    executable = True,
    attrs = {"spec": attr.label(
        allow_single_file = [".json"],
        mandatory = True,
        doc = "A committed openapi.json, or a target providing GerritOpenApiSpecInfo.",
    )},
    doc = "Serve a local Swagger UI preview of the spec (dev-only; the UI assets load from a CDN).",
)
