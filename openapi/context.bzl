"""Heavy entry point: bootstrap/produce the versioned core-context archive.

`core_context_archive` packages the REST-context + DTO source closure (plus mined
overrides) that a plugin extractor consumes as a *pinned* input, instead of
reaching into live core. Emits a deterministic `.tar` + a `provenance.json`
(schema, version, exact source_rev, archive digest, per-entry digests), so a
plugin can pin a compatible context and a drift gate can detect staleness.

Loaded only in generation packages; tag targets `manual`.
"""

_ARCHIVER = Label("//tools/context_archiver:archiver")

def _core_context_archive_impl(ctx):
    archive = ctx.actions.declare_file(ctx.label.name + ".tar")
    prov = ctx.actions.declare_file(ctx.label.name + ".provenance.json")

    args = ctx.actions.args()
    args.add("--version", ctx.attr.version)
    args.add("--source-rev", ctx.attr.source_rev)
    args.add("--arcprefix", ctx.attr.arcprefix)
    args.add_all(ctx.files.srcs, before_each = "--src")
    inputs = list(ctx.files.srcs)
    if ctx.file.overrides:
        args.add("--overrides", ctx.file.overrides.path)
        inputs.append(ctx.file.overrides)
    args.add("--out-archive", archive.path)
    args.add("--out-provenance", prov.path)

    ctx.actions.run(
        outputs = [archive, prov],
        inputs = depset(inputs),
        executable = ctx.executable._archiver,
        arguments = [args],
        mnemonic = "GerritOpenApiCoreContext",
        progress_message = "Bootstrapping core-context archive %{label}",
    )
    return [
        DefaultInfo(files = depset([archive, prov])),
        # Addressable sub-outputs so consumers/tests can pick one.
        OutputGroupInfo(archive = depset([archive]), provenance = depset([prov])),
    ]

core_context_archive = rule(
    implementation = _core_context_archive_impl,
    attrs = {
        "srcs": attr.label_list(
            allow_files = True,
            mandatory = True,
            doc = "REST-context + DTO source closure the extractor manifest requires.",
        ),
        "overrides": attr.label(allow_single_file = True, doc = "Mined/curated core overrides."),
        "version": attr.string(mandatory = True, doc = "Baseline version this archive matches."),
        "source_rev": attr.string(mandatory = True, doc = "Exact Gerrit source revision."),
        "arcprefix": attr.string(default = "core-context"),
        "_archiver": attr.label(default = _ARCHIVER, executable = True, cfg = "exec"),
    },
    doc = "Produce a deterministic, provenance-stamped core-context source archive.",
)
