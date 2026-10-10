#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Compares the upstream pin of one adapter with the latest upstream release.

    scripts/latest-upstream.py <adapter>

Prints key=value lines (the format of $GITHUB_OUTPUT):

    pin=<version in the adapter Dockerfile>
    latest=<latest stable upstream release>
    latest_sha=<its commit, for an adapter that is pinned by commit>
    update=yes|no
    source=<where the latest release was looked up>

Nothing is modified. Pre-releases are never "latest".
"""

import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AGENT = "modbus-interop latest-upstream (https://github.com/otfabric/modbus-interop)"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/json, */*"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode()


def key(version):
    """Sort key of a stable version; None for anything else (pre-releases)."""
    match = re.fullmatch(r"v?(\d+(?:\.\d+)*)", version)
    return tuple(int(part) for part in match.group(1).split(".")) if match else None


def newest(versions):
    stable = [v for v in versions if key(v) is not None]
    if not stable:
        raise SystemExit("no stable release found upstream")
    return max(stable, key=key)


def git_tags(repository):
    """Tags of repository as {tag: commit}; an annotated tag gives its commit."""
    out = subprocess.check_output(["git", "ls-remote", "--tags", repository], text=True)
    commits = {}
    for line in out.splitlines():
        sha, ref = line.split()
        tag = ref.removeprefix("refs/tags/")
        if tag.endswith("^{}"):
            commits[tag[:-3]] = sha
        else:
            commits.setdefault(tag, sha)
    return commits


def libmodbus():
    tags = git_tags("https://github.com/stephane/libmodbus.git")
    latest = newest(tags)
    return latest, tags[latest], "git tags of stephane/libmodbus"


def pymodbus():
    releases = json.loads(fetch("https://pypi.org/pypi/pymodbus/json"))["releases"]
    live = [v for v, files in releases.items() if files and not all(f.get("yanked") for f in files)]
    return newest(live), "", "PyPI"


def digitalpetri():
    meta = fetch("https://repo1.maven.org/maven2/com/digitalpetri/modbus/modbus-tcp/maven-metadata.xml")
    return newest(re.findall(r"<version>([^<]+)</version>", meta)), "", "Maven Central"


def nmodbus():
    index = json.loads(fetch("https://api.nuget.org/v3-flatcontainer/nmodbus/index.json"))
    return newest(index["versions"]), "", "nuget.org"


def tokiomodbus():
    crate = json.loads(fetch("https://crates.io/api/v1/crates/tokio-modbus"))
    live = [v["num"] for v in crate["versions"] if not v.get("yanked")]
    return newest(live), "", "crates.io"


RESOLVERS = {
    "libmodbus": libmodbus,
    "pymodbus": pymodbus,
    "digitalpetri": digitalpetri,
    "nmodbus": nmodbus,
    "tokiomodbus": tokiomodbus,
}


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in RESOLVERS:
        raise SystemExit(f"usage: latest-upstream.py {'|'.join(RESOLVERS)}")
    adapter = sys.argv[1]
    dockerfile = (ROOT / "adapters" / adapter / "Dockerfile").read_text()
    match = re.search(r"^ARG UPSTREAM_VERSION=(.+)$", dockerfile, re.M)
    if not match:
        raise SystemExit(f"adapters/{adapter}/Dockerfile has no ARG UPSTREAM_VERSION=<pin>")
    pin = match.group(1).strip()
    latest, latest_sha, source = RESOLVERS[adapter]()
    print(f"pin={pin}")
    print(f"latest={latest}")
    print(f"latest_sha={latest_sha}")
    print(f"update={'yes' if key(latest) != key(pin) else 'no'}")
    print(f"source={source}")


if __name__ == "__main__":
    main()
