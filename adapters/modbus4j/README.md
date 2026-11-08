<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# modbus4j adapter

Wraps [modbus4j](https://github.com/MangoAutomation/modbus4j), the Modbus
library for Java of Serotonin Software / Infinite Automation (Mango), behind
the [container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | `com.infiniteautomation:modbus4j`, built from the source of the release tag |
| Version | 3.1.0 (tag `v3.1.0`, commit `3325a2cbce786989156ba7999d6adc2755f7ea5b`, released 2022-01-28; the latest release) |
| Source | `https://github.com/MangoAutomation/modbus4j/archive/refs/tags/v3.1.0.tar.gz`, SHA-256 `eb20d1fce2d25e3de8e1b67025ca06cf43babd9a249dad82c4a49ebf2fd93344` |
| Licence | GPL-3.0-or-later; **this adapter is GPL-3.0-or-later too** (it links the library) |
| Image | built locally, **not published** (`published: false`) |
| Engine | its own (`independentEngine: true`) |
| Roles | server and client |
| Transports | `tcp`, `udp`, `rtu-over-tcp`, both roles |
| Build | Maven 3.9.16 on Temurin 25; runs on the Temurin 25 JRE |

modbus4j is not on Maven Central; Mango publishes the jar on its own
repository (`maven.mangoautomation.net`). The image does not depend on that
repository: the Dockerfile downloads GitHub's archive of the tag, checks its
SHA-256 and that the commit recorded in the archive is the pinned one, and
compiles the unmodified sources with [upstream/pom.xml](upstream/pom.xml)
(the upstream `pom.xml` carries Mango's release and Nexus staging plugins;
this one has only the build, and the dependencies the upstream pom names).
The other jars come from Maven Central with strict checksums: commons-lang3
3.7 and commons-logging 1.1.1 (the versions of the upstream pom), Gson 2.14.0
with error_prone_annotations 2.48.0 (the JSON documents).

`/licenses` in the image has the GPL text of modbus4j (`modbus4j/license.txt`),
`modbus4j/SOURCE` (archive URL, SHA-256, commit) with the build pom used, the
adapter's own source and licence (`modbus-interop-modbus4j/`), and the licences
of the other jars; `/licenses/NOTICE.txt` maps each component to its licence.
The adapter's files carry `SPDX-License-Identifier: GPL-3.0-or-later`.

## How the library is used

**Server.** `new TcpSlave(port, false)` (tcp), `new TcpSlave(port, true)`
(rtu-over-tcp: the library's "encapsulated" RTU frames on TCP) or
`new UdpSlave(port, false)` (udp), with one process image added for the
fixture's unit identifier and `setMaxWriteRegisterCount(123)` (the library's
default is 120). This is how an application runs a modbus4j slave. The
library parses each request, validates it, finds the `ProcessImage` of its
unit identifier, calls it once per coil or register, and encodes the
response.

`Device` is that process image: an implementation of the library's
`ProcessImage` interface, its extension point for a slave's data. It holds the
fixture's four blocks and throws the library's `IllegalDataAddressException`
for an address outside a block, which the library answers with exception 2.
`getReportSlaveIdData()` returns the fixture's server ID and run indicator,
which the library sends after the byte count (FC17); `getExceptionStatus()`
returns 0 (FC07). Everything else (function codes, quantities, bit packing,
exception responses) is the library's.

The slaves bind inside `start()`, which blocks and does not say when it has
bound. The adapter first binds the port itself and lets go (a taken port is
exit 1 before the library starts), runs `start()` on a thread, and writes the
readiness file once `/proc/net/{tcp,tcp6}` (LISTEN) or `/proc/net/{udp,udp6}`
shows a socket on the port. `stop()` on SIGTERM.

**Client.** `new TcpMaster(params, keepAlive, true, true)` (tcp, and
rtu-over-tcp with `IpParameters.setEncapsulated(true)`) or
`new UdpMaster(params, true)` (udp), with `setTimeout(--timeout-ms)`,
`setRetries(0)` and `setMaxWriteRegisterCount(123)`. Requests are the
library's message classes (`ReadCoilsRequest`, ..., `WriteMaskRegisterRequest`,
`ReportSlaveIdRequest`), sent with `ModbusMaster.send`.

- `setRetries(0)` is one attempt: `MessageControl.send` loops
  `while (response == null && retries-- > 0)`, and `UdpMaster` makes
  `retries + 1` attempts. Checked on the wire.
- Without `--repeat` the master runs with `keepAlive=false`: it connects in
  `send`, sends, waits and closes, which is the contract's operation. With
  `--repeat` it runs with `keepAlive=true` (connects in `init()`, one
  connection for all requests) because the contract asks for one connection;
  see finding 12 for what that costs.
- `requests` is counted from the library's I/O log (`setIoLog`): every frame
  `TcpMaster` writes. For `UdpMaster`, which does not use the log, one per
  `send`.
- The library has one timeout for the TCP connect and for the wait for a
  response. `--timeout-ms` is that timeout; `--connect-timeout-ms` bounds the
  connect from outside (the adapter gives up when nothing was written by
  then). A connect therefore ends at the earlier of the two.
- Responses the library throws away are reported only to the master's
  `MessagingExceptionHandler` (`setExceptionHandler`); the request then waits
  until it times out. When the handler saw such a response during the wait,
  the adapter reports `invalid-response` instead of `timeout`.

Error mapping:

| What the library reports | `error.code` |
|---|---|
| `init()` fails, the connect inside `send` fails (nothing was written: `ConnectException`, `UnknownHostException`, connect timeout), or nothing was written by `--connect-timeout-ms` | `connect-failed` |
| a response with `isException()` | `exception` |
| `ModbusTransportException` caused by the library's `TimeoutException` (TCP) or `SocketTimeoutException` (UDP), when no response was discarded | `timeout` |
| the same, after the exception handler got a `WaitingRoomException` ("No recipient was found waiting for response": another transaction or unit identifier, or another function code) or a `ModbusTransportException` ("CRC mismatch") | `invalid-response` |
| `SlaveIdNotEqual` (UdpMaster's unit identifier check), `IllegalFunctionException` (a function code the response parser does not know), "Invalid response received" (UDP, too short) | `invalid-response` |
| a response of another message class, a data length that does not fit the request, an echo that differs from the request (checked by the adapter) | `invalid-response` |
| `ModbusTransportException` caused by another `IOException` after the request was written | `connection-lost` |
| responses that differ under `--repeat`; a request the library refuses before sending (`ModbusRequest.validate`, e.g. a range past 65535); unit identifier 0 (finding 17) | `failed` |

`clientUnitIdCheck` is declared: a response with another unit identifier is
discarded by the library on every transport (TCP and rtu-over-tcp: the waiting
room key has the unit identifier; UDP: `validateResponse`), and the adapter
reports it as `invalid-response` - on TCP and rtu-over-tcp only when the
timeout has run out. Verified against a scripted peer, together with the other
rows of the table above (wrong transaction identifier, wrong function code,
wrong CRC, a short data field).

## Transports

All three are the library's own: `TcpSlave`/`TcpMaster` (MBAP), the same with
`encapsulated` (RTU frames with CRC on TCP), `UdpSlave`/`UdpMaster` (MBAP in
datagrams). Exercised by hand in both roles, modbus4j against itself and
against the j2mod (udp, rtu-over-tcp), jsmodbus and modbusesp (rtu-over-tcp)
adapters: FC01, FC03, FC04, FC05, FC16, FC17, FC22 (client), exception 2,
`--repeat`, a write seen by a later read. On rtu-over-tcp the server answers
nothing for unit identifier 2, for broadcast 0 (it executes a broadcast write,
as a serial server does) and for a frame with a bad CRC; on udp nothing for
unit identifier 2 (as on tcp, finding 1).

## What it cannot do

| Feature | Why |
|---|---|
| `serverUnitIdFiltering` | A request for a unit identifier without a process image is not answered (`BaseRequestHandler`: `processImage == null` returns no response). The library cannot be made to send exception 11 from a process image. |
| `serverQuantityCheck` | A quantity the library refuses (`ModbusRequest.validate`: 0, above 2000 bits or 125/123 registers) throws, the exception goes to the exception handler, and the request is not answered. |
| `serverMaskWrite` | `ModbusRequest.createModbusRequest` has FC22 commented out: the slave answers exception 1. (`WriteMaskRegisterRequest.handleImpl` exists but is unreachable.) |
| `serverReadWriteMultiple`, `serverDeviceIdentification`, `serverDiagnostics`, `serverFileRecord`, `serverFifoQueue` and the client operations for FC23, FC43, FC08, FC20, FC21, FC24 | No message classes for FC08, FC20, FC21, FC23, FC24 or FC43. The server answers them with exception 1; the client refuses the operations (exit 2, nothing sent). |
| `clientRaw`, `clientRawAnyResponse` | There is no public way to send a PDU the library has no class for, and the response parser throws `IllegalFunctionException` for a function code it does not know. |
| `requestEvents`, `connectionEvents` | `TcpSlave` and `UdpSlave` show neither requests nor connections to the application: the process image sees single coils and registers, not requests, and the connection handler is private. |
| `--bind-address` other than `0.0.0.0` | `TcpSlave` and `UdpSlave` take a port only and listen on every address: exit 1. |
| `serverRejectedWriteUnchanged` | An FC15 or FC16 request that runs past the end of the block writes the part inside it (coils 1998-1999, registers 254-255) before the library answers exception 2. The adapter cannot prevent it: the library hands it the values one at a time. Spec 6.11/6.12, FIXTURES.md rule 2. |
| `serverIllegalFunction` | Over rtu-over-tcp the RTU slave does not answer a function code it does not implement (`raw --function 65` times out); over tcp and udp it answers exception 1. The feature is declared for every transport. |

## Findings about the library (3.1.0)

Observed on the wire against this adapter's server and client and against a
scripted peer. PDUs in hex; "Spec" is the Modbus Application Protocol
Specification V1.1b3.

| # | Request (PDU, unit identifier 1 unless said) | Answer | Spec |
|---|---|---|---|
| 1 | FC03, unit identifier 2: `0300000001` | none; the connection stays open | a matter of the application; FIXTURES.md asks for exception 11 |
| 2 | FC03 quantity 0 / 126: `0300000000` / `030000007e`; FC01 quantity 2001: `01000007d1` | none (`ModbusTransportException: Invalid number of registers: 0` to the exception handler) | exception 3 (6.1, 6.3) |
| 3 | FC15 quantity 1969: `0f000007b1f7` + 247 bytes | `0f000007b1`, written | exception 3: the maximum is 1968 (6.11); the library checks FC15 against the read maximum, 2000 |
| 4 | FC16 at 254, 3 registers: `1000fe000306000100020003` | `9002`, **and registers 254, 255 now read `0001 0002`**; FC15 at 1998, 3 coils `0f07ce00030105`: `8f02`, and coils 1998, 1999 are written | nothing is written when the range is not valid (6.11, 6.12 state diagrams) |
| 5 | FC05 value 0x1234: `0500031234` | `0500030000`: the coil is switched off, the echo differs from the request | exception 3 (6.5) |
| 6 | FC16 quantity 2 with byte count 6: `100028000206000700080009` | `1000280003`: three registers written; the quantity field is not read | exception 3 when quantity and byte count disagree (6.12) |
| 7 | FC22: `16000500f20025` | `9601` | the library has `WriteMaskRegisterRequest`, but its request parser has FC22 commented out |
| 8 | FC65 and FC03 in one TCP segment: `410102`, `0300000001` | `c101` only: parsing the unknown function clears the connection's whole receive buffer (`ExceptionRequest.readRequest`), the FC03 is lost | every request is answered |
| 9 | any unknown function on rtu-over-tcp, e.g. FC22 `01 16 0005 00f2 0025 <crc>` | none (`ShouldNeverHappenException: wha` while computing the CRC of the `ExceptionRequest`) | exception 1 |
| 10 | MBAP length 8 for FC03 `0300000001` + `0000` | `0103020000`, and the two extra bytes start the next frame, which then fails ("Unsupported IP protocol id") | the MBAP length frames the PDU (Messaging on TCP/IP Implementation Guide V1.0b, 3.1.3) |
| 11 | unit identifier 0, FC06 `0600640bad` | none, and the register is written | broadcast is a serial-line notion (Modbus over Serial Line V1.02, 2.2); on TCP 0 is an ordinary unit identifier |
| 12 | Client, `keepAlive=true`: a request that fails (timeout, I/O error) | `TcpMaster.sendImpl` reconnects and sends it once more, also with `setRetries(0)`; a timeout lasts twice | an implicit retry of a possibly state-changing request |
| 13 | Client, TCP and rtu-over-tcp: a response with another unit identifier, transaction identifier or function code, or a bad CRC | discarded (`WaitingRoomException`, `CRC mismatch`) and reported only to the exception handler; the request runs into its timeout | invalid response; the adapter reports it, but only after the timeout |
| 14 | Client, `new TcpMaster(params, keepAlive, autoIncrement, validateResponse=true)` | the argument is ignored (the field is never set); only `UdpMaster` checks | (the 3.0.6 release notes say the check exists) |
| 15 | Client, UDP: a response with another transaction identifier | accepted | the transaction identifier pairs request and response (Implementation Guide V1.0b, 3.1.3) |
| 16 | Client, TCP: the server closes the connection without answering | not noticed (`InputStreamListener` polls `available()`); the request times out | `connection-lost` in the contract |
| 17 | Client, unit identifier 0 | sent as a broadcast; `send` returns no response (TCP), or `NullPointerException` (UDP, with `validateResponse`) | as 11 |
| 18 | Client: the echo of FC05 | read as "0xFF00 or not": an echo 0x1234 of an "off" request is accepted | the echo is the request (6.5) |

Finding 4 makes two read-only cases of tests/cases.tsv (`write-multiple-coils
--address 1998 --values 1,0,1` and `write-multiple-registers --address 254
--values 1,2,3`) change the shared modbus4j server, so later reads of coils
1998..1999 and registers 254..255 on it differ from the fixture.

## Choices made where the contract leaves room

- After a failure under `--repeat`, `response` holds the last normal response
  if there was one.
- A flag given twice: the last one counts.
- The fixture's `name` must equal the name of its directory; otherwise exit 4.
- A fixture without `reportServerId`: FC17 is answered with exception 4 (the
  only refusal the library lets `getReportSlaveIdData` make).
- FC07 (Read Exception Status), which the library serves, answers 0x00; it is
  in `serverFunctions`.

## Build

```sh
make image-modbus4j smoke-modbus4j
scripts/interop.sh modbus4j
```

`UPSTREAM_VERSION` selects the tag, `UPSTREAM_SOURCE_SHA256` and
`UPSTREAM_SHA` pin its archive and commit; the commit ends up in the adapter
jar's manifest, which is where `print-capabilities` reads it. A candidate
(`make candidate-modbus4j CANDIDATE_VERSION=...`) without its checksum is
built unverified and says so. Nothing in the image is architecture specific.
