#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Tests of scripts/check-versions.sh: it runs on a copy of versions.yaml and
the Dockerfiles with one value changed, and must fail with the reason."""

import glob
import os
import re
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)


def value(path, pattern):
    with open(os.path.join(ROOT, path)) as f:
        return re.search(pattern, f.read(), re.M).group(1)


class CheckVersions(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.tmp)
        files = ["versions.yaml", "scripts/check-versions.sh", "adapters/pymodbus/requirements.txt",
                 "adapters/tokiomodbus/Cargo.lock", "adapters/jsmodbus/package-lock.json"]
        files += [os.path.relpath(p, ROOT) for p in glob.glob(os.path.join(ROOT, "adapters", "*", "Dockerfile"))]
        files += [os.path.relpath(p, ROOT) for p in glob.glob(os.path.join(ROOT, "own", "*", "Dockerfile"))]
        for f in files:
            os.makedirs(os.path.dirname(os.path.join(self.tmp, f)), exist_ok=True)
            shutil.copy(os.path.join(ROOT, f), os.path.join(self.tmp, f))

    def edit(self, path, old, new):
        p = os.path.join(self.tmp, path)
        with open(p) as f:
            text = f.read()
        text, n = re.subn(old, new, text, count=1, flags=re.M)
        self.assertEqual(n, 1, "%s: %r not found" % (path, old))
        with open(p, "w") as f:
            f.write(text)

    def check(self):
        p = subprocess.run(["bash", os.path.join(self.tmp, "scripts", "check-versions.sh")],
                           capture_output=True, text=True)
        return p.returncode, p.stdout + p.stderr

    def fails(self, *reasons):
        code, out = self.check()
        self.assertEqual(code, 1, out)
        for r in reasons:
            self.assertIn(r, out)

    def test_the_pins_agree(self):
        code, out = self.check()
        self.assertEqual(code, 0, out)
        self.assertNotIn("FAIL", out)
        # Stage references (FROM toolchain AS upstream) pass as they are.
        self.assertIn("libmodbus revision", out)

    def test_a_base_image_without_a_digest_fails_although_it_names_a_stage(self):
        self.edit("adapters/pymodbus/Dockerfile", r"^FROM \$\{BASE_REGISTRY\}/python:\S+ AS build$",
                  "FROM python:3.14-slim AS build")
        self.fails("FAIL adapters/pymodbus/Dockerfile: FROM python:3.14-slim is neither pinned")

    def test_an_image_named_like_a_later_stage_fails(self):
        self.edit("adapters/libmodbus/Dockerfile", r"^FROM \$\{BASE_REGISTRY\}/debian:\S+ AS toolchain$",
                  "FROM debian AS toolchain")
        self.fails("FROM debian is neither pinned")

    def test_a_digest_argument_the_dockerfile_does_not_declare_fails(self):
        self.edit("adapters/jsmodbus/Dockerfile", r"^(FROM \$\{BASE_REGISTRY\}/node:\S+@)\$\{NODE_DIGEST\}( AS runtime)$",
                  r"\1${RUNTIME_DIGEST}\2")
        self.fails("FROM ${BASE_REGISTRY}/node:26.13.0-trixie-slim@${RUNTIME_DIGEST} is neither pinned")

    def test_the_version_of_another_implementation_fails(self):
        other = value("adapters/modbus4j/Dockerfile", r"^ARG UPSTREAM_VERSION=(.+)$")
        self.edit("adapters/j2mod/Dockerfile", r"^ARG UPSTREAM_VERSION=.+$", "ARG UPSTREAM_VERSION=" + other)
        self.fails('FAIL j2mod version: version: "%s" is not in implementations.j2mod' % other)

    def test_the_revision_of_another_implementation_fails(self):
        other = value("adapters/modbusesp/Dockerfile", r"^ARG UPSTREAM_SHA=(.+)$")
        self.edit("adapters/libmodbus/Dockerfile", r"^ARG UPSTREAM_SHA=.+$", "ARG UPSTREAM_SHA=" + other)
        self.fails('FAIL libmodbus revision: revision: "%s" is not in implementations.libmodbus' % other)

    def test_a_source_hash_only_in_versions_yaml_fails(self):
        self.edit("versions.yaml", r'^(    source_sha256: ")[0-9a-f]{64}"', r'\g<1>%s"' % ("0" * 64))
        self.fails("FAIL modbus4j source hash")

    def test_a_bundled_version_only_in_the_dockerfile_fails(self):
        self.edit("adapters/libmodbus/Dockerfile", r"^ARG CJSON_VERSION=.+$", "ARG CJSON_VERSION=1.7.19-1")
        self.fails('FAIL libmodbus CJSON_VERSION: "1.7.19-1" is not in implementations.libmodbus')

    def test_an_artifact_hash_only_in_versions_yaml_fails(self):
        for field, adapter, lock, other in (("wheel_sha256", "pymodbus", "requirements.txt", "0" * 64),
                                            ("crate_sha256", "tokiomodbus", "Cargo.lock", "0" * 64),
                                            ("tarball_integrity", "jsmodbus", "package-lock.json", "sha512-" + "A" * 86 + "==")):
            with self.subTest(field):
                self.setUp()
                self.edit("versions.yaml", r'^(    %s: ")[^"]+"' % field, r'\g<1>%s"' % other)
                self.fails('FAIL %s %s: %s of versions.yaml is not in adapters/%s/%s' % (adapter, field, other, adapter, lock))

    def test_a_digest_of_the_own_dockerfile_fails(self):
        # The own Dockerfile's golang image is in versions.yaml and no adapter's.
        own = [os.path.relpath(p, ROOT) for p in glob.glob(os.path.join(ROOT, "own", "*", "Dockerfile"))]
        self.assertEqual(len(own), 1, own)
        self.edit(own[0], r"^(ARG GOLANG_DIGEST=sha256:)[0-9a-f]{64}$", r"\g<1>%s" % ("0" * 64))
        self.fails("FAIL %s GOLANG_DIGEST" % own[0])

    def test_an_adapter_without_an_entry_fails(self):
        self.edit("versions.yaml", r"^  tokiomodbus:$", "  tokio_modbus:")
        self.fails("FAIL tokiomodbus: versions.yaml has no implementations.tokiomodbus")


if __name__ == "__main__":
    unittest.main()
