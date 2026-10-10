# Container contract

Version: **1.0** (`contract_version` in [versions.yaml](../versions.yaml))

Every adapter image exposes the same commands, flags, JSON documents and exit
codes. Consumers depend on this document, not on any adapter's source. A
change that breaks it bumps the major version; an addition bumps the minor
one. Documents carry `schemaVersion` `"1.0"`: it names the compatible line
and changes only with the major version.

| Version | Change |
|---------|--------|
| 1.0 | First release |

Not every adapter has every operation or event. What an image lacks it says
in [`print-capabilities`](#print-capabilities), and an operation it does not
list in `clientOperations` is a usage error (exit 2).

What a *device* answers to a request is defined by its fixture: see
[FIXTURES.md](FIXTURES.md).

## Contents

- [Image layout](#image-layout)
- [Commands](#commands)
- [`server`](#server)
- [`client`](#client)
- [`print-capabilities`](#print-capabilities)
- [`print-fixture`](#print-fixture)
- [Exit codes](#exit-codes)
- [Output rules](#output-rules)

## Image layout

| Item | Value |
|------|-------|
| Image | `ghcr.io/otfabric/modbus-interop-<adapter>` |
| Entrypoint | `modbus-interop` (`/usr/local/bin/modbus-interop`) |
| Default command | `server` |
| Protocol | Modbus TCP (MBAP) |
| Port | 1502/tcp (not 502: the process is not root) |
| User | non-root, uid 10001 |
| Fixtures | `/fixtures/<name>/fixture.json` (baked in; mount over `/fixtures` to replace) |
| Readiness file | `/run/modbus-interop/ready` |
| Health check | built in: `test -f /run/modbus-interop/ready` |
| Licences | `/licenses/` |

## Commands

```text
modbus-interop server [flags]
modbus-interop client <operation> --host HOST [flags]
modbus-interop print-capabilities
modbus-interop print-fixture [NAME]
modbus-interop version
```

Flags are `--name value`; switches are bare `--name`. An unknown flag is a
usage error (exit 2), never ignored. Numbers are decimal, or hexadecimal with
a `0x` prefix.

## `server`

Runs a Modbus TCP device that serves one fixture until it receives SIGTERM or
SIGINT.

| Flag | Default | Meaning |
|------|---------|---------|
| `--fixture PATH` | `/fixtures/baseline/fixture.json` | Fixture to serve |
| `--bind-address ADDR` | `0.0.0.0` | Listen address |
| `--port N` | 1502 | Listen port |
| `--ready-file PATH` | `/run/modbus-interop/ready` | Readiness file |

Lifecycle:

1. The fixture is loaded and validated. An invalid fixture ends the process
   with exit 4 before anything listens. The rules are those of
   `scripts/validate-fixtures.py`, including that `name` is the name of the
   directory the file is in: mount a fixture as `<dir>/<name>/fixture.json`.
2. The listener is opened. Failure ends the process with a non-zero exit.
3. The readiness file is written and the `ready` event is printed. From here
   on the device accepts Modbus TCP connections. **Readiness is the file (or
   the event), not the open port and not a delay.**
4. On SIGTERM/SIGINT the readiness file is removed, connections are closed,
   the `stopped` event is printed and the process exits 0.

At least 16 simultaneous connections are accepted, and a connection may carry
any number of requests. The device state (coils and registers) is shared by
all connections and lives as long as the process: restart the container to
return to the fixture.

### Server events

One JSON object per line on stdout.

| `event` | Other fields | When |
|---------|--------------|------|
| `ready` | `adapter`, `address`, `fixture`, `unitId` | Once, when the device accepts connections |
| `connection-opened` | `peer` (`ip:port`) | A client connected. Only with the feature `connectionEvents` |
| `connection-closed` | `peer` | The connection ended. Only with the feature `connectionEvents` |
| `request` | see below | A request was answered. Only with the feature `requestEvents` |
| `stopped` | | Once, after a clean stop |

A `request` event describes one request and how it was answered:

| Field | Meaning |
|-------|---------|
| `unitId` | Unit identifier of the request |
| `function` | Function code of the request |
| `address`, `quantity` | For the functions that carry them. FC05, FC06 and FC22 have `quantity` 1. FC23 reports the read part here |
| `bits`, `registers` | What a write carried: `bits` for FC05 and FC15, `registers` for FC06 and FC16 |
| `andMask`, `orMask` | FC22 |
| `writeAddress`, `writeRegisters` | FC23: the write part |
| `exception` | `null` when the request was answered normally, otherwise the exception code sent |

A request that is not answered at all (a malformed frame) produces no event.
The order of the events of one connection follows the protocol.

## `client`

Runs one bounded operation as a Modbus TCP client and prints one result
document.

```text
modbus-interop client <operation> --host HOST [--port N] [common flags] [operation flags]
```

Every operation: connect, send the request, wait for the response, close the
connection.

### Common flags

| Flag | Default | Meaning |
|------|---------|---------|
| `--host HOST` | required | Device to connect to |
| `--port N` | 1502 | |
| `--unit-id N` | 1 | Unit identifier of the request, 0..255 |
| `--connect-timeout-ms N` | 5000 | TCP connect timeout |
| `--timeout-ms N` | 5000 | Timeout of the wait for each response |
| `--repeat N` | 1 | Send the request N times (1..1000) on the same connection, each after the response to the one before. Only with the feature `clientRepeat` |

### Operations

| Operation | Function | Flags |
|-----------|---------:|-------|
| `read-coils` | 01 | `--address N`, `--quantity N` (1..2000) |
| `read-discrete-inputs` | 02 | `--address N`, `--quantity N` (1..2000) |
| `read-holding-registers` | 03 | `--address N`, `--quantity N` (1..125) |
| `read-input-registers` | 04 | `--address N`, `--quantity N` (1..125) |
| `write-single-coil` | 05 | `--address N`, `--value V` |
| `write-single-register` | 06 | `--address N`, `--value N` |
| `write-multiple-coils` | 15 | `--address N`, `--values V,V,...` (1..1968) |
| `write-multiple-registers` | 16 | `--address N`, `--values N,N,...` (1..123) |
| `mask-write-register` | 22 | `--address N`, `--and-mask N`, `--or-mask N`. Only with the feature `clientMaskWrite` |
| `read-write-multiple-registers` | 23 | `--read-address N`, `--read-quantity N` (1..125), `--write-address N`, `--values N,N,...` (1..121). Only with the feature `clientReadWriteMultiple` |
| `read-device-identification` | 43/14 | `--category C`: `basic` (default), `regular` or `extended`. Stream access starting at object 0; the client follows "more follows" until it has the whole category. Only with the feature `clientDeviceIdentification` |
| `raw` | any | `--function N` (1..127), `--data HEX` (the request PDU after the function code, may be empty). Only with the feature `clientRaw` |

Addresses are 0..65535 and are the numbers on the wire, not 1-based
"register numbers". A coil value is `true`/`false` (also `1`/`0`,
`on`/`off`); a register value is 0..65535. Every flag of an operation is
required unless a default is given. A value outside these ranges, including a
quantity the protocol does not allow, is a usage error (exit 2): nothing is
sent. `raw` is the way to send what the protocol does not allow.

An operation succeeds when the device answers with the normal response to the
request. With `--repeat`, it succeeds when every response is normal and all
of them are equal; the operation ends at the first exception or error.

Left to the adapter, and therefore not to be relied on: whether a flag given
twice is a usage error or the last one wins, and what `response` holds after
a failure under `--repeat` (the last normal response, or `null`).
`--bind-address` is an IP address literal; a host name may be refused.

### Result document

Exactly one JSON object on one line of stdout, for every invocation that got
past argument parsing, including failures.

| Field | Type | Meaning |
|-------|------|---------|
| `schemaVersion` | string | `"1.0"` |
| `adapter` | string | Adapter that ran the operation |
| `operation` | string | As given on the command line |
| `ok` | boolean | The operation succeeded as defined above |
| `error` | object or null | `{ "code", "message" }` when `ok` is false |
| `connected` | boolean | The TCP connection was established |
| `unitId` | number | Unit identifier of the request |
| `requests` | number | Requests sent |
| `responses` | number | Normal responses received |
| `response` | object or null | The normal response (the last one with `--repeat`), see below; `null` when there was none |
| `exception` | object or null | `{ "function", "code" }` when the device answered with an exception response: `function` is the function code of that response (request function + 128) and `code` the exception code |
| `elapsedMs` | number | Wall-clock duration |

`response` has `function` (the function code) and, depending on it:

| Function | Fields |
|---------:|--------|
| 01, 02 | `bits`: array of booleans, exactly the requested quantity |
| 03, 04, 23 | `registers`: array of numbers 0..65535 |
| 05 | `address`, `value` (boolean) |
| 06 | `address`, `value` (0..65535) |
| 15, 16 | `address`, `quantity` |
| 22 | `address`, `andMask`, `orMask` |
| 43 | `conformityLevel` (number), `objects`: array of `{ "id", "value" }` in ascending `id`, `value` being the object as a string |
| `raw` | `data`: the response PDU after the function code, lower-case hex |

A library that checks the echo of a write response itself and does not hand
it out reports the values of the request there: they are what the library
confirmed.

`error.code`:

| Code | Meaning | Exit |
|------|---------|------|
| `connect-failed` | No TCP connection | 3 |
| `exception` | The device answered with an exception response; see `exception` | 1 |
| `timeout` | The device did not answer in time | 1 |
| `connection-lost` | The connection closed during the operation | 1 |
| `invalid-response` | A response arrived that is not a valid answer to the request (wrong function, length, transaction or unit identifier, echo mismatch) | 1 |
| `failed` | Anything else, including responses that differ under `--repeat`; see `message` | 1 |

`message` is for people and not part of the contract.

## `print-capabilities`

Prints one JSON object on one line and exits 0. See
[CAPABILITIES.md](CAPABILITIES.md) for the fields and the values of each
adapter. Needs no network and no fixture.

## `print-fixture`

`print-fixture [NAME]` (default `baseline`) writes the fixture baked into the
image to stdout, byte for byte, and exits 0. An unknown name exits 1.

A consumer reads the expected values from the image it tests against, so it
needs no checkout of this repository and cannot drift from the image.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success. `server`: clean stop after SIGTERM/SIGINT |
| 1 | The operation failed (see `error.code`); `server`: could not listen |
| 2 | Usage error: unknown command, operation or flag, missing or invalid value. Nothing was sent |
| 3 | `client`: the connection could not be established |
| 4 | `server`: the fixture is missing or invalid |

## Output rules

- **stdout carries JSON only**: server events as JSON Lines, one client
  result, one capability document. `print-fixture` and `version` are the
  exceptions and print their own format.
- **stderr carries diagnostics** for people. Nothing on stderr is part of the
  contract. Library logging goes there too.
- Field names and exit codes are stable within a major contract version.
  Fields may be added in a minor version; consumers ignore fields they do not
  know.
- Every wait is bounded: by `--timeout-ms` or `--connect-timeout-ms`. No
  client operation runs indefinitely.
