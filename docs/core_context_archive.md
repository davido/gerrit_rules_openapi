<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Public (heavy): bootstrap/produce the versioned core-context archive the
plugin extractor consumes as a pinned input. Load only in generation packages
and tag targets `manual`.

<a id="core_context_archive"></a>

## core_context_archive

<pre>
load("@gerrit_rules_openapi//openapi:context.bzl", "core_context_archive")

core_context_archive(<a href="#core_context_archive-name">name</a>, <a href="#core_context_archive-srcs">srcs</a>, <a href="#core_context_archive-arcprefix">arcprefix</a>, <a href="#core_context_archive-overrides">overrides</a>, <a href="#core_context_archive-source_rev">source_rev</a>, <a href="#core_context_archive-version">version</a>)
</pre>

Produce a deterministic, provenance-stamped core-context source archive.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="core_context_archive-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="core_context_archive-srcs"></a>srcs |  REST-context + DTO source closure the extractor manifest requires.   | <a href="https://bazel.build/concepts/labels">List of labels</a> | required |  |
| <a id="core_context_archive-arcprefix"></a>arcprefix |  -   | String | optional |  `"core-context"`  |
| <a id="core_context_archive-overrides"></a>overrides |  Mined/curated core overrides.   | <a href="https://bazel.build/concepts/labels">Label</a> | optional |  `None`  |
| <a id="core_context_archive-source_rev"></a>source_rev |  Exact Gerrit source revision.   | String | required |  |
| <a id="core_context_archive-version"></a>version |  Baseline version this archive matches.   | String | required |  |


