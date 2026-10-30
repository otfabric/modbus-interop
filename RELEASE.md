# modbus-interop Releases

## How a release is made

1. Write the notes of the version below and set `interop_version` in `versions.yaml` to it, in the same change. `make validate` (and CI) fails when RELEASE.md has no notes for `interop_version`.
2. `make ci` on the commit to release.
3. Tag `vX.Y.Z` and push the tag. The release workflow runs one job on an amd64 runner and one on an arm64 runner: each validates (including the notes of the tag), builds every image natively, runs the smoke and cross-stack self-tests on those images and pushes exactly them to GHCR as `<image>:vX.Y.Z-amd64` and `-arm64`. A last job joins the two into the multi-arch `<image>:vX.Y.Z` and creates the GitHub release with `manifest.json` (upstream pins and the digests of the multi-arch images) and `checksums.txt`.
4. Consumers pin the digests from `manifest.json`.

The notes are the body of the GitHub release, followed by the image digests:
everything after the heading `## vX.Y.Z` up to the next line that is exactly
`---` (`scripts/release-notes.sh vX.Y.Z` prints it). This is the format of
every otfabric repository and the rule of the shared release workflow, so:
one section per version, newest first, each ending with a `---` line, and no
`---` line inside a section.

Tags are immutable. A fix is a new version.

---

## v0.3.0

**Date:** 2026-10-30

**Previous release:** v0.2.0

## Summary

Self-test results as the organisation's interop-result-v1.

## Changes

### Added

- **`reports/results.json`** ([interop-result-v1](schemas/interop-result-v1.schema.json)): `scripts/interop.sh` writes it when `REPORT_DIR` is set, with stable scenario IDs (a new column of `tests/cases.tsv`) and the digests of the images. Only `pass` counts as passing; a run with no pass, or with a waiver that no longer reproduces, fails. Existing reports keep their shape.
- **`tests/known-bugs.tsv`** has a fifth column, the evidence URL of the waiver.

---

## v0.2.0

**Date:** 2026-10-14

## Summary

Contract 1.1: Diagnostics (FC08 Return Query Data), Report Server ID (FC17), Read and Write File Record (FC20, FC21) and Read FIFO Queue (FC24) as client operations and server behaviour, on the stacks that have them, and PyModbus 3.16.1.

## Changes

### Added

- **Client operations** `diagnostics`, `report-server-id`, `read-file-record`, `write-file-record` and `read-fifo-queue`, with the response fields `subFunction`, `file` and `record` ([docs/CONTAINER_CONTRACT.md](docs/CONTAINER_CONTRACT.md)).
- **Features** `serverDiagnostics`, `serverReportServerId`, `serverFileRecord`, `serverFifoQueue`, `clientDiagnostics`, `clientReportServerId`, `clientReadFileRecord`, `clientWriteFileRecord`, `clientFifoQueue`, and `clientRawAnyResponse`: whether `raw` can receive the normal response of any function code (libmodbus frames responses by function code and cannot) ([docs/CAPABILITIES.md](docs/CAPABILITIES.md)).
- **Fixture fields** `reportServerId`, `fileRecords` and `fifoQueues`, and the behaviour of the device for these functions ([docs/FIXTURES.md](docs/FIXTURES.md)). The baseline fixture has server ID 42, two files and three FIFO queues.
- **Self-tests**: cases for every new operation, raw PDUs for FC08, FC20 and FC24, values compared with the fixture, and an FC21 write read back with FC20 on another connection.

### Changed

- **PyModbus 3.15.0 → 3.16.1.** 3.16.1 fixes the client's FC17 decoding, which took the run indicator into the server ID.

### Support per stack

See the capability matrix in [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md). PyModbus serves and sends all five functions; the other stacks only what their library offers typed (FC17 in libmodbus's client and in tokio-modbus, FC08 in the NModbus server, FC21 in the NModbus client). digitalpetri/modbus 2.1.6 has none of them. Every other server answers them with exception 1, and `raw` reaches them from every client.

---

## v0.1.0

**Date:** 2026-10-10

## Summary

First release: five independent Modbus TCP reference implementations, each as
a server and a client behind one container contract, serving one
deterministic device.

## Changes

### Added

- **Adapters**, each with a `server` and a `client`: `libmodbus` (libmodbus v3.2.0, C), `pymodbus` (PyModbus 3.15.0, Python), `digitalpetri` (digitalpetri/modbus 2.1.6, Java), `nmodbus` (NModbus 3.0.83, C#) and `tokiomodbus` (tokio-modbus 0.17.0, Rust). Every pin is the latest stable upstream release.
- **Container contract 1.0** ([docs/CONTAINER_CONTRACT.md](docs/CONTAINER_CONTRACT.md)): `server`, `client <operation>`, `print-capabilities`, `print-fixture`, `version`; one JSON result document per client run, JSON Lines events from the server, fixed exit codes, readiness by file. Port 1502, user 10001.
- **Client operations**: the four reads, single and multiple writes of coils and registers, mask write (FC22), read/write multiple (FC23), Read Device Identification (FC43/14) and `raw` for any request PDU.
- **Baseline fixture** ([docs/FIXTURES.md](docs/FIXTURES.md)): coils 0..1999, discrete inputs 1000..2999, holding registers 0..255, input registers 65280..65535, seven device identification objects, with the device behaviour every server follows.
- **Capability documents** ([docs/CAPABILITIES.md](docs/CAPABILITIES.md)) and JSON Schemas for them, for client results and for fixtures.
- **Self-tests**: a contract smoke test per adapter and the cross-stack self-test, every client against every server over [tests/cases.tsv](tests/cases.tsv), in which all pairings must return the same result.
- **Upstream candidates** workflow: weekly, compares every pin with the latest upstream release, self-tests a newer one as a candidate, and opens an issue while a pin is behind.
- **Release workflow**: native linux/amd64 and linux/arm64 images, joined into multi-arch tags, with `manifest.json` and checksums.

### Upstream notes

[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) has the capability matrix and the findings. The ones a consumer will notice:

- FC22 is missing from NModbus; FC43 from libmodbus, digitalpetri/modbus and the NModbus server. Those cases are skipped for them.
- The PyModbus server answers an invalid read quantity, and as shipped any unknown function code, with function code `0x80` and exception 1. It declares `serverQuantityCheck` false.
- As shipped, digitalpetri/modbus and NModbus close the connection on an unknown function code and stay silent for another unit identifier; the adapters answer with exceptions 1 and 11 through the libraries' extension points.
- The tokio-modbus server panics in its decoder on an FC15 request whose byte count is too small.

### Not in this release

- Modbus RTU, ASCII, UDP and TLS: Modbus TCP only.
- Self-test cases that need a misbehaving peer (`clientUnitIdCheck`, `invalid-response`, `connection-lost`, requests split over TCP segments).
- Cases for invalid writes (FC05 value, FC15/FC16 quantity and byte count): the stacks differ too much as shipped; their behaviour is recorded in the compatibility document.

---
