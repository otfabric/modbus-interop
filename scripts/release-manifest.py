#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Writes manifest.json and the images part of the GitHub release body.

    scripts/release-manifest.py <version> <commit> <digests file> <manifest out> <images out>

The release workflow puts the notes of the version (scripts/release-notes.sh)
in front of the images part.

The digests file holds one "<adapter> <sha256:...>" line per published
multi-arch image (make digests). Upstream pins are read from the adapter
Dockerfiles, the contract and fixture versions from versions.yaml.
"""

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REGISTRY = "ghcr.io/otfabric/modbus-interop-"


def arg(adapter, name):
    text = (ROOT / "adapters" / adapter / "Dockerfile").read_text()
    match = re.search(rf"^ARG {name}=(.+)$", text, re.M)
    return match.group(1).strip() if match else ""


def manifest_value(key):
    match = re.search(rf'^{key}: "([^"]+)"', (ROOT / "versions.yaml").read_text(), re.M)
    if not match:
        raise SystemExit(f"versions.yaml has no {key}")
    return match.group(1)


def main():
    if len(sys.argv) != 6:
        raise SystemExit(__doc__)
    version, commit, digests_file, manifest_out, body_out = sys.argv[1:]
    digests = dict(line.split() for line in Path(digests_file).read_text().splitlines() if line.strip())
    if not digests:
        raise SystemExit(f"{digests_file} lists no image")

    upstream, images = {}, {}
    for adapter, digest in digests.items():
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
            raise SystemExit(f"{adapter}: {digest!r} is not a digest")
        pin = {"version": arg(adapter, "UPSTREAM_VERSION")}
        if not pin["version"]:
            raise SystemExit(f"adapters/{adapter}/Dockerfile has no ARG UPSTREAM_VERSION")
        if revision := arg(adapter, "UPSTREAM_SHA"):
            pin["revision"] = revision
        upstream[adapter] = pin
        images[adapter] = f"{REGISTRY}{adapter}@{digest}"

    Path(manifest_out).write_text(json.dumps({
        "version": version,
        "commit": commit,
        "contract": manifest_value("contract_version"),
        "fixtureSchema": manifest_value("fixture_schema"),
        "platforms": ["linux/amd64", "linux/arm64"],
        "upstream": upstream,
        "images": images,
    }, indent=2) + "\n")

    body = ["## Images", "", "| Image | Upstream | Digest |", "|-------|----------|--------|"]
    for adapter, digest in digests.items():
        body.append(f"| `{REGISTRY}{adapter}:{version}` | {upstream[adapter]['version']} | `{digest}` |")
    body += ["", "## Pinning in a consumer", "", "```bash"]
    for adapter, image in images.items():
        body.append(f"MODBUS_INTEROP_{adapter.upper()}_IMAGE={image}")
    body += [
        "```",
        "",
        "`manifest.json` records the upstream versions these images were built from. "
        "The images contain third-party software under its own licences; see "
        "`THIRD_PARTY_NOTICES.md` and `/licenses` in each image. Notes: `RELEASE.md`.",
        "",
    ]
    Path(body_out).write_text("\n".join(body))


if __name__ == "__main__":
    main()
