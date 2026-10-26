# gerrit_rules_openapi

Build-time Bazel **ruleset** that extracts an OpenAPI 3.1 document from Gerrit's
REST bindings (**source → spec**). It is **not** a Gerrit plugin — nothing here
is loaded at runtime. It is consumed via `bazel_dep` by Gerrit core and by
plugins built in-tree or standalone.

Direction note: this is the inverse of a client-codegen ruleset like
[`rules_openapi`](https://github.com/tomato-bazel/rules_openapi) (which goes
*spec → code*). We borrow that family's provider/toolchain/plugin-contract
*architecture* (itself from `rules_jsonschema`); we do not depend on it.

See the design: `gerrit6/split-openapi-design-proposal-v2.md` (§5, §6a, §6b).

## Status

**Skeleton.** The rule/provider/toolchain plumbing is real and tested
end-to-end; the extractor backend is a deterministic **stand-in**
(`tools/default_extractor`) so byte-equality can be exercised before the real
backend is lifted. Replacing the stand-in with the verbatim gerrit6
`java/.../openapi` + `tools/openapi/py` backend (same CLI/toolchain contract) is
the substantive next step.

## Public API

Loaded by consumers as `@gerrit_rules_openapi//openapi:<file>`:

| Entry point | Symbols | Weight |
|---|---|---|
| `defs.bzl` | `openapi_resource_jar` | light — package a committed spec; loads no generation code |
| `generate.bzl` | `openapi_spec`, `openapi_export`, `openapi_stats`, `openapi_publish` | heavy — runs the extractor toolchain |
| `preview.bzl` | `openapi_dev_serve` | dev |
| `providers.bzl` | `GerritOpenApiSpecInfo`, `GerritOpenApiExtractorToolchainInfo` | — |
| `toolchains.bzl` | `gerrit_openapi_extractor_toolchain` | declare a custom backend |

Toolchain type: `//openapi:extractor_toolchain_type` (default registered in
`MODULE.bazel`, swappable).

## Try it

```sh
bazel test //examples/smoke:spec_matches_golden   # regeneration == committed golden
bazel build //examples/smoke:resource             # package a committed spec (no generator)
bazel run   //examples/smoke:export               # runfiles-aware spec locator
```

Generation targets are tagged `manual`: `bazel build //...` won't select them,
but an explicit dependency (the test) still builds them — `manual` blocks
wildcard *selection*, not dependency builds or package *loading*.

## Deferred (tracked in the proposal)

- Public / `internal` split of rule implementations (convention: see local
  `rules_go_simple/internal`, `rules_sbom/sbom/internal`,
  `rules_openapi/openapi/private`). Entry-point isolation already holds in the
  flat layout, so this is API-surface hardening, not a correctness fix.
- Lift the real extractor + bootstrap the versioned core-context source archive.

## License

Apache-2.0.
