# tokiomodbus adapter

A Modbus TCP server and client behind the
[container contract](../../docs/CONTAINER_CONTRACT.md), built on
[tokio-modbus](https://github.com/slowtec/tokio-modbus).

| | |
|---|---|
| Upstream | tokio-modbus, slowtec GmbH |
| Version | 0.17.0 (crates.io, released 2025-10-22; the newest release, 0.16.2 to 0.16.5 are yanked) |
| Artifact | `tokio-modbus-0.17.0.crate`, SHA-256 `95d06136e737e962c71d922a09148f5a264f6609a021fdf031ad16ffd172c818` (pinned in `Cargo.lock`, reported as `upstream.revision`) |
| Source commit | `dd151a6a10a9e74bb7c982578aed21413562dc5a` (from `.cargo_vcs_info.json` in the crate) |
| Licence | MIT OR Apache-2.0 |
| Language | Rust, on the tokio runtime |
| Protocol engine | its own |

## Build

```sh
make image-tokiomodbus          # from the repository root
```

- Build stage: `rust:1.99.0-slim-trixie`, runtime stage: `debian:trixie-slim`,
  both pinned by index digest.
- `Cargo.toml` pins the direct dependencies to exact versions (tokio-modbus
  0.17.0 with only the `tcp` and `tcp-server` features, tokio 1.53.2,
  serde_json 1.0.151) and the committed `Cargo.lock` pins all 31 crates with
  checksums. The build is `cargo build --locked --offline --release`.
- **Candidate build**: `--build-arg UPSTREAM_VERSION=<x.y.z>` other than the
  pin rewrites the requirement in `Cargo.toml` and moves only tokio-modbus in
  the lock file (`cargo update --package tokio-modbus --precise <x.y.z>`).
  The other crates keep their locked versions unless the candidate requires
  something else. A release whose API differs fails to compile, which is the
  answer a candidate run is after.
- `/licenses/crates/<crate>-<version>/` holds the licence texts of every
  crate that goes into the binary (proc macros included),
  `/licenses/crates/CRATES.txt` lists them with their SPDX expressions,
  `/licenses/rust-std/` is the Rust standard library, which is linked in
  statically, and `/licenses/modbus-interop/LICENSE` is this repository's.
  `collect-licenses.sh` copies the texts from the sources cargo verified
  against `Cargo.lock`; it uses cargo only and fails the build if a crate
  ships no licence text.

## How the adapter uses tokio-modbus

### Server

`tokio_modbus::server::tcp::Server::serve_until` runs the accept loop, the
MBAP framing and the PDU codec. The adapter gives it:

- an `on_connected` callback, which receives the accepted stream and the peer
  address and returns one `Service` for the connection. That is the
  `connection-opened` event. The library drops the service when the
  connection ends, and `Drop` prints `connection-closed`.
- the `Service` itself. Its request type is `SlaveRequest`, the variant that
  carries the unit identifier, so the service can answer another unit
  identifier with exception 11. `call` receives every request the codec could
  decode, answers it from the fixture model behind a mutex and prints the
  `request` event, also when the answer is an exception.

Everything a device decides is in the service: unit identifier, quantity
limits (exception 3, checked before the address as the specification's state
diagrams do), address ranges (exception 2), FC22, FC23 (both ranges are
checked, then the write, then the read), FC43/14 and exception 1 for the
rest. The library supplies the Read Device Identification PDU types; cutting
the object list into responses that fit a PDU ("more follows") and individual
access (read code 4) are the service's work, so the conformity level is the
fixture's level plus 0x80.

### Client

`tokio_modbus::client::tcp::attach_slave` on a TCP stream the adapter
connects itself (name resolution and connect are bounded by
`--connect-timeout-ms`), with the unit identifier as `Slave`.

- Reads, FC23 and FC43/14 use the `Reader` methods.
- Writes use `Client::call` with the `Request` variants and compare the
  echoed address, value, quantity and masks themselves. The `Writer` methods
  return `()` and check the echo only with `debug_assert!`, which a release
  build does not contain.
- `raw` is `Request::Custom`.
- The library has no timeouts: every call is wrapped in
  `tokio::time::timeout` with `--timeout-ms`.
- The library's nested result maps to the contract like this: the inner
  `ExceptionCode` is `exception`; `ProtocolError::HeaderMismatch` (transaction
  or unit identifier) and `FunctionCodeMismatch` are `invalid-response`, as is
  a transport error of kind `InvalidData` (the codec could not decode the
  response); a closed connection is `connection-lost`.

The stream is wrapped in a pass-through that records what was received. It
exists for two things the library does not provide:

- `raw` has to hand back the response PDU as it arrived. For function codes
  the library decodes (1 to 6, 15, 16, 17, 22, 23, 43) it only hands out the
  decoded form, so the adapter takes the bytes of the frame the library
  accepted.
- When the peer closes the connection instead of answering, the library
  returns `io::Error::last_os_error()`, which is whatever the last failed
  system call left behind (observed: "Operation now in progress (os error
  115)"). The pass-through sees the end of the stream and the adapter reports
  `connection-lost`.

## What it cannot do

Every feature is declared `true`. The limits below are inside features that
pass the self-tests; they come from the codec, which the service cannot
influence, and the adapter does not paper over them.

- **Requests the codec rejects are not answered; the connection is closed.**
  The specification answers these with an exception, the library returns an
  error from the decoder and ends the connection (no `request` event, since
  nothing was answered):
  - FC05 with a value other than 0x0000 or 0xFF00 (specification: exception 3)
  - FC16 with more than 123 registers, or a byte count that is not twice the
    quantity; FC23 with more than 121 registers to write (specification:
    exception 3). Quantity 0 and the read quantities are decoded and reach
    the service, which answers exception 3: that is what `serverQuantityCheck`
    is tested with.
  - FC43 with a MEI type other than 14, or a read code outside 1..4
    (specification: exception 1 and exception 3)
  - a function code of 128 or above
- **FC15 with a quantity larger than its bytes hold makes the library
  panic** while decoding (see findings). The panic unwinds the task of that
  connection only, the connection closes and the server carries on. The
  release profile must therefore keep the default `panic = "unwind"`.
- FC15 with more data bytes than the quantity needs is accepted: the codec
  does not hand the byte count to the service.

## Findings in tokio-modbus 0.17.0

1. **Panic on a malformed FC15 request.** `decode_packed_coils(bytes, count)`
   indexes `bytes[i / 8]` for every `i < count` without checking `count`
   against the byte count. Request PDU `0F 0000 0009 01 FF` (9 coils, 1 data
   byte) gives `panicked at src/codec/mod.rs:616:19: index out of bounds: the
   len is 1 but the index is 1` in the server.
2. **A custom request with a function code the library names never
   succeeds.** `Request::Custom(n, ..)` has `FunctionCode::Custom(n)`, while
   the response to it is decoded as the named variant (or, for an exception,
   `FunctionCode::new(n)`), and the client compares the two as enum values.
   `Custom(3, [0,0,0,2])` answered correctly returns
   `ProtocolError::FunctionCodeMismatch`; so does an exception response to
   `Custom(7, [])`. The error carries the result, and the adapter accepts it
   when the function code numbers are equal.
3. **A closed connection is reported as `last_os_error()`** (above).
4. **Write echoes and read lengths are checked with `debug_assert!` only.**
   In a release build `write_single_register(0, 1)` succeeds when the device
   echoes another address, and `read_holding_registers(0, 2)` returns one
   register when the device sends one. The adapter checks both.
5. The decoder's message for a bad coil value is the literal `Invalid coil
   value: {}`: the placeholder is never filled in.
6. The requests listed under "What it cannot do" end the connection where
   the specification (V1.1b3, section 6 state diagrams) asks for an exception
   response.
7. The client drops whatever is in its read buffer before each request and
   requires the transaction and unit identifier of the response to equal the
   request's, so a late response to an earlier request is rejected rather
   than mistaken for the current one.

## Checked by hand

`clientUnitIdCheck` has no case in `tests/cases.tsv` (no reference server
answers with a wrong unit identifier). It was verified against a scripted
peer that answers with unit identifier + 1: the result is `invalid-response`.
The same peer was used for the other error codes: wrong transaction
identifier, wrong function, echo mismatch, short read, connection closed
before and in the middle of a response, differing responses under `--repeat`
and a device identification split over two responses.
