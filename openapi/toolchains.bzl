"""Public: declare a custom extractor backend as a toolchain.

Swap the default by declaring your own and `register_toolchains(...)`-ing it
ahead of the default. Preserve the current executable/input/output contract when
swapping — do not redesign extraction while relocating it.
"""

load("//openapi/internal:toolchain.bzl", _gerrit_openapi_extractor_toolchain = "gerrit_openapi_extractor_toolchain")

gerrit_openapi_extractor_toolchain = _gerrit_openapi_extractor_toolchain
