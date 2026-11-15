#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Tests of scripts/release-manifest.py, which writes the release's
manifest.json and refuses a release without the digest of every adapter of
ADAPTERS in the Makefile."""

import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SCRIPT = os.path.join(HERE, "release-manifest.py")
COMMIT = "0123456789abcdef0123456789abcdef01234567"

with open(os.path.join(ROOT, "Makefile")) as f:
    ADAPTERS = re.search(r"^ADAPTERS \?= (.+)$", f.read(), re.M).group(1).split()
with open(os.path.join(ROOT, "Makefile")) as f:
    LOCAL = re.search(r"^LOCAL_ADAPTERS \?= (.+)$", f.read(), re.M).group(1).split()


def digest(n):
    return "sha256:%064x" % n


class ReleaseManifest(unittest.TestCase):
    def run_with(self, lines):
        with tempfile.TemporaryDirectory() as tmp:
            digests = os.path.join(tmp, "digests.txt")
            out, body = os.path.join(tmp, "manifest.json"), os.path.join(tmp, "images.md")
            with open(digests, "w") as f:
                f.write("".join(line + "\n" for line in lines))
            p = subprocess.run([sys.executable, SCRIPT, "v9.9.9", COMMIT, digests, out, body],
                               capture_output=True, text=True)
            manifest = None
            if os.path.exists(out):
                with open(out) as f:
                    manifest = json.load(f)
            return p.returncode, p.stderr, manifest

    def every_image(self):
        return ["%s %s" % (a, digest(i + 1)) for i, a in enumerate(ADAPTERS)]

    def test_every_adapter_by_digest(self):
        code, err, manifest = self.run_with(self.every_image())
        self.assertEqual(code, 0, err)
        self.assertEqual(list(manifest["images"]), ADAPTERS)
        for i, a in enumerate(ADAPTERS):
            self.assertEqual(manifest["images"][a], "ghcr.io/otfabric/modbus-interop-%s@%s" % (a, digest(i + 1)))
        self.assertEqual(sorted(manifest["upstream"]), sorted(ADAPTERS))

    def test_a_partial_digest_file_refuses_the_release(self):
        # make digests stopped after the third adapter.
        code, err, manifest = self.run_with(self.every_image()[:3])
        self.assertEqual(code, 1)
        self.assertIsNone(manifest)
        self.assertIn("missing %s;" % " ".join(ADAPTERS[3:]), err)

    def test_an_adapter_that_is_not_published_is_refused(self):
        code, err, manifest = self.run_with(self.every_image() + ["%s %s" % (LOCAL[0], digest(99))])
        self.assertEqual(code, 1)
        self.assertIsNone(manifest)
        self.assertIn("not published or repeated %s" % LOCAL[0], err)

    def test_a_repeated_adapter_is_refused(self):
        lines = self.every_image()
        code, err, manifest = self.run_with(lines[1:] + [lines[1]])
        self.assertEqual(code, 1)
        self.assertIsNone(manifest)
        self.assertIn("missing %s; not published or repeated %s" % (ADAPTERS[0], ADAPTERS[1]), err)

    def test_a_line_that_is_not_adapter_and_digest_is_refused(self):
        code, err, manifest = self.run_with(self.every_image() + [ADAPTERS[0]])
        self.assertEqual(code, 1)
        self.assertIsNone(manifest)
        self.assertIn("is not \"<adapter> <digest>\"", err)

    def test_a_malformed_digest_is_refused(self):
        lines = self.every_image()
        code, err, manifest = self.run_with(lines[:-1] + ["%s sha256:abc" % ADAPTERS[-1]])
        self.assertEqual(code, 1)
        self.assertIsNone(manifest)
        self.assertIn("is not a digest", err)


if __name__ == "__main__":
    unittest.main()
