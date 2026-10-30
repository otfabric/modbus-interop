# Compatibility

What is pinned, what each stack can do, how the stacks get on with each
other, and what was learned about them while writing the adapters. The
machine-readable form of the pins is [versions.yaml](../versions.yaml); the
capabilities come from `print-capabilities` of each image.

## Pinned upstreams

| Adapter | Upstream | Version | Released | Licence | Language |
|---------|----------|---------|----------|---------|----------|
| `libmodbus` | [libmodbus](https://github.com/stephane/libmodbus) | v3.2.0 (`a9b025d1`) | 2026-07-02 | LGPL-2.1-or-later | C |
| `pymodbus` | [PyModbus](https://github.com/pymodbus-dev/pymodbus) | 3.16.1 | 2026-10-11 | BSD-3-Clause | Python |
| `digitalpetri` | [digitalpetri/modbus](https://github.com/digitalpetri/modbus) | 2.1.6 | 2026-08-08 | EPL-2.0 | Java |
| `nmodbus` | [NModbus](https://github.com/NModbus/NModbus) | 3.0.83 | 2026-04-16 | MIT | C# |
| `tokiomodbus` | [tokio-modbus](https://github.com/slowtec/tokio-modbus) | 0.17.0 | 2025-10-22 | MIT OR Apache-2.0 | Rust |

Every pin is the latest stable upstream release. The **Upstream** workflow
checks that every day (`scripts/latest-upstream.py`, and the base-image
digests), tests a newer release as a candidate and opens an issue when a pin
is behind.

Two adapters need at least their pinned release, because they use an API
that is new in it: `digitalpetri` (raw request hooks, 2.1.6) and
`tokiomodbus` (Read Device Identification, 0.16.4; 0.16.2 to 0.16.5 are
yanked). `nmodbus` needs 3.0.83 for its device-identification client types;
NModbus 4.0 is a pre-release of a different API. `libmodbus` and `pymodbus`
also build and pass on the release before (v3.1.12 and 3.15.0).

## Capabilities

| Feature | libmodbus | pymodbus | digitalpetri | nmodbus | tokiomodbus |
|---------|:---------:|:--------:|:------------:|:-------:|:-----------:|
| Server functions | 1-6, 15, 16, 22, 23 | 1-6, 15, 16, 22, 23, 43 | 1-6, 15, 16, 22, 23 | 1-6, 15, 16, 23 | 1-6, 15, 16, 22, 23, 43 |
| `serverUnitIdFiltering` | yes ¹ | yes ¹ | yes ¹ | yes ¹ | yes |
| `serverQuantityCheck` | yes | **no** | yes ¹ | yes ¹ | yes |
| `serverMaskWrite` (FC22) | yes | yes | yes | **no** | yes |
| `serverReadWriteMultiple` (FC23) | yes | yes | yes | yes | yes |
| `serverDeviceIdentification` (FC43/14) | **no** | yes | **no** | **no** | yes |
| `requestEvents` | yes | yes | yes | yes | yes |
| `connectionEvents` | yes | yes | yes | **no** | yes |
| `clientMaskWrite` | yes | yes | yes | **no** | yes |
| `clientReadWriteMultiple` | yes | yes | yes | yes | yes |
| `clientDeviceIdentification` | **no** | yes | **no** | yes | yes |
| `clientRaw` | yes | yes | yes | yes | yes |
| `clientRepeat` | yes | yes | yes | yes | yes |
| `clientUnitIdCheck` | **no** | yes | **no** | yes | yes |
| `serverDiagnostics` (FC08, Return Query Data) | **no** | yes | **no** | yes ² | **no** |
| `serverReportServerId` (FC17) | **no** ³ | yes | **no** | **no** | **no** ⁴ |
| `serverFileRecord` (FC20, FC21) | **no** | yes | **no** | **no** | **no** |
| `serverFifoQueue` (FC24) | **no** | yes | **no** | **no** | **no** |
| `clientDiagnostics` | **no** | yes | **no** | **no** | **no** |
| `clientReportServerId` | yes | yes | **no** | **no** | yes |
| `clientReadFileRecord` | **no** | yes | **no** | **no** | **no** |
| `clientWriteFileRecord` | **no** | yes | **no** | yes | **no** |
| `clientFifoQueue` | **no** | yes | **no** | **no** | **no** |
| `clientRawAnyResponse` | **no** | yes | yes | yes | yes |

¹ The behaviour comes from adapter code placed in front of the library
through its public extension points, not from the library as shipped. What
the library does by itself is under [Findings](#findings).
² The library's own FC08 service, behind an adapter check: it echoes exactly
one data word, so other lengths are answered with exception 3.
³ libmodbus answers FC17 with its own fixed identity, which cannot follow a
fixture; the adapter answers exception 1.
⁴ The tokio-modbus 0.17.0 server frames its FC17 response with an MBAP
length one byte short (see [Findings](#findings)); the adapter answers
exception 1.

FC08, FC17, FC20, FC21 and FC24 differ most between the stacks. No stack
but PyModbus has typed support for all of them; digitalpetri/modbus 2.1.6
has none. tokio-modbus has typed FC20, FC21 and FC24 on its main branch, not
yet released. Where a library offers only raw bytes for a function, the
adapter does not encode it: `raw` sends it instead.

A reference server is useful to a consumer only if it behaves as
[FIXTURES.md](FIXTURES.md) says, so the adapters make their devices do that
where the library offers a hook. They never re-implement a function the
library lacks: such a function is declared unsupported (FC22 in NModbus, FC43
in libmodbus, digitalpetri and the NModbus server).

## Cross-stack matrix

Every client runs every case of [tests/cases.tsv](../tests/cases.tsv) against
every server, 25 pairings, and all pairings must produce the same result
document for a case. With the pins above there is no failure and no
disagreement between any two stacks; a pairing skips the cases whose features
it lacks:

| Skipped for | Cases |
|-------------|-------|
| any client against the `pymodbus` server | the `serverQuantityCheck` cases |
| `nmodbus` as client or server | FC22 |
| `libmodbus` and `digitalpetri` as client or server, `nmodbus` as server | FC43 |

`clientUnitIdCheck`, and the error codes `invalid-response` and
`connection-lost`, are not exercised by the self-tests: they need a
misbehaving peer, which no reference server is. The adapter authors verified
them by hand against scripted peers.

## Findings

Behaviour of the libraries as shipped, observed on the wire while writing the
adapters unless marked otherwise. "Spec" is the Modbus Application Protocol
Specification V1.1b3. These are the things a library under test meets when it
talks to a real deployment of one of these stacks rather than to the adapter.

### Servers that close the connection or stay silent where the spec asks for an exception

| Situation | libmodbus | PyModbus | digitalpetri | NModbus | tokio-modbus |
|-----------|-----------|----------|--------------|---------|--------------|
| Unknown function code (spec: exception 1) | exception 1, after sleeping for the response timeout and flushing the socket | answers function `0x80`, exception 1 | closes | closes | passed to the service |
| Request for another unit id | served like its own | exception 4 | silence | silence | passed to the service |
| FC03 quantity 0 or 126 (spec: exception 3) | exception 3, after the same sleep | function `0x80`, exception 1 | exception 3 | 0 and 126..127 are served, more closes | exception 3 from the service |
| FC05 value other than `0000`/`FF00` (spec: exception 3) | exception 3 | accepted as ON | accepted as ON | accepted as OFF | closes |
| FC16 quantity 124 (spec: exception 3) | exception 3 | exception 3 | served | served up to 127 | closes |
| FC15/FC16 byte count that does not match the quantity | too small: exception 3; FC15 too large: served | not probed | too small: closes | not checked | FC15 too small: **the connection task panics**; FC16: closes |
| FC23 with a valid write and an invalid read range | not probed | exception 2, but the write has happened | not probed | exception 2, but the write has happened | from the service |
| Address outside the device | from the mapping | bit blocks are padded to 16 and the padding is readable | none: a sparse 0..65535 image | from the data store | from the service |

The table is what the libraries do **as shipped**, not what the images do.
The adapters correct the first three rows (except the PyModbus quantity
answer, which is why it declares `serverQuantityCheck` false), the FC23 row
and the last row. The `digitalpetri` and `nmodbus` adapters also answer the
invalid writes of the middle rows with exception 3, because their check sits
in front of every request; the other three images answer those as their
library does. Invalid writes are therefore not in the self-tests, and a
consumer should not rely on what an image does with one.

### PyModbus answers with function code 0x80

For a function code it does not know, and for a read whose quantity is 0 or
above the maximum, PyModbus 3.16.1 answers `80 01`: function code `0x80`
instead of the request's function code plus `0x80`, and exception 1 also
where the spec asks for 3. A client that validates the function code of a
response reports this as an invalid response (libmodbus and NModbus do); the
PyModbus client itself closes the connection on it. The adapter registers a
handler for every unknown function code, so those are answered correctly;
the quantity answers are left as they are.

### tokio-modbus server panics on a short FC15 request

Request PDU `0F 0000 0009 01 FF` (nine coils, one data byte) makes the
decoder index past the buffer (`codec/mod.rs:616`). Only the task of that
connection dies, and only when the program is built with `panic = "unwind"`,
as the adapter is; with `panic = "abort"` the server process ends.

### libmodbus

- `modbus_reply` sleeps for the response timeout (500 ms by default) and
  flushes the socket before it sends exception 1 or 3. In a single-threaded
  server every connection waits, and a request pipelined behind the rejected
  one is discarded. The adapter sets that timeout to 20 ms.
- The MBAP protocol identifier and length are ignored; requests and responses
  are framed by function code. A client therefore cannot read a response to a
  function it does not know (FC43), and `raw` reports that as `failed`.
- The TCP client checks the transaction identifier of a response, not the
  unit identifier.
- FC17 (Report Server ID) is answered by the library; the adapter turns it
  into exception 1.
- FC15 with more data bytes than the quantity needs is served (tokio-modbus
  does the same).
- Exception codes of 12 and above are reported to the caller as a bad
  exception code, not as the exception.

### PyModbus

- Two requests in one TCP segment: only the first is answered.
- The client does not check the function code, the number of values or the
  echo of a write. Frames with the wrong unit or transaction identifier are
  dropped silently, so the caller sees a timeout.
- The client sleeps 100 ms after every successful connect.
- FC43/14 always reports conformity level `0x83` as shipped (the adapter
  reports the level of the fixture), and read code 0 gets exception 4.
- FC43/14 stream access starting at an object the device does not have is
  answered with no objects; the spec restarts at object 0.

### digitalpetri/modbus

- The library has no FC43, although its `FunctionCode` enum names it.
- The client matches responses by transaction identifier only; unit and
  protocol identifier, write echoes and byte counts are not checked. A
  pending request is not failed when the connection closes: it runs into its
  timeout.
- FC23 with read quantity 126 is answered with a 254-byte PDU, one more than
  the protocol allows.
- A request with a non-zero protocol identifier is served.

### NModbus

- The server reads the MBAP header and the rest of a frame with one read
  each: a request that arrives in two TCP segments is misread or closes the
  connection. No reference client splits a request, so the self-tests do not
  meet this.
- A request that is only a function code (FC07, FC11, FC12, FC17) closes the
  connection; this cannot be corrected from outside the library.
- The client raises an exception response before it validates the frame, so
  one with the wrong unit or function code is accepted; exception 5
  (acknowledge) is swallowed and ends in a timeout; exception 6 (busy) is
  retried without limit by default.

### tokio-modbus

- The client checks write echoes and register counts with `debug_assert!`
  only: a release build accepts a wrong echo or a short read. The adapter
  checks them itself.
- `Request::Custom(n, ..)` for a function code the library knows fails with
  a function code mismatch although the response is correct.
- A connection closed by the peer surfaces as an unrelated OS error.
- The library has no timeouts; every bound is the caller's.
- Over-limit writes (FC16 above 123 registers, FC23 above 121 written) close
  the connection before the service sees them, so `serverQuantityCheck` is
  true for the reads and for quantity 0, not for those.

### Diagnostics, Report Server ID, file records and FIFO queues

What the libraries do with FC08, FC17, FC20, FC21 and FC24, as shipped:

| Situation | libmodbus | PyModbus | digitalpetri | NModbus | tokio-modbus |
|-----------|-----------|----------|--------------|---------|--------------|
| Client API | FC17 only | all five | none | FC21 only (one sub-request) | FC17 only |
| Server, as shipped | FC17 with a fixed identity (`B4 FF "LMB3.2.0"`); others exception 1 | FC08 echoes and counters that never count; FC17 from the identity objects; FC20 a dummy record; FC21 echoes and stores nothing; FC24 always `0, 1, 2, 3` | closes the connection | FC08 echoes one data word, whatever the sub-function; FC17 closes; FC21 exception 4 | FC17 with a broken frame; others to the service |
| `raw` receives a normal response of FC08, FC20, FC21, FC24 | no: frames by function code | yes | yes | yes | yes |

Details:

- **PyModbus server, FC20:** the request decoder takes the record length in
  registers for a length in bytes and halves it, so a request for an odd
  number of registers is answered `80 01` and never reaches the device. The
  self-tests read even lengths only; a consumer should do the same against
  this server.
- **PyModbus server, FC08:** an unknown sub-function is answered with
  exception 4 as shipped; the adapter answers exception 1.
- **PyModbus server, FC20/FC21:** the request decoders drop the reference
  type, so a sub-request with a type other than 6 is served as type 6 (and an
  FC21 echo carries 06); the spec asks for exception 2. Not in the
  self-tests; a consumer should not rely on it.
- **PyModbus server, FC17 as shipped** answers the identity objects joined by
  `-`, or `Pymodbus`, with the run indicator on; the adapter answers the
  fixture.
- **PyModbus client, FC17:** the last byte inside the byte count is taken as
  the run indicator, so additional data ends up in the server ID, and a run
  indicator other than 00/FF is lost.
- **PyModbus client, FC08:** for sub-functions other than 0 the data is
  decoded as words, an odd length padded with ASCII `0`; the adapter cuts it
  back to the frame.
- **PyModbus client, FC20/FC24:** `FileRecord.record_length` is in bytes on
  the request side; the FC24 decoder ignores the byte count and the limit of
  31 values. The adapter checks both.
- **PyModbus client, FC17:** 3.15.0 put the run indicator into the server
  ID; fixed in 3.16.1.
- **NModbus client, FC21:** only the file and record number of the echo are
  compared: an echo with another reference type or other data is accepted.
- **NModbus server, FC08:** the library's service keeps exactly one data
  word: a longer request gets a truncated echo, a shorter one closes the
  connection. The adapter answers other lengths with exception 3 and other
  sub-functions with exception 1.
- **tokio-modbus server, FC17:** `response_pdu_size` counts
  `ReportServerId(_, _, data)` as `3 + data.len()`, but the PDU is
  `4 + data.len()`, so the MBAP length is one short:
  `0001 0000 0004 01 11 02 2a ff`. A request with any data byte after the
  function code closes the connection.
- **tokio-modbus:** FC20, FC21 and FC24 are typed on the main branch
  (unreleased); FC08 is not.

## Platforms

Images are published for linux/amd64 and linux/arm64, each built and
self-tested natively by the release workflow. Development and the results
above are linux/arm64.
