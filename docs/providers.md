<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Providers exposed by gerrit_rules_openapi.

A typed artifact provider carrying a generated spec and its provenance.

<a id="GerritOpenApiSpecInfo"></a>

## GerritOpenApiSpecInfo

<pre>
load("@gerrit_rules_openapi//openapi:providers.bzl", "GerritOpenApiSpecInfo")

GerritOpenApiSpecInfo(<a href="#GerritOpenApiSpecInfo-spec">spec</a>, <a href="#GerritOpenApiSpecInfo-domains">domains</a>, <a href="#GerritOpenApiSpecInfo-provenance">provenance</a>, <a href="#GerritOpenApiSpecInfo-report">report</a>)
</pre>

A generated Gerrit OpenAPI document and its provenance.

**FIELDS**

| Name  | Description |
| :------------- | :------------- |
| <a id="GerritOpenApiSpecInfo-spec"></a>spec |  File: the canonical openapi.json.    |
| <a id="GerritOpenApiSpecInfo-domains"></a>domains |  depset[File]: per-area slices (core only; empty for plugins).    |
| <a id="GerritOpenApiSpecInfo-provenance"></a>provenance |  File\|None: {source_rev, ruleset_rev, digest, version} JSON.    |
| <a id="GerritOpenApiSpecInfo-report"></a>report |  File\|None: the stats / overrides report.    |


