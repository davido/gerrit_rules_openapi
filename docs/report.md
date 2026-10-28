<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Public (heavy): the REAL source->spec pipeline, lifted verbatim from gerrit's
tools/openapi/openapi.bzl.

`openapi_endpoint_report` runs the Java `endpoints` generator (via report_tool)
over the consumer's `roots` (java_library targets -> transitive source jars) for
a `modules` list, optionally applying `overrides`, emitting an inventory or an
OpenAPI document. `openapi_plugin_spec` wraps the full base -> mined-overrides ->
compose -> emit chain for a plugin. Load only in generation packages; tag targets
`manual`.

<a id="openapi_endpoint_report"></a>

## openapi_endpoint_report

<pre>
load("@gerrit_rules_openapi//openapi:report.bzl", "openapi_endpoint_report")

openapi_endpoint_report(<a href="#openapi_endpoint_report-name">name</a>, <a href="#openapi_endpoint_report-out">out</a>, <a href="#openapi_endpoint_report-allow_unused_overrides">allow_unused_overrides</a>, <a href="#openapi_endpoint_report-context_modules">context_modules</a>, <a href="#openapi_endpoint_report-format">format</a>, <a href="#openapi_endpoint_report-modules">modules</a>,
                        <a href="#openapi_endpoint_report-overrides">overrides</a>, <a href="#openapi_endpoint_report-plugin">plugin</a>, <a href="#openapi_endpoint_report-plugin_name">plugin_name</a>, <a href="#openapi_endpoint_report-roots">roots</a>, <a href="#openapi_endpoint_report-scope">scope</a>, <a href="#openapi_endpoint_report-src_prefix">src_prefix</a>, <a href="#openapi_endpoint_report-version">version</a>)
</pre>

Emits the endpoint inventory JSON from the roots' transitive source jars.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="openapi_endpoint_report-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="openapi_endpoint_report-out"></a>out |  -   | <a href="https://bazel.build/concepts/labels">Label</a>; <a href="https://bazel.build/reference/be/common-definitions#configurable-attributes">nonconfigurable</a> | required |  |
| <a id="openapi_endpoint_report-allow_unused_overrides"></a>allow_unused_overrides |  Do not fail when the overrides contain operations absent from the emitted endpoints. Set on per-domain slices, which apply the whole-API composed overrides to a subset of the endpoints.   | Boolean | optional |  `False`  |
| <a id="openapi_endpoint_report-context_modules"></a>context_modules |  REST module FQCNs used to build the kind/path graph only -- their child() bindings resolve parent chains (e.g. project->branch) but their endpoints are NOT emitted. For plugin documents that hang off core resource kinds.   | List of strings | optional |  `[]`  |
| <a id="openapi_endpoint_report-format"></a>format |  Output format passed to the extractor: the inventory report or an OpenAPI doc.   | String | optional |  `"inventory"`  |
| <a id="openapi_endpoint_report-modules"></a>modules |  REST module FQCNs to extract (as passed to the extractor's --modules).   | List of strings | required |  |
| <a id="openapi_endpoint_report-overrides"></a>overrides |  OpenAPI overrides document to apply (openapi format only).   | <a href="https://bazel.build/concepts/labels">Label</a> | optional |  `None`  |
| <a id="openapi_endpoint_report-plugin"></a>plugin |  The plugin's install name. A plugin's own top-level servlet (bound in its Gerrit-HttpModule) is served under /plugins/<name>/, so this prefixes those HttpModule-resolved mounts accordingly (e.g. checks' /checkers -> /plugins/checks/checkers); mounts grafted onto core kinds are unaffected. Distinct from plugin_name (the ~ view form): a plugin may need one, both, or neither.   | String | optional |  `""`  |
| <a id="openapi_endpoint_report-plugin_name"></a>plugin_name |  When set, the plugin's view segments that attach to a core resource kind are emitted in the explicit <plugin_name>~<segment> form (the always-unambiguous REST path, e.g. webhooks~projects). Omit for the bare form when the plugin self-namespaces its view names (e.g. code-owners' code_owners.config).   | String | optional |  `""`  |
| <a id="openapi_endpoint_report-roots"></a>roots |  java_library targets whose transitive source jars define the source universe.   | <a href="https://bazel.build/concepts/labels">List of labels</a> | required |  |
| <a id="openapi_endpoint_report-scope"></a>scope |  The 'scope' label written into a combined (--modules) inventory report. Empty keeps the tool default (all-core-domains, for the core surface); a plugin report sets a plugin-identifying scope (e.g. plugin:checks) so the checked-in golden does not claim to be the core surface.   | String | optional |  `""`  |
| <a id="openapi_endpoint_report-src_prefix"></a>src_prefix |  Only source-jar entries under one of these package paths are parsed. A plugin in its own package (com.googlesource.gerrit.plugins.*, com.ericsson.*, ...) adds its prefix alongside com/google/gerrit/ so both the plugin and the core context modules are parsed.   | List of strings | optional |  `["com/google/gerrit/"]`  |
| <a id="openapi_endpoint_report-version"></a>version |  info.version for the emitted OpenAPI document, passed by the consumer.   | String | required |  |


<a id="openapi_endpoint_golden_test"></a>

## openapi_endpoint_golden_test

<pre>
load("@gerrit_rules_openapi//openapi:report.bzl", "openapi_endpoint_golden_test")

openapi_endpoint_golden_test(<a href="#openapi_endpoint_golden_test-name">name</a>, <a href="#openapi_endpoint_golden_test-report">report</a>, <a href="#openapi_endpoint_golden_test-golden">golden</a>, <a href="#openapi_endpoint_golden_test-kwargs">**kwargs</a>)
</pre>

Fail when `report` (a generated inventory) drifts from the checked-in `golden`.

**PARAMETERS**


| Name  | Description | Default Value |
| :------------- | :------------- | :------------- |
| <a id="openapi_endpoint_golden_test-name"></a>name |  Test target name.   |  none |
| <a id="openapi_endpoint_golden_test-report"></a>report |  Label of the generated endpoint-inventory JSON to check.   |  none |
| <a id="openapi_endpoint_golden_test-golden"></a>golden |  Label of the checked-in golden JSON it must match byte-for-byte.   |  none |
| <a id="openapi_endpoint_golden_test-kwargs"></a>kwargs |  Extra sh_test attributes (size, tags, visibility, ...).   |  none |


<a id="openapi_json_gate_test"></a>

## openapi_json_gate_test

<pre>
load("@gerrit_rules_openapi//openapi:report.bzl", "openapi_json_gate_test")

openapi_json_gate_test(<a href="#openapi_json_gate_test-name">name</a>, <a href="#openapi_json_gate_test-script">script</a>, <a href="#openapi_json_gate_test-inputs">inputs</a>, <a href="#openapi_json_gate_test-kwargs">**kwargs</a>)
</pre>

Run a py_test gate over generated OpenAPI artifacts.

`script` is passed each of `inputs` as a $(location) argument, with the same
labels wired as its data. Writing the label list once removes the args/data drift
the raw py_test blocks were prone to.


**PARAMETERS**


| Name  | Description | Default Value |
| :------------- | :------------- | :------------- |
| <a id="openapi_json_gate_test-name"></a>name |  Test target name.   |  none |
| <a id="openapi_json_gate_test-script"></a>script |  Label of the Python test script (used as both srcs and main).   |  none |
| <a id="openapi_json_gate_test-inputs"></a>inputs |  Labels passed to `script` as $(location) args and wired as its data.   |  none |
| <a id="openapi_json_gate_test-kwargs"></a>kwargs |  Extra py_test attributes (size, tags, visibility, ...).   |  none |


<a id="openapi_plugin_spec"></a>

## openapi_plugin_spec

<pre>
load("@gerrit_rules_openapi//openapi:report.bzl", "openapi_plugin_spec")

openapi_plugin_spec(<a href="#openapi_plugin_spec-name">name</a>, <a href="#openapi_plugin_spec-modules">modules</a>, <a href="#openapi_plugin_spec-roots">roots</a>, <a href="#openapi_plugin_spec-docs">docs</a>, <a href="#openapi_plugin_spec-version">version</a>, <a href="#openapi_plugin_spec-context_modules">context_modules</a>, <a href="#openapi_plugin_spec-src_prefix">src_prefix</a>, <a href="#openapi_plugin_spec-plugin_name">plugin_name</a>,
                    <a href="#openapi_plugin_spec-plugin">plugin</a>, <a href="#openapi_plugin_spec-curated_overrides">curated_overrides</a>, <a href="#openapi_plugin_spec-entity_aliases">entity_aliases</a>, <a href="#openapi_plugin_spec-context_overrides">context_overrides</a>, <a href="#openapi_plugin_spec-inventory_golden">inventory_golden</a>)
</pre>

Generate a plugin's full OpenAPI 3.1 document in one call.

Conceptually identical to the core pipeline in //tools/openapi (base -> mined
overrides -> [compose with curated] -> emit), differing only in the doc dialect:
plugins ship Markdown (rest-api*.md, which override_extractor auto-detects) where
core ships AsciiDoc. It wires the same override_extractor + overrides_compose_tool
core uses, so a plugin stays a few lines instead of repeating the five-target
pipeline.

Produces `<name>`: the composed OpenAPI 3.1 document (format = openapi) enriched
with the plugin's own doc prose (summaries, descriptions, request/response
examples). Feed it to openapi_resource_jar (package into the jar) and
openapi_dev_serve (local viewer). The document itself is a generated, unversioned
build output guarded by `<name>_spec_test`.


**PARAMETERS**


| Name  | Description | Default Value |
| :------------- | :------------- | :------------- |
| <a id="openapi_plugin_spec-name"></a>name |  Base name; the macro derives its targets from it (`<name>`, `<name>_base`, `<name>_spec_test`, and `<name>_golden_test` when `inventory_golden` is set).   |  none |
| <a id="openapi_plugin_spec-modules"></a>modules |  REST binding module class name(s) whose endpoints are emitted.   |  none |
| <a id="openapi_plugin_spec-roots"></a>roots |  Source roots (labels) the extractor parses for the REST bindings and the kinds they reference.   |  none |
| <a id="openapi_plugin_spec-docs"></a>docs |  The plugin's rest-api*.md doc label(s); their prose (summaries, descriptions, examples) is mined and merged into the spec.   |  none |
| <a id="openapi_plugin_spec-version"></a>version |  The emitted document's info.version. Pass the host Gerrit's version and keep it in sync with core's //tools/openapi VERSION.   |  none |
| <a id="openapi_plugin_spec-context_modules"></a>context_modules |  Core REST module class name(s) whose kinds resolve the plugin's parent path chains (e.g. change/project), but whose endpoints are NOT emitted. Defaults to `[]`.   |  `[]` |
| <a id="openapi_plugin_spec-src_prefix"></a>src_prefix |  Package prefix(es) under `roots` to scan. Defaults to `["com/google/gerrit/"]`.   |  `["com/google/gerrit/"]` |
| <a id="openapi_plugin_spec-plugin_name"></a>plugin_name |  Internal plugin name used to qualify the plugin's own view segments that attach onto core kinds. Defaults to `""`.   |  `""` |
| <a id="openapi_plugin_spec-plugin"></a>plugin |  The plugin install name. Prefixes the plugin's own HttpModule mounts with /plugins/<name>/ and expands the @PLUGIN@ doc token, so a plugin-servlet endpoint's spec path and its prose line up. Defaults to `""`.   |  `""` |
| <a id="openapi_plugin_spec-curated_overrides"></a>curated_overrides |  Optional hand-authored overrides sidecar (envelope/title + any non-derivable edits) composed over the mined overrides, curated winning per field -- like core's openapi-overrides.json. Omit to publish the mined prose alone.   |  `None` |
| <a id="openapi_plugin_spec-entity_aliases"></a>entity_aliases |  Optional label mapping a DTO entity name to its documented heading, for entities whose doc heading differs from the class name.   |  `None` |
| <a id="openapi_plugin_spec-context_overrides"></a>context_overrides |  Optional label to a parent pipeline's generated-overrides JSON (e.g. core's //tools/openapi output) composed underneath the plugin's own overrides, so shared core entities inherit core prose.   |  `None` |
| <a id="openapi_plugin_spec-inventory_golden"></a>inventory_golden |  Optional checked-in `.json` golden of the plugin's emitted endpoint inventory. When set, adds a byte-diff `<name>_golden_test` so REST-surface drift is reviewable. Records emitted plugin endpoints only.   |  `None` |


