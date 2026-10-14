# modbus-interop — Modbus TCP Interoperability Testing with Docker

A Docker-based interoperability testing framework for Modbus TCP, providing independent client and server implementations, deterministic fixtures, and automated protocol compatibility testing.
Test Modbus libraries against established implementations: libmodbus, PyModbus, digitalpetri/modbus, NModbus and tokio-modbus. Reference implementations are independently containerized, enabling reproducible testing across platforms without integrating third-party protocol stacks into production applications.

| Adapter | Implementation | Version | Language | Server | Client | Image |
|---------|----------------|---------|----------|:------:|:------:|-------|
| `libmodbus` | [libmodbus](https://github.com/stephane/libmodbus) | v3.2.0 | C | yes | yes | `ghcr.io/otfabric/modbus-interop-libmodbus` |
| `pymodbus` | [PyModbus](https://github.com/pymodbus-dev/pymodbus) | 3.16.1 | Python | yes | yes | `ghcr.io/otfabric/modbus-interop-pymodbus` |
| `digitalpetri` | [digitalpetri/modbus](https://github.com/digitalpetri/modbus) | 2.1.6 | Java | yes | yes | `ghcr.io/otfabric/modbus-interop-digitalpetri` |
| `nmodbus` | [NModbus](https://github.com/NModbus/NModbus) | 3.0.83 | C# | yes | yes | `ghcr.io/otfabric/modbus-interop-nmodbus` |
| `tokiomodbus` | [tokio-modbus](https://github.com/slowtec/tokio-modbus) | 0.17.0 | Rust | yes | yes | `ghcr.io/otfabric/modbus-interop-tokiomodbus` |

The stacks do not all do the same: not every one has FC22, FC23, Read
Device Identification, Diagnostics, Report Server ID, file records or FIFO
queues, and they differ in what they check. Each image says
what it supports (`print-capabilities`); see
[docs/CAPABILITIES.md](docs/CAPABILITIES.md) and
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) for the exact versions, the
cross-stack matrix and the findings.

**Docs:** [docs/CONTAINER_CONTRACT.md](docs/CONTAINER_CONTRACT.md) (commands, JSON documents, exit codes) · [docs/FIXTURES.md](docs/FIXTURES.md) (the simulated device and its behaviour) · [docs/CAPABILITIES.md](docs/CAPABILITIES.md) · [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md) (pins, cross-stack matrix, findings) · [REQUIREMENTS.md](REQUIREMENTS.md) · [PLAN.md](PLAN.md) · [RELEASE.md](RELEASE.md)

## What this repository is, and is not

It owns the reference implementations, their adapters, the fixtures, the
container contract, the images and the self-tests that show the references
agree with each other.

It does not contain, import, build or know about any library under test. A
Modbus library is a consumer: it pulls the images and owns its own scenarios,
assertions and compatibility claims. A passing self-test here says the
*references* interoperate; it says nothing about a consumer.

The fixtures describe what a device is; the adapters make each implementation
behave that way; the consumer decides what to assert.

## Quick start

Needs Docker and `jq`.

```sh
make images      # build the adapter images for this machine
make smoke       # container contract of each image
make interop     # every client against every server
```

A reference server on loopback, and a reference client against it:

```sh
make run-libmodbus        # 127.0.0.1:1502, baseline fixture, Ctrl-C to stop
```

```sh
docker run --rm --add-host=host.docker.internal:host-gateway \
  ghcr.io/otfabric/modbus-interop-pymodbus:dev \
  client read-holding-registers --address 0 --quantity 8 \
  --host host.docker.internal --port 1502 | jq .
```

```json
{
  "schemaVersion": "1.0",
  "adapter": "pymodbus",
  "operation": "read-holding-registers",
  "ok": true,
  "error": null,
  "connected": true,
  "unitId": 1,
  "requests": 1,
  "responses": 1,
  "response": { "function": 3, "registers": [0, 1, 32767, 32768, 65535, 4660, 43981, 255] },
  "exception": null,
  "elapsedMs": 12
}
```

Every image has the same commands:

```text
server               a Modbus TCP device serving a fixture
client <operation>   read-coils, read-discrete-inputs, read-holding-registers,
                     read-input-registers, write-single-coil, write-single-register,
                     write-multiple-coils, write-multiple-registers, mask-write-register,
                     read-write-multiple-registers, read-device-identification,
                     diagnostics, report-server-id, read-file-record,
                     write-file-record, read-fifo-queue, raw
print-capabilities   what this adapter supports, as JSON
print-fixture        the fixture baked into the image
```

## Using the images from a library's tests

A consumer needs Docker, the image references and its own test code. It does
not clone this repository.

**Library as client, reference as server.** Start the server, wait for the
readiness file (never for a delay), connect:

```sh
docker run -d --name ref -p 127.0.0.1:1502:1502 ghcr.io/otfabric/modbus-interop-libmodbus:<tag> server
until docker exec ref test -f /run/modbus-interop/ready; do sleep 0.1; done
```

The server's stdout is a JSON Lines stream of the requests it answered
(function, address, quantity, values written, exception sent), for asserting
what the library actually put on the wire.

**Library as server, reference as client.** Serve the fixture with the
library, run a client operation, assert on the result document and exit code:

```sh
docker run --rm --add-host=host.docker.internal:host-gateway \
  ghcr.io/otfabric/modbus-interop-digitalpetri:<tag> \
  client write-multiple-registers --address 20 --values 1,2,3 \
  --host host.docker.internal --port <port of the library's server>
```

**Expected values** come from the image: `print-fixture baseline` prints the
fixture it was built with, so expectations cannot drift from the image.

**What a stack supports** comes from the image too: `print-capabilities`.
Skip, do not fail, a scenario that needs a feature an image declares false.

**Pin by digest** in release qualification
(`ghcr.io/otfabric/modbus-interop-libmodbus@sha256:...`); the digests are in
each release's `manifest.json`.

## Repository layout

```text
modbus-interop/
├── adapters/
│   ├── libmodbus/       C adapter
│   ├── pymodbus/        Python adapter
│   ├── digitalpetri/    Java adapter
│   ├── nmodbus/         C# adapter
│   └── tokiomodbus/     Rust adapter
├── fixtures/
│   ├── schema/          JSON Schema of the fixture format
│   └── baseline/        the baseline device
├── schemas/             JSON Schemas of the capability and client result documents
├── tests/cases.tsv      self-test cases: operation, expected outcome, features needed
├── tests/known-bugs.tsv bugs of upstream versions that are built as candidates
├── scripts/             validate, smoke, cross-stack self-test
├── docs/                contract, fixtures, capabilities, compatibility
├── versions.yaml        every upstream pin and base image digest
├── compose.yaml         the reference servers on loopback
└── Makefile
```

## Development

```sh
make help        # all targets
make ci          # validate, lint, build, smoke, cross-stack self-test, document schemas
make validate    # fixtures against the schema and the cross-field rules
make buildx      # check the other architecture locally, under emulation (CI builds both natively)
make candidate-pymodbus CANDIDATE_VERSION=<version>   # self-test an adapter on another upstream version than the pin
```

`make smoke ADAPTERS=libmodbus` and `VERBOSE=1 make interop` narrow and
expand what is run and shown. See [CONTRIBUTING.md](CONTRIBUTING.md) for
adding an adapter, a fixture or a client operation.

## Security

The servers are unauthenticated Modbus TCP devices that accept writes from
anyone who can reach them. They are test fixtures: `compose.yaml` and the
`run-*` targets publish them on loopback only, and they must never be
attached to a plant network. See [SECURITY.md](SECURITY.md).

## License

The repository's own code and documentation are MIT-licensed. The images
contain the third-party libraries listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) under their own licences.
See [LICENSE](LICENSE).
