#!/usr/bin/env python3
"""Produce a deterministic core-context source-closure archive + provenance.

The core-context archive is the versioned input a plugin extractor consumes
instead of reaching into live core: the REST-context + DTO source closure the
extractor's manifest requires (gerrit6 `RestEndpoints.prepare()` throws
"context module not found in manifest" without it), plus the mined overrides.

SKELETON: here we archive the declared `--src` inputs; the real bootstrap feeds
the actual gerrit6 context/DTO source closure from the frozen revision. The
*contract* (deterministic archive + provenance manifest) is real and tested.

Determinism: a tar with fixed mtime/uid/gid/uname/gname and sorted entries, so
the archive bytes — and therefore `archive_sha256` — are stable across machines.

CLI:
    --version V          baseline version the archive matches
    --source-rev REV     exact Gerrit source revision it was built from
    --src PATH           repeatable; source-closure inputs
    --overrides PATH     mined/curated core overrides (optional)
    --arcprefix NAME     archive path prefix (default: core-context)
    --out-archive PATH   deterministic .tar
    --out-provenance PATH provenance JSON (schema/version/rev/digest/entries)
"""

import argparse
import hashlib
import io
import json
import os
import tarfile


def _sha256(b: bytes) -> str:
    return hashlib.sha256(b).hexdigest()


def _add(tar: tarfile.TarFile, arcname: str, data: bytes) -> None:
    ti = tarfile.TarInfo(name=arcname)
    ti.size = len(data)
    ti.mtime = 0
    ti.uid = ti.gid = 0
    ti.uname = ti.gname = ""
    ti.mode = 0o644
    tar.addfile(ti, io.BytesIO(data))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--version", required=True)
    ap.add_argument("--source-rev", required=True)
    ap.add_argument("--src", action="append", default=[])
    ap.add_argument("--overrides")
    ap.add_argument("--arcprefix", default="core-context")
    ap.add_argument("--out-archive", required=True)
    ap.add_argument("--out-provenance", required=True)
    a = ap.parse_args()

    members = [(f"{a.arcprefix}/{os.path.basename(p)}", open(p, "rb").read()) for p in a.src]
    if a.overrides:
        members.append((f"{a.arcprefix}/overrides.json", open(a.overrides, "rb").read()))
    members.sort(key=lambda m: m[0])

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode="w", format=tarfile.USTAR_FORMAT) as tar:
        for arcname, data in members:
            _add(tar, arcname, data)
    archive = buf.getvalue()

    with open(a.out_archive, "wb") as f:
        f.write(archive)

    provenance = {
        "schema": "gerrit_rules_openapi/core-context@1",
        "version": a.version,
        "source_rev": a.source_rev,
        "archive_sha256": _sha256(archive),
        "entries": [{"path": n, "sha256": _sha256(d)} for n, d in members],
    }
    with open(a.out_provenance, "w", encoding="utf-8") as f:
        json.dump(provenance, f, indent=2, sort_keys=True)
        f.write("\n")


if __name__ == "__main__":
    main()
