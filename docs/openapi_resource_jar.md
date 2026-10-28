<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Public (light): package a committed spec. Loads NO generation code.

A normal plugin BUILD loads only this file. `openapi_resource_jar` accepts a
committed `openapi.json` (a source file) or a GerritOpenApiSpecInfo and merges
it into a jar at `Documentation/rest-api-openapi.json`.

<a id="openapi_resource_jar"></a>

## openapi_resource_jar

<pre>
load("@gerrit_rules_openapi//openapi:defs.bzl", "openapi_resource_jar")

openapi_resource_jar(<a href="#openapi_resource_jar-name">name</a>, <a href="#openapi_resource_jar-spec">spec</a>)
</pre>

Package a spec into a plugin jar at Documentation/rest-api-openapi.json.

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="openapi_resource_jar-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="openapi_resource_jar-spec"></a>spec |  A committed openapi.json (normal build) or a GerritOpenApiSpecInfo.   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |


