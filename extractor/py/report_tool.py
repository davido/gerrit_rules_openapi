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

"""Unpacks declared Java source jars into a manifest and runs the endpoint extractor.

This is the action body of the openapi_endpoint_report rule (see openapi.bzl). It
takes the transitive source jars of a set of root java_library targets, keeps only
entries under a package prefix, and feeds them to the extractor as an explicit
--manifest / --base-dir pair. Source jars are the single, declared definition of
"all sources" shared by the CI drift test and the regeneration script.

Determinism: jars and manifest entries are sorted. A duplicate source path with
differing bytes (a split-package / source-jar conflict) fails loudly rather than
letting a later jar silently overwrite an earlier one.
"""

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--generator", required=True, help="endpoint extractor executable")
    ap.add_argument("--out", required=True, help="output JSON report path")
    ap.add_argument(
        "--src-prefix",
        default="com/google/gerrit/",
        help="comma-separated package prefixes; a jar entry is parsed if it is under any",
    )
    ap.add_argument("--modules", required=True, help="comma-separated module FQCNs")
    ap.add_argument(
        "--scope",
        default=None,
        help="inventory 'scope' label (defaults to all-core-domains in the tool)",
    )
    ap.add_argument(
        "--version",
        default=None,
        help="OpenAPI info.version (the Gerrit release label; openapi format only)",
    )
    ap.add_argument(
        "--context-modules",
        default=None,
        help="comma-separated module FQCNs used for kind/path graph only (not emitted)",
    )
    ap.add_argument(
        "--plugin-name",
        default=None,
        help="emit plugin view segments on core kinds as <plugin-name>~<segment>",
    )
    ap.add_argument(
        "--plugin",
        default=None,
        help="plugin install name; prefixes its HttpModule mounts with /plugins/<name>",
    )
    ap.add_argument("--format", default="inventory", help="extractor output format")
    ap.add_argument("--overrides", default=None, help="OpenAPI overrides sidecar (openapi format)")
    ap.add_argument(
        "--allow-unused-overrides",
        action="store_true",
        help="do not fail on override operations absent from the emitted subset "
        "(per-domain slices apply the whole-API overrides to a subset)",
    )
    ap.add_argument("source_jars", nargs="+")
    args = ap.parse_args()
    prefixes = tuple(p for p in args.src_prefix.split(",") if p)

    with tempfile.TemporaryDirectory(prefix="openapi-src-") as work:
        seen = {}  # entry path -> sha256 of its bytes
        for jar in sorted(args.source_jars):
            with zipfile.ZipFile(jar) as z:
                for name in z.namelist():
                    if not name.endswith(".java") or not name.startswith(prefixes):
                        continue
                    data = z.read(name)
                    digest = hashlib.sha256(data).hexdigest()
                    if name in seen:
                        if seen[name] != digest:
                            sys.stderr.write(
                                "ERROR: duplicate source %s with differing bytes across source "
                                "jars (split-package conflict)\n" % name
                            )
                            sys.exit(3)
                        continue  # same path + same bytes: harmless
                    seen[name] = digest
                    dest = os.path.join(work, name)
                    os.makedirs(os.path.dirname(dest), exist_ok=True)
                    with open(dest, "wb") as f:
                        f.write(data)

        manifest = os.path.join(work, "manifest.txt")
        with open(manifest, "w") as f:
            for name in sorted(seen):
                f.write(name + "\n")

        # --generator is the generator's FilesToRunProvider.executable (see openapi.bzl);
        # Bazel has already staged its runfiles as action tools, so invoke it directly.
        cmd = [
            os.path.abspath(args.generator),
            "--manifest",
            manifest,
            "--base-dir",
            work,
            "--modules",
            args.modules,
            "--format",
            args.format,
            "--out",
            os.path.abspath(args.out),
        ]
        if args.context_modules:
            cmd += ["--context-modules", args.context_modules]
        if args.plugin_name:
            cmd += ["--plugin-name", args.plugin_name]
        if args.plugin:
            cmd += ["--plugin", args.plugin]
        if args.scope:
            cmd += ["--scope", args.scope]
        if args.version:
            cmd += ["--version", args.version]
        if args.overrides:
            cmd += ["--overrides", os.path.abspath(args.overrides)]
        if args.allow_unused_overrides:
            cmd += ["--allow-unused-overrides"]
        result = subprocess.run(cmd, check=False)
        sys.exit(result.returncode)


if __name__ == "__main__":
    main()
