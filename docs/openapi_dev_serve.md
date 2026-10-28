<!-- Generated with Stardoc: http://skydoc.bazel.build -->

Public (dev): a local Swagger preview of a spec. Dev-only; off the normal
build path.

<a id="openapi_dev_serve"></a>

## openapi_dev_serve

<pre>
load("@gerrit_rules_openapi//openapi:preview.bzl", "openapi_dev_serve")

openapi_dev_serve(<a href="#openapi_dev_serve-name">name</a>, <a href="#openapi_dev_serve-spec">spec</a>)
</pre>

Serve a local Swagger UI preview of the spec (dev-only; the UI assets load from a CDN).

**ATTRIBUTES**


| Name  | Description | Type | Mandatory | Default |
| :------------- | :------------- | :------------- | :------------- | :------------- |
| <a id="openapi_dev_serve-name"></a>name |  A unique name for this target.   | <a href="https://bazel.build/concepts/labels#target-names">Name</a> | required |  |
| <a id="openapi_dev_serve-spec"></a>spec |  A committed openapi.json, or a target providing GerritOpenApiSpecInfo.   | <a href="https://bazel.build/concepts/labels">Label</a> | required |  |


