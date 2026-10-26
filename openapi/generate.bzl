"""Heavy entry point: generate a Gerrit OpenAPI spec from source.

Loaded ONLY by generation/verification packages (never by a normal plugin
packaging BUILD). All targets here should be tagged `manual` by the consumer so
`bazel build //...` does not select them through wildcard expansion.

  openapi_spec     — the extractor rule; runs the toolchain over consumer inputs,
                     returns GerritOpenApiSpecInfo.
  openapi_export   — a runfiles-aware executable that reports the produced spec.
  openapi_stats    — runnable: prints the stats contract for a spec.
  openapi_publish  — runnable: stages a spec into a chosen local dir. Never
                     commits/tags/pushes/uploads.
"""

load(":providers.bzl", "GerritOpenApiSpecInfo")

_TOOLCHAIN = Label("//openapi:extractor_toolchain_type")

def _openapi_spec_impl(ctx):
    tc = ctx.toolchains[_TOOLCHAIN].extractor
    out = ctx.actions.declare_file(ctx.label.name + ".json")

    args = ctx.actions.args()
    args.add("--version", ctx.attr.version)
    args.add("--out", out.path)
    args.add_all(ctx.files.srcs, before_each = "--src")
    args.add_all(ctx.files.docs, before_each = "--doc")
    inputs = ctx.files.srcs + ctx.files.docs
    if ctx.file.overrides:
        args.add("--overrides", ctx.file.overrides.path)
        inputs = inputs + [ctx.file.overrides]
    if ctx.file.context_modules:
        args.add("--context", ctx.file.context_modules.path)
        inputs = inputs + [ctx.file.context_modules]

    ctx.actions.run(
        outputs = [out],
        inputs = depset(inputs),
        executable = tc.tool,  # FilesToRunProvider -> runfiles staged automatically
        arguments = [args],
        mnemonic = "GerritOpenApiSpec",
        progress_message = "Extracting OpenAPI spec %{label}",
    )

    # provenance: left None in the skeleton; the real rule writes
    # {source_rev, ruleset_rev, digest, version} (proposal §6b/§7).
    return [
        DefaultInfo(files = depset([out])),
        GerritOpenApiSpecInfo(
            spec = out,
            domains = depset(),
            provenance = None,
            report = None,
        ),
    ]

openapi_spec = rule(
    implementation = _openapi_spec_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = True,
            doc = "Consumer-owned source inputs (JavaInfo source closure in the " +
                  "real backend; a paths fixture in the default skeleton backend).",
        ),
        "docs": attr.label_list(allow_files = True, doc = "REST prose doc inputs."),
        "overrides": attr.label(allow_single_file = True, doc = "Curated overrides JSON."),
        "context_modules": attr.label(
            allow_single_file = True,
            doc = "Pinned core-context input (source-closure archive for plugins).",
        ),
        "version": attr.string(mandatory = True, doc = "info.version, passed in by the consumer."),
    },
    toolchains = [_TOOLCHAIN],
    doc = "Extract an OpenAPI 3.1 document from consumer-declared inputs.",
)

def _spec_file(target, file_attr):
    if GerritOpenApiSpecInfo in target:
        return target[GerritOpenApiSpecInfo].spec
    return file_attr

_LAUNCHER = """#!/usr/bin/env bash
set -euo pipefail
MODE="{mode}"
SPEC_SHORT="{short}"
find_spec() {{
  for c in "${{RUNFILES_DIR:-}}/_main/$SPEC_SHORT" \\
           "${{RUNFILES_DIR:-}}/gerrit_rules_openapi/$SPEC_SHORT" \\
           "$0.runfiles/_main/$SPEC_SHORT" \\
           "$0.runfiles/gerrit_rules_openapi/$SPEC_SHORT" \\
           "$SPEC_SHORT"; do
    [ -f "$c" ] && {{ echo "$c"; return 0; }}
  done
  echo "ERROR: spec not found in runfiles ($SPEC_SHORT)" >&2; exit 1
}}
SPEC="$(find_spec)"
case "$MODE" in
  export)  echo "openapi spec: $SPEC" ;;
  stats)   python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print("paths:",len(d.get("paths",{{}})))' "$SPEC" ;;
  publish) dest="${{1:?usage: bazel run <target> -- <dest-dir>}}"; mkdir -p "$dest"; cp "$SPEC" "$dest/openapi.json"; echo "published -> $dest/openapi.json (no commit/tag/push)" ;;
esac
"""

def _runner_impl(ctx):
    spec = _spec_file(ctx.attr.spec, ctx.file.spec)
    launcher = ctx.actions.declare_file(ctx.label.name + ".sh")
    ctx.actions.write(
        output = launcher,
        is_executable = True,
        content = _LAUNCHER.format(mode = ctx.attr.mode, short = spec.short_path),
    )
    return [DefaultInfo(
        executable = launcher,
        runfiles = ctx.runfiles(files = [spec]),
    )]

_openapi_runner = rule(
    implementation = _runner_impl,
    executable = True,
    attrs = {
        "spec": attr.label(allow_single_file = [".json"], mandatory = True),
        "mode": attr.string(values = ["export", "stats", "publish"], mandatory = True),
    },
)

def openapi_export(name, spec, **kwargs):
    _openapi_runner(name = name, spec = spec, mode = "export", **kwargs)

def openapi_stats(name, spec, **kwargs):
    _openapi_runner(name = name, spec = spec, mode = "stats", **kwargs)

def openapi_publish(name, spec, **kwargs):
    _openapi_runner(name = name, spec = spec, mode = "publish", **kwargs)
