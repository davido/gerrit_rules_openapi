# Copyright (C) 2026 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Hermetic OpenAPI endpoint inventory: a report rule + golden drift test.

`openapi_endpoint_report` runs the parse-only endpoint extractor over the
*declared* transitive source jars of a set of root `java_library` targets — the
single, hermetic definition of "all sources" (no workspace `find`). It is
OpenAPI-specific, not a general build primitive: the public attributes (`roots`,
`modules`, `src_prefix`) are already source-agnostic, but the tool and generator
are Gerrit-local defaults (`_tool`, `_generator`). Extracting this to `bazlets`
for standalone plugin builds would additionally open those up (a published
generator artifact) — a later step, when the plugin story is real.
"""

load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_python//python:defs.bzl", "py_binary", "py_test")
load("@rules_shell//shell:sh_test.bzl", "sh_test")

visibility(["//openapi:__subpackages__"])

def _openapi_endpoint_report_impl(ctx):
    source_jars = depset(
        transitive = [r[JavaInfo].transitive_source_jars for r in ctx.attr.roots],
    )
    out = ctx.outputs.out

    # Pass the generator as a FilesToRunProvider so its runfiles (JVM launcher, jars)
    # are staged with the action intentionally, not incidentally — the report_tool
    # invokes generator.executable by path.
    generator = ctx.attr._generator[DefaultInfo].files_to_run
    args = ctx.actions.args()
    args.add("--generator", generator.executable)
    args.add("--out", out)
    args.add("--src-prefix", ",".join(ctx.attr.src_prefix))
    args.add("--modules", ",".join(ctx.attr.modules))
    if ctx.attr.context_modules:
        args.add("--context-modules", ",".join(ctx.attr.context_modules))
    if ctx.attr.plugin_name:
        args.add("--plugin-name", ctx.attr.plugin_name)
    if ctx.attr.plugin:
        args.add("--plugin", ctx.attr.plugin)
    if ctx.attr.scope:
        args.add("--scope", ctx.attr.scope)
    args.add("--format", ctx.attr.format)

    # info.version is passed by the consumer (no host version.bzl load) -- used only
    # by the openapi format.
    args.add("--version", ctx.attr.version)
    extra = []
    if ctx.file.overrides:
        args.add("--overrides", ctx.file.overrides)
        extra = [ctx.file.overrides]
    if ctx.attr.allow_unused_overrides:
        args.add("--allow-unused-overrides")
    args.add_all(source_jars)
    ctx.actions.run(
        executable = ctx.executable._tool,
        arguments = [args],
        inputs = depset(extra, transitive = [source_jars]),
        outputs = [out],
        tools = [generator],
        mnemonic = "OpenApiEndpointReport",
        progress_message = "Generating OpenAPI endpoint report %s" % out.short_path,
    )
    return [DefaultInfo(files = depset([out]))]

openapi_endpoint_report = rule(
    implementation = _openapi_endpoint_report_impl,
    doc = "Emits the endpoint inventory JSON from the roots' transitive source jars.",
    attrs = {
        "roots": attr.label_list(
            providers = [JavaInfo],
            mandatory = True,
            doc = "java_library targets whose transitive source jars define the source universe.",
        ),
        "modules": attr.string_list(
            mandatory = True,
            doc = "REST module FQCNs to extract (as passed to the extractor's --modules).",
        ),
        "context_modules": attr.string_list(
            doc = "REST module FQCNs used to build the kind/path graph only -- their child() " +
                  "bindings resolve parent chains (e.g. project->branch) but their endpoints are " +
                  "NOT emitted. For plugin documents that hang off core resource kinds.",
        ),
        "plugin_name": attr.string(
            doc = "When set, the plugin's view segments that attach to a core resource kind are " +
                  "emitted in the explicit <plugin_name>~<segment> form (the always-unambiguous " +
                  "REST path, e.g. webhooks~projects). Omit for the bare form when the plugin " +
                  "self-namespaces its view names (e.g. code-owners' code_owners.config).",
        ),
        "plugin": attr.string(
            doc = "The plugin's install name. A plugin's own top-level servlet (bound in its " +
                  "Gerrit-HttpModule) is served under /plugins/<name>/, so this prefixes those " +
                  "HttpModule-resolved mounts accordingly (e.g. checks' /checkers -> " +
                  "/plugins/checks/checkers); mounts grafted onto core kinds are unaffected. " +
                  "Distinct from plugin_name (the ~ view form): a plugin may need one, both, or " +
                  "neither.",
        ),
        "scope": attr.string(
            doc = "The 'scope' label written into a combined (--modules) inventory report. " +
                  "Empty keeps the tool default (all-core-domains, for the core surface); a " +
                  "plugin report sets a plugin-identifying scope (e.g. plugin:checks) so the " +
                  "checked-in golden does not claim to be the core surface.",
        ),
        "src_prefix": attr.string_list(
            default = ["com/google/gerrit/"],
            doc = "Only source-jar entries under one of these package paths are parsed. A plugin " +
                  "in its own package (com.googlesource.gerrit.plugins.*, com.ericsson.*, ...) " +
                  "adds its prefix alongside com/google/gerrit/ so both the plugin and the core " +
                  "context modules are parsed.",
        ),
        "format": attr.string(
            default = "inventory",
            values = ["inventory", "openapi"],
            doc = "Output format passed to the extractor: the inventory report or an OpenAPI doc.",
        ),
        "overrides": attr.label(
            allow_single_file = [".json"],
            doc = "OpenAPI overrides document to apply (openapi format only).",
        ),
        "allow_unused_overrides": attr.bool(
            default = False,
            doc = "Do not fail when the overrides contain operations absent from the emitted " +
                  "endpoints. Set on per-domain slices, which apply the whole-API composed " +
                  "overrides to a subset of the endpoints.",
        ),
        "out": attr.output(mandatory = True),
        "version": attr.string(
            mandatory = True,
            doc = "info.version for the emitted OpenAPI document, passed by the consumer.",
        ),
        "_tool": attr.label(
            default = "//extractor/py:report_tool",
            executable = True,
            cfg = "exec",
        ),
        "_generator": attr.label(
            default = "//extractor/java:endpoints",
            executable = True,
            cfg = "exec",
        ),
    },
)

def openapi_endpoint_golden_test(name, report, golden, **kwargs):
    """Fails when `report` (a generated inventory) drifts from the checked-in `golden`."""
    sh_test(
        name = name,
        size = kwargs.pop("size", "small"),
        srcs = ["@gerrit_rules_openapi//extractor/data:openapi_golden_diff.sh"],
        args = ["$(location %s)" % golden, "$(location %s)" % report],
        data = [golden, report],
        **kwargs
    )

def openapi_plugin_spec(
        name,
        modules,
        roots,
        docs,
        version,
        context_modules = [],
        src_prefix = ["com/google/gerrit/"],
        plugin_name = "",
        plugin = "",
        curated_overrides = None,
        entity_aliases = None,
        context_overrides = None,
        inventory_golden = None):
    """A plugin's full OpenAPI pipeline in one call -- conceptually identical to the
    core pipeline in //tools/openapi (base -> mined overrides -> [compose with curated]
    -> emit), differing only in the doc dialect: plugins ship Markdown (rest-api*.md,
    which override_extractor auto-detects) where core ships AsciiDoc. No new logic --
    it wires the same override_extractor + overrides_compose_tool core uses, so a plugin
    stays a few lines instead of repeating the five-target pipeline.

    Produces `<name>`: the composed OpenAPI 3.1 document (format = openapi) enriched
    with the plugin's own doc prose (summaries, descriptions, request/response
    examples). Feed it to openapi_resource_jar (package into the jar) and
    openapi_dev_serve (local viewer).

    Args mirror openapi_endpoint_report (modules / context_modules / roots / src_prefix
    / plugin_name / plugin / version). `version` is the emitted document's info.version;
    pass the host Gerrit's version (keep it in sync with core's //tools/openapi VERSION).
    `docs` are the plugin's rest-api*.md label(s). `plugin` (the
    install name) both prefixes the plugin's own HttpModule mounts with /plugins/<name>/
    and expands the @PLUGIN@ doc token, so a plugin-servlet endpoint's spec path and its
    doc prose line up. `curated_overrides`, when set, is a small hand-authored sidecar
    (envelope/title + any non-derivable edits) composed over the mined overrides with
    curated winning per field -- exactly like core's openapi-overrides.json. Omit it to
    publish the mined prose alone.

    `inventory_golden` is optional. When set to a checked-in `.json` golden, the macro
    adds a byte-diff `<name>_golden_test` over the plugin's endpoint inventory, so
    REST-surface drift is reviewable (like core's all_domain_golden_test). The golden
    records emitted plugin endpoints only -- `context_modules` resolve parent paths but
    are not emitted. The OpenAPI document itself remains a generated, unversioned build
    output guarded by `<name>_spec_test`; only the inventory is checked in."""
    base = name + "_base"
    generated = name + "_generated_overrides"

    # A plugin-identifying scope: the install name if given, else the plugin's package
    # dir name (e.g. code-owners), never the underscored Bazel target name. It labels the
    # inventory golden's `scope` and the build-log lines so plugin reports do not read
    # "all-core-domains".
    plugin_scope = "plugin:" + (plugin or native.package_name().split("/")[-1])

    # (1) Override-less base spec: structure only -- the spec the miner reads and
    # joins its prose to by (method, path). Same source universe as the final spec.
    openapi_endpoint_report(
        name = base,
        out = base + ".generated.json",
        format = "openapi",
        modules = modules,
        context_modules = context_modules,
        roots = roots,
        src_prefix = src_prefix,
        plugin_name = plugin_name,
        plugin = plugin,
        scope = plugin_scope,
        version = version,
    )

    # (2) Mine the plugin's own rest-api*.md prose into generated overrides. The doc
    # files are real action inputs; their sandbox paths go into a manifest inside the
    # action (a label alone would not stage them) -- the same handshake core uses.
    doc_locs = " ".join(["$(locations %s)" % d for d in docs])
    native.genrule(
        name = generated,
        srcs = docs + [":" + base] +
               ([entity_aliases] if entity_aliases else []) +
               ([context_overrides] if context_overrides else []),
        outs = [generated + ".json", name + "_overrides_report.json"],
        cmd = " && ".join([
            "MANIFEST=$$(mktemp)",
            "for f in %s; do echo $$f; done > $$MANIFEST" % doc_locs,
            "$(location @gerrit_rules_openapi//extractor/py:override_extractor)" +
            " --docs-manifest $$MANIFEST" +
            (" --plugin %s" % plugin if plugin else "") +
            (" --entity-aliases $(location %s)" % entity_aliases if entity_aliases else "") +
            (" --context-overrides $(location %s)" % context_overrides if context_overrides else "") +
            " --spec $(location :%s)" % base +
            " --out $(location %s.json)" % generated +
            " --report $(location %s_overrides_report.json)" % name,
        ]),
        tools = ["@gerrit_rules_openapi//extractor/py:override_extractor"],
    )

    overrides = ":" + generated + ".json"
    if curated_overrides:
        # (2b) Compose curated over mined (curated wins per field), via the same Java
        # deepMerge the emitter applies -- one merge implementation, as in core.
        composed = name + "_composed_overrides"
        native.genrule(
            name = composed,
            srcs = [generated + ".json", curated_overrides],
            outs = [composed + ".json"],
            cmd = "$(location @gerrit_rules_openapi//extractor/java:overrides_compose_tool)" +
                  " $(location %s.json)" % generated +
                  " $(location %s)" % curated_overrides +
                  " $(location %s.json)" % composed,
            tools = ["@gerrit_rules_openapi//extractor/java:overrides_compose_tool"],
        )
        overrides = ":" + composed + ".json"

    # (3) Re-emit with the mined (+curated) overrides applied: the published spec.
    openapi_endpoint_report(
        name = name,
        out = name + ".generated.json",
        format = "openapi",
        modules = modules,
        context_modules = context_modules,
        roots = roots,
        src_prefix = src_prefix,
        plugin_name = plugin_name,
        plugin = plugin,
        scope = plugin_scope,
        overrides = overrides,
        version = version,
    )

    # (4) A verification gate, mirroring core's all_domain_openapi_spec_test: a second
    # format=inventory report of the same bindings -- independent of the emitted OpenAPI
    # document, though it shares the extractor/path-resolution machinery, so it is a
    # consistency check (spec operations exactly equal the inventory's -- nothing dropped
    # or invented), not an independent oracle for path correctness. Fed with the published
    # spec to the shared semantic validator (parses OpenAPI 3.x, every $ref resolves,
    # no bodies on GET/HEAD/DELETE, tags/security consistent). Run it with
    # `bazel test //plugins/<plugin>:<name>_spec_test`.
    openapi_endpoint_report(
        name = name + "_inventory",
        out = name + "-inventory.generated.json",
        format = "inventory",
        modules = modules,
        context_modules = context_modules,
        roots = roots,
        src_prefix = src_prefix,
        plugin_name = plugin_name,
        plugin = plugin,
        # A plugin snapshot, not the core surface: label the inventory accordingly so the
        # checked-in golden's scope does not read "all-core-domains".
        scope = plugin_scope,
        version = version,
    )
    openapi_json_gate_test(
        name = name + "_spec_test",
        script = "@gerrit_rules_openapi//extractor/py:openapi_spec_validator.py",
        inputs = [":" + name, ":" + name + "_inventory"],
    )

    # (5) Optional REST-surface drift golden. The OpenAPI document itself stays a
    # generated build output; this byte-diffs the plugin's *endpoint inventory* (emitted
    # endpoints only -- context_modules resolve parent paths but are not emitted) against
    # a checked-in golden, so plugin REST-surface changes surface as reviewable golden
    # diffs. Regenerate the golden from :<name>_inventory after an intended REST change.
    if inventory_golden:
        openapi_endpoint_golden_test(
            name = name + "_golden_test",
            golden = inventory_golden,
            report = ":" + name + "_inventory",
        )

def openapi_json_gate_test(name, script, inputs, **kwargs):
    """A py_test gate over generated OpenAPI artifacts: `script` is passed each of
    `inputs` as a $(location) argument, with the same labels as its data. Writing the
    label list once removes the args/data drift the raw py_test blocks were prone to.
    Extra kwargs (size, tags, visibility, ...) pass through for plugin/bazlets reuse."""
    py_test(
        name = name,
        srcs = [script],
        main = script,
        args = ["$(location %s)" % i for i in inputs],
        data = inputs,
        **kwargs
    )
