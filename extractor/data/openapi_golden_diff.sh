#!/bin/bash
# Fails if the generated endpoint inventory differs from the checked-in golden.
# Args: <golden> <generated>. Used by openapi_endpoint_golden_test (openapi.bzl).
set -euo pipefail
golden="$1"
generated="$2"
if ! diff -u "$golden" "$generated"; then
  echo >&2
  echo "ERROR: the OpenAPI endpoint golden is stale." >&2
  echo "Regenerate it with: tools/openapi/testdata/regen-all-domain-golden.sh" >&2
  exit 1
fi
