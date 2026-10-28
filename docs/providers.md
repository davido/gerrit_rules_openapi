<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Providers exposed by gerrit_rules_openapi.

The design mirrors rules_openapi / rules_jsonschema in *shape* (a small
toolchain provider + a typed artifact provider), but inverts the direction:
ours produces a spec from source; theirs consumes a spec to produce code.

<a id="GerritOpenApiExtractorToolchainInfo"></a>

## GerritOpenApiExtractorToolchainInfo

<pre>
load("@gerrit_rules_openapi//openapi:providers.bzl", "GerritOpenApiExtractorToolchainInfo")

GerritOpenApiExtractorToolchainInfo(<a href="#GerritOpenApiExtractorToolchainInfo-tool">tool</a>)
</pre>

A source -> OpenAPI extractor backend.

**FIELDS**

| Name  | Description |
| :------------- | :------------- |
| <a id="GerritOpenApiExtractorToolchainInfo-tool"></a>tool |  FilesToRunProvider: the extractor executable plus its runfiles.    |


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


