# modbus-interop-otfabric

[go-modbus](https://github.com/otfabric/go-modbus), OT Fabric's own Modbus
stack, behind the [container contract](../../docs/CONTAINER_CONTRACT.md), so
that other stacks can test against it. Anything that uses a
`modbus-interop-<adapter>` image can use this one in its place. Unlike those, it
is not a reference: its tags are go-modbus versions, and no harness release or
`manifest.json` contains it.

**For testing only.** It runs an unauthenticated Modbus server. Do not expose it
to a network you do not control. Old images stay available, so check
[go-modbus advisories](https://github.com/otfabric/go-modbus/security/advisories)
for the version you pull.

**Not an independent implementation.** The image declares
`upstream.independentEngine: false`, and `results.json` records
`independent: false`. A result against it says nothing independent about
go-modbus when OT Fabric produces it. A result you publish, naming the
implementation and version, is independent evidence, and we would like to hear
about it.

## Use

```sh
docker run -d --name go-modbus -p 127.0.0.1:1502:1502 ghcr.io/otfabric/modbus-interop-otfabric:<tag> server
until docker exec go-modbus test -f /run/modbus-interop/ready; do sleep 0.1; done
```

`<tag>` is `<go-modbus version>-<commit>`, which never moves, or
`<go-modbus version>`, the newest build of that release. There is no `latest`.
Each publish is a [release](https://github.com/otfabric/modbus-interop/releases)
named `modbus-interop-otfabric/<tag>` with the image digest. Pin the digest.

| | |
|---|---|
| Upstream | go-modbus, a released tag, pinned in `Dockerfile`, `go.mod` and `versions.yaml` |
| Licence | MIT; every module's licence is in the image under `/licenses` |
| Adapter name | `otfabric` (the name in documents, as the schema allows) |
| Language | Go |
| Protocol engine | OT Fabric's own |

## Why it is apart from the other adapters

`REQUIREMENTS.md` R1: nothing under `adapters/`, `scripts/`, `tests/` or the
top-level `Makefile` knows a library under test, and CI enforces that. This
directory and `.github/workflows/own-go-modbus.yml` are the one exception. The
scripts take the image through `ADAPTER_IMAGE_otfabric`.

The dependency runs one way, so no release loop can form
(`otfabric/engineering` ADR-0012). This image builds a released go-modbus tag.
No release or required check of go-modbus needs it, and go-modbus commits no pin
of it. It is not in a harness release or `manifest.json`.

## Moving the pin

The workflow reports a newer go-modbus release in an issue each day. To move:

1. In `Dockerfile`, both `ARG UPSTREAM_VERSION` lines and `ARG UPSTREAM_SHA` (the
   commit of the tag).
2. `go get github.com/otfabric/go-modbus@<version> && go mod tidy` here.
3. `own_implementations` in `../../versions.yaml`.

Merging publishes, after the smoke test and the cross-stack self-test against
the latest released references have passed on both architectures. Run the
workflow by hand to publish at once.

```sh
make -f own/go-modbus/Makefile image smoke interop    # from the repository root
```

## What it cannot do

Declared `false`, because the public API of go-modbus does not offer it and the
adapter does not encode protocol itself:

- `clientRaw`, `clientRawAnyResponse`: no way to send an arbitrary PDU.
- `connectionEvents`: no hook for a connection opening or closing.

`requestEvents` is declared `true`, with one limit: the handlers print the
events, so a request the library answers by itself prints none. That is a
quantity out of range (exception 3) and a function code without a handler
(exception 1).

`clientUnitIdCheck` has no case in `tests/cases.tsv`, since no reference server
answers with a wrong unit identifier. `main_test.go` checks it against a scripted
peer.
