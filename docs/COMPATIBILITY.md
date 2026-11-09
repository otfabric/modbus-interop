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
| `j2mod` | [j2mod](https://github.com/steveohara/j2mod) | 3.4.0 (`d7861c90`) | 2026-09-06 | Apache-2.0 | Java |
| `jsmodbus` | [jsmodbus](https://github.com/Cloud-Automation/node-modbus) | 5.0.0 (npm, git `fdf02b26`, untagged) | 2026-09-14 | MIT | JavaScript |
| `modbusesp` | [modbus-esp8266](https://github.com/emelianov/modbus-esp8266) | 4.1.1 (`29966630`) | 2023-01-03 | BSD-3-Clause | C++ |
| `modbus4j` | [modbus4j](https://github.com/MangoAutomation/modbus4j) | 3.1.0 (`3325a2cb`) | 2022-01-28 | GPL-3.0-or-later | Java |

Every pin is the latest stable upstream release. The **Upstream** workflow
checks that every day (`scripts/latest-upstream.py`, and the base-image
digests), tests a newer release as a candidate and opens an issue when a pin
is behind.

`modbus4j` is built from the pinned source archive on the machine that runs
it and never published (GPL-3.0-or-later, REQUIREMENTS R8.3). `modbusesp` is
an Arduino library: the adapter compiles its Modbus sources unmodified for
Linux against a small Arduino-core layer with no Modbus logic
(`adapters/modbusesp/README.md`), with four of its documented compile-time
settings changed (frame size 253, 16 clients, no one-connection-per-address
rule, timeouts from `--timeout-ms`).

Two adapters need at least their pinned release, because they use an API
that is new in it: `digitalpetri` (raw request hooks, 2.1.6) and
`tokiomodbus` (Read Device Identification, 0.16.4; 0.16.2 to 0.16.5 are
yanked). `nmodbus` needs 3.0.83 for its device-identification client types;
NModbus 4.0 is a pre-release of a different API. `libmodbus` and `pymodbus`
also build and pass on the release before (v3.1.12 and 3.15.0).

## Capabilities

| Feature | libmodbus | pymodbus | digitalpetri | nmodbus | tokiomodbus | j2mod | jsmodbus | modbusesp | modbus4j |
|---------|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|:-:|
| Server functions | 1-6, 15, 16, 22, 23 | 1-6, 8, 15-17, 20-24, 43 | 1-6, 15, 16, 22, 23 | 1-6, 8, 15, 16, 23 | 1-6, 15, 16, 22, 23, 43 | 1-6, 15, 16, 20-24 | 1-6, 15, 16, 43 | 1-6, 15, 16, 20-23 | 1-7, 15-17 |
| Transports (server) | tcp | tcp | tcp | tcp | tcp | tcp, udp, rtu-over-tcp | tcp, rtu-over-tcp | tcp, rtu-over-tcp | tcp, udp, rtu-over-tcp |
| Transports (client) | tcp | tcp | tcp | tcp | tcp | tcp, udp, rtu-over-tcp | tcp, rtu-over-tcp | tcp, rtu-over-tcp | tcp, udp, rtu-over-tcp |
| `serverUnitIdFiltering` | yes ¹ | yes ¹ | yes ¹ | yes ¹ | yes | yes ¹ | yes ¹ | yes ¹ | **no** |
| `serverQuantityCheck` | yes | **no** | yes ¹ | yes ¹ | yes | yes ¹ | **no** | yes ¹ | **no** |
| `serverMaskWrite` | yes | yes | yes | **no** | yes | yes | **no** | yes | **no** |
| `serverReadWriteMultiple` | yes | yes | yes | yes | yes | yes | **no** | yes | **no** |
| `serverDeviceIdentification` | **no** | yes | **no** | **no** | yes | **no** | yes ¹ | **no** | **no** |
| `requestEvents` | yes | yes | yes | yes | yes | yes | yes | **no** | **no** |
| `connectionEvents` | yes | yes | yes | **no** | yes | yes | yes | **no** | **no** |
| `clientMaskWrite` | yes | yes | yes | **no** | yes | yes | **no** | yes | yes |
| `clientReadWriteMultiple` | yes | yes | yes | yes | yes | yes | **no** | yes | **no** |
| `clientDeviceIdentification` | **no** | yes | **no** | yes | yes | yes | yes | **no** | **no** |
| `clientRaw` | yes | yes | yes | yes | yes | **no** | **no** | yes | **no** |
| `clientRepeat` | yes | yes | yes | yes | yes | yes | yes | yes | yes |
| `clientUnitIdCheck` | **no** | yes | **no** | yes | yes | **no** | **no** | **no** | yes |
| `serverDiagnostics` | **no** | yes | **no** | yes ² | **no** | **no** | **no** | **no** | **no** |
| `serverReportServerId` | **no** ³ | yes | **no** | **no** | **no** ⁴ | **no** | **no** | **no** | yes |
| `serverFileRecord` | **no** | yes | **no** | **no** | **no** | **no** | **no** | **no** | **no** |
| `serverFifoQueue` | **no** | yes | **no** | **no** | **no** | yes | **no** | **no** | **no** |
| `clientDiagnostics` | **no** | yes | **no** | **no** | **no** | yes | **no** | **no** | **no** |
| `clientReportServerId` | yes | yes | **no** | **no** | yes | yes | **no** | **no** | yes |
| `clientReadFileRecord` | **no** | yes | **no** | **no** | **no** | yes | **no** | **no** | **no** |
| `clientWriteFileRecord` | **no** | yes | **no** | yes | **no** | yes | **no** | yes | **no** |
| `clientFifoQueue` | **no** | yes | **no** | **no** | **no** | yes | **no** | **no** | **no** |
| `clientRawAnyResponse` | **no** | yes | yes | yes | yes | **no** | **no** | yes | **no** |
| `serverIllegalFunction` | yes | yes | yes | yes | yes | **no** | **no** | yes | **no** |
| `serverRejectedWriteUnchanged` | yes | yes | yes | yes | yes | yes | yes | yes ¹ | **no** |
| `clientLargestRegisterWrites` | yes | yes | yes | yes | yes | yes | **no** | yes | yes |
| `serverHighestAddress` | yes | yes | yes | yes | yes | yes | yes | **no** | yes |
| `serverLargestRegisterWrites` | yes | yes | yes | yes | yes | yes | **no** | yes | yes |

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
every server, 81 pairings, and all pairings must produce the same result
document for a case. Pairings that both declare `udp` or `rtu-over-tcp` run
[tests/transport-cases.tsv](../tests/transport-cases.tsv) and the write checks
over that transport as well: j2mod and modbus4j over `udp`, j2mod, jsmodbus,
modbus-esp8266 and modbus4j over `rtu-over-tcp`.

With the pins above, on linux/arm64 (2026-11-08): **9453 pass, 0 fail, 5007
unsupported, 0 known differences**. There is no disagreement between any two
stacks. A pairing skips the cases whose features it lacks; the largest groups:

| Skipped for | Cases |
|-------------|-------|
| any client against the `pymodbus`, `jsmodbus` and `modbus4j` servers | the `serverQuantityCheck` cases |
| `nmodbus`, `jsmodbus` as client or server; the `modbus4j` server | FC22 |
| `jsmodbus`, `modbus4j` as client or server | FC23 |
| `libmodbus`, `digitalpetri`, `j2mod`, `modbusesp`, `modbus4j` servers; the `libmodbus`, `digitalpetri`, `modbusesp`, `modbus4j` clients; the `nmodbus` server | FC43 |
| `j2mod`, `jsmodbus`, `modbus4j` clients | `raw` |
| the `j2mod`, `jsmodbus`, `modbus4j` servers | the unknown function code (`serverIllegalFunction`) |
| the `modbus4j` server | a refused FC15/FC16 writes nothing (`serverRejectedWriteUnchanged`); another unit identifier |
| the `modbusesp` server | reads that end at address 65535 (`serverHighestAddress`) |
| the `jsmodbus` client or server | FC16 with 123 registers, FC23 with 121 written (`clientLargestRegisterWrites`, `serverLargestRegisterWrites`) |
| the five stacks of contract 1.0 | everything over `udp` and `rtu-over-tcp`: they declare `tcp` only |

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

### j2mod 3.4.0

The adapter answers requests through its own accept loop on the library's
public `TCPSlaveConnection` and `ModbusUDPTransport`, with a gate in front for
the [FIXTURES.md](FIXTURES.md) rules the library lacks; the library builds
every response. Details and hex in `adapters/j2mod/README.md`.

- **Unknown function code over TCP closes the connection** (spec 7):
  `00010000000401410102` gets no answer instead of `c101`.
  `IllegalFunctionRequest.readData` skips the MBAP length instead of the data
  length and reads past the frame. UDP answers `c101`; on rtu-over-tcp the
  stream goes out of step. `serverIllegalFunction` false.
- The library's own `ModbusSlave` answers another unit with `8302`, also on
  RTU (Serial Line V1.02, 2.2: no answer); checks no quantities (`0300000000`
  is answered `0300`, spec 6.3); takes FC05 value 0x1234 as OFF (spec 6.5:
  exception 3). The adapter's gate corrects all three.
- On rtu-over-tcp neither role checks the CRC (Serial Line V1.02, 2.5.1.2).
- FC16 with byte count 3 for 2 registers, and FC21 without a sub-request,
  close the connection (spec 6.12: exception 3; the FC21 case is a
  `NullPointerException`).
- FC20 with two sub-requests (spec 6.14): `140e0600010000000206000200000002`
  is answered `140c0506000b0506000100020000`, expected
  `140c0506000b01a4050600010002` (`ReadFileRecordResponse.getMessage`
  advances the offset wrongly). Found by the go-modbus interop suite after
  v0.4.0, which declared `serverFileRecord` true; it is false since v0.4.1,
  and the self-tests now send two sub-requests
  (`values.fc20.two-sub-requests`).
- Over UDP, FC24 and FC20 responses are framed by a data length that is never
  updated: FC24 `1804de` is answered one byte short, the empty queue carries
  3 stale bytes, FC20 is answered with MBAP length 0 and no PDU.
- FC23 writes before it checks the read range, and FC21 stops part-way
  through its sub-requests; the adapter checks both before the library runs.
- Client: `WriteCoilResponse` sign-extends the echoed address (32768 reads as
  -32768); the adapter reports `invalid-response`. A response with another
  unit identifier is accepted over TCP (`clientUnitIdCheck` false).

### jsmodbus 5.0.0

The server is created without its tables, the library's documented hook, so
every decoded request reaches an application event with a send callback; the
adapter answers it with the library's own response and exception classes.
Details in `adapters/jsmodbus/README.md`.

- **A request the decoder cannot read is never answered, and nothing after
  it on the connection is decoded**: FC65 `410102`, FC07, FC08, FC22 (spec 7:
  exception 1), FC01 with 2001 coils (6.1: exception 3), FC15 with too few
  data bytes (6.11). `serverIllegalFunction` and `serverQuantityCheck` false.
- **FC16 with 121 to 123 registers is broken in both roles** (spec 6.12): the
  request body caps the byte count at 0xF6. The client sends quantity 123 and
  byte count 246 with 240 data bytes; the RTU server frames requests by the
  capped count, misses the CRC and stalls. 120 registers work.
  `clientLargestRegisterWrites` and `serverLargestRegisterWrites` false.
- As shipped: no unit-id filtering, no address or quantity checks (FC03 at
  5000 answers `0300`; quantity 126 answers a 254-byte PDU, above the 253 of
  spec 4.1), FC05 value 0x1234 turns the coil off (6.5), FC16 takes byte count
  / 2 and ignores the quantity (6.12).
- FC43 exceptions carry the MEI type byte (`ab0e01`); spec 6.21 defines the
  exception as `ab` and the code. The adapter's own answers use two bytes.
- Requests that arrive in one TCP segment are served last in, first out: FC06
  followed by FC03 returns the value from before the write.
- Client: the RTU client cannot decode an FC43 exception and times out; an
  exception code outside 1-6, 8, 10, 11 throws inside its data handler; a
  closed RTU connection is noticed only at the timeout; a response for
  another unit is dropped silently (`clientUnitIdCheck` false). The client
  checks no counts or write echoes; the adapter does.

### modbus-esp8266 4.1.1

Line numbers are of the tag. Details in `adapters/modbusesp/README.md`.

- **Address 65535 is unusable** (spec 4.4): `addReg` caps the count with
  `0xFFFF - address < numregs` (Modbus.cpp:65), so no register is created at
  65535, and the read and write checks have the same off-by-one.
  `04 ffff 0001` and `04 ff83 007d` are answered `84 02`.
  `serverHighestAddress` false.
- FC20 server: the byte count is two too large (`14 07 06 0001 0000 0002` is
  answered `14 08 05 06 000b 01a4`; spec 6.14 gives 06). The response size is
  a `uint8_t`, so a response over 255 bytes overflows its buffer; the adapter
  refuses such requests with exception 2 before the library copies anything.
  `serverFileRecord` false.
- FC20 client: refuses every correctly framed response, accepts only its own
  server's inflated count, and requires sub-response lengths the spec's own
  example does not have. `clientReadFileRecord` false.
- Reads check only the first address, and a bad quantity is exception 2, not
  3 (spec 6.1-6.4); the documented `MODBUS_STRICT_REG` setting does not
  compile. FC23 checks the read range twice and the write range never, and
  writes before it checks. Write quantity limits are above the spec (FC15
  2000, FC16 125, FC23 125 written). The adapter's checks cover all of these.
- A short PDU is read past its end; `ModbusRTUTemplate::_slaveId` is not
  initialised.
- Client: an RTU response with a bad CRC is dropped silently, so the client
  times out; neither write echoes nor the unit identifier are checked.

### modbus4j 3.1.0

Built unmodified from the source archive. Details in
`adapters/modbus4j/README.md`.

- **A refused FC15 or FC16 has already written the part inside the block**
  (spec 6.11/6.12 state diagrams; FIXTURES.md rule 2): FC15 at 1998 with
  three coils sets coils 1998-1999, FC16 at 254 with three registers sets
  254-255, then exception 2 is answered. The process image hands the adapter
  one value at a time, so it cannot refuse the request as a whole.
  `serverRejectedWriteUnchanged` false.
- Over rtu-over-tcp an unknown function code is not answered (the library
  throws while computing the CRC); over tcp and udp it is exception 1.
  `serverIllegalFunction` false.
- No answer at all for a request for another unit (FIXTURES rule 1) or with a
  bad quantity (`0300000000`, `030000007e`; spec 6.1, 6.3).
  `serverUnitIdFiltering` and `serverQuantityCheck` false.
- FC15 with 1969 coils is served and written (6.11: at most 1968); FC05 with
  value 0x1234 switches the coil off (6.5); FC16 ignores the quantity field
  and writes as many registers as the byte count holds (6.12).
- The server answers FC22 with exception 1: the request parser has FC22
  commented out, although the client sends it.
- A request that follows an unknown function code in the same segment is
  lost; the MBAP length is ignored, so extra bytes start the next frame.
  Unit 0 over TCP is not answered, but its write is executed.
- Client: with keep-alive the master resends a failed request on a new
  connection even with no retries (the adapter keeps connections only for
  `--repeat`); a response with a wrong unit, transaction, function or CRC is
  dropped and the request times out; the UDP master accepts a response with
  another transaction identifier; a closed connection is reported as a
  timeout. One timeout covers both connect and response.

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
