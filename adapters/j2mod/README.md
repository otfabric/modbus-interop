# j2mod adapter

Wraps [j2mod](https://github.com/steveohara/j2mod), a Modbus master and slave
library for Java (TCP, UDP, RTU over TCP, serial), behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | `com.ghgande:j2mod` from Maven Central |
| Version | 3.4.0 (tag `v3.4.0`, commit `d7861c90f40a7ac219a2dc6987ffa54caa5d47a7`, released 2026-09-06) |
| Licence | Apache-2.0 |
| Engine | its own (`independentEngine: true`) |
| Roles | server and client |
| Transports | server and client: `tcp`, `udp`, `rtu-over-tcp` |
| Build | Maven 3.9.16 on Temurin 25; runs on the Temurin 25 JRE |

Other jars in the image: jSerialComm 2.11.4 and slf4j-api 2.0.9 (at the
versions the upstream POM names; the adapter does not override them),
slf4j-simple 2.0.18 (the library's logging, to stderr) and Gson 2.14.0 with
its error_prone_annotations 2.48.0 (the JSON documents). The upstream POM
also declares the `slf4j-reload4j` binding as a compile dependency; the
adapter excludes it, as an application choosing its own logging backend
would. `/licenses/NOTICE.txt` in the image maps each jar to its licence text.

## How the library is used

**Server.** Not through `ModbusSlave`/`ModbusSlaveFactory`: their listeners
answer a request for a unit identifier without a process image with
exception 2, check no quantity, and report neither requests nor connections
(see the findings). The adapter runs the loop of the library's own connection
handler (`TCPConnectionHandler.run`, `ModbusUDPListener.run`) itself, with the
library's public pieces:

1. `transport.readRequest(listener)` decodes a request into the library's
   request class;
2. `Device` applies the fixture rules the library does not, and otherwise
   calls `request.createResponse(listener)`: the library's request class
   reads or changes the process image and builds the response;
3. `transport.writeResponse(response)`.

`Device` is the `AbstractModbusListener` handed to `createResponse`, the
library's extension point that names the process image of a request
(`getProcessImage(unitId)`). The device is the library's own model: the
fixture is loaded into a sparse `SimpleProcessImage` (`addDigitalOut(ref,
…)`, `addRegister(ref, …)`, …), so reading, writing, the bit packing, FC22's
mask arithmetic, FC23's write-before-read, file records and the FIFO are the
library's code. An address with no element is answered by the library with
exception 2. In front of `createResponse`, `Device` answers with the library's
`request.createExceptionResponse(code)`:

- another unit identifier, 0 and 255 included: exception 11 (`tcp`, `udp`); on
  `rtu-over-tcp` no answer at all (contract, Transports);
- quantity 0 or above the maximum (FC01-FC04, FC15, FC16, FC23 read and
  write), an FC15 byte count that does not match the quantity: exception 3;
- FC23: both ranges outside the holding registers before anything is written
  (the library writes first and finds the read range only then): exception 2;
- FC21: every sub-request against the fixture's files before anything is
  written (the library writes register by register and stops at the first
  missing one): exception 2.

Two layouts bridge the library's process-image model and the fixture's, both
through the public `SimpleProcessImage`, `File`, `Record` and `FIFO`-less API:

- **Files (FC20, FC21).** The library refuses a file number that is not below
  the number of files of the image (`fileNumber >= getFileCount()`, as if file
  numbers were dense indexes from 0) and then looks the file up by its number;
  so every number from 0 to the highest one gets a `File`: the fixture's, or
  an empty placeholder without records (answered with exception 2 like a file
  that does not exist). A library `Record` holds its own registers and FC20/
  FC21 read or write `length` registers *inside one record*; in the fixture
  the record number is a register index and a request runs on over the
  following registers. So record `r` holds the file's registers from `r` on,
  up to 122 (the longest FC21 sub-request), the same `Register` objects as
  every other record that covers them.
- **FIFO queues (FC24).** `ReadFIFOQueueRequest.createResponse` takes the
  FIFO count from the holding register at the pointer address and the values
  from the holding registers after it (the library's `FIFO` class is not used
  by it). The fixture's queues are not holding registers (in the baseline,
  100 and 101 are holding registers with other values, and the queues at 100
  and 101 overlap), so each queue gets a `SimpleProcessImage` of its own, laid
  out the library's way, and `getProcessImage` returns it for an FC24 request.
  A queue whose values would run past 65535 cannot be laid out and is not
  served (exception 2).

**Transports.** `tcp` and `rtu-over-tcp`: the library's
`TCPSlaveConnection(socket, useRtuOverTcp)` on each accepted socket, one
thread per connection, read timeout 0 as `ModbusSlave` sets it. `udp`: the
library's `ModbusUDPTransport` on a terminal (`AbstractUDPTerminal`, public):
the library's own `UDPSlaveTerminal` is package-private and runs only inside
`ModbusUDPListener`. The adapter's terminal does what the library's does
(hands the transport a zero-padded buffer of fixed size, answers the sender of
the request) with a buffer of 260 bytes, the longest MBAP frame; the library's
has 256, which cuts off the longest requests (an FC16 with 123 registers is
259 bytes).

**Events.** `request` events are printed by `Device` from the library's typed
request and the response it is about to send (`ExceptionResponse.
getExceptionCode()`), so requests answered with an exception are reported
too. `connection-opened` and `connection-closed` come from the accept loop.
A request the library cannot decode is not answered and has no event.

**Client.** TCP and RTU over TCP: `TCPMasterConnection` (`connect(rtu)`,
`setTimeout` first to `--connect-timeout-ms` for the connect, then to
`--timeout-ms`: the library has one timeout for both) and one
`ModbusTCPTransaction` per request with the library's request classes for
every operation but `raw`. `setRetries(1)`: the library reads 0 as its
default of 5, so 1 is exactly one attempt; `setReconnecting(false)`. A
transaction reconnects by itself when it finds the connection closed, so the
adapter checks `isConnected()` before each request and reports
`connection-lost` instead. UDP: `UDPMasterConnection` and one
`ModbusUDPTransaction` per request, `setRetries(0)` (this class runs
`retries + 1` attempts). Each request is additionally bounded by
`--timeout-ms` plus 2 s, in case a peer trickles bytes.

Operations and the library classes they use: FC01-FC06, FC15, FC16, FC22,
FC23 (`ReadCoilsRequest` … `ReadWriteMultipleRequest`); `diagnostics`
(`ReadSerialDiagnosticsRequest`: one data word only, `--data` of other
lengths is a usage error); `report-server-id` (`ReportSlaveIDRequest`);
`read-file-record`, `write-file-record` (`ReadFileRecordRequest`,
`WriteFileRecordRequest`, one sub-request); `read-fifo-queue`
(`ReadFIFOQueueRequest`); `read-device-identification` (`ReadMEIRequest(level,
objectId)`, repeated with the next object id while "more follows").
`ReadMEIResponse` has no getters for the conformity level, "more follows" and
the next object id, and `ReportSlaveIDResponse` has the run indicator only as
a boolean: the adapter reads them from the response's own `getMessage()`, the
library's encoding of what it decoded.

Error mapping. The library reports every transport failure as a
`ModbusIOException` with a message and no cause:

| What the library reports | `error.code` |
|---|---|
| `connect()` throws, or the host does not resolve | `connect-failed` |
| `ModbusSlaveException`; the transaction's response is an `ExceptionResponse` of the request's function | `exception` |
| "Socket timeout reading response"; on UDP, `execute()` returns with no response | `timeout` |
| "Premature end of stream", "failed to write", a connection found closed before a request | `connection-lost` |
| "Executing transaction failed" (another transaction identifier), any other read failure, a response of another class or an exception response of another function, the UDP transaction's unit-identifier or function-code check | `invalid-response` |
| a write echo that differs from the request, a byte count that does not fit the request (checked by the adapter: the library does neither) | `invalid-response` |
| responses that differ under `--repeat` | `failed` |

The library reports a response that is shorter than its own byte count in
the same words as a connection the peer closed ("Premature end of stream"):
both are `connection-lost`.

## What it cannot do

| Feature | Why |
|---|---|
| `serverDeviceIdentification`, `serverDiagnostics`, `serverReportServerId` | The library's `ReadMEIRequest`, `ReadSerialDiagnosticsRequest` and `ReportSlaveIDRequest` answer exception 1 in `createResponse` (so do FC07, FC11, FC12). The fixture's `deviceIdentification` and `reportServerId` are validated (exit 4 when invalid) but not served. |
| `clientRaw`, `clientRawAnyResponse` | No request class for an arbitrary PDU (`IllegalFunctionRequest.writeData` throws), and `ModbusResponse.createModbusResponse` decodes a normal response of a function it does not know as an `ExceptionResponse`. `raw` is a usage error (exit 2). |
| `clientUnitIdCheck` | `ModbusTCPTransaction` compares the transaction identifier only; a response with another unit identifier is accepted (verified against a test peer). `ModbusUDPTransaction` does compare unit identifier and function code, but the feature is declared for the adapter. |
| `diagnostics` with other than two data bytes | `ReadSerialDiagnosticsRequest` has exactly one data word. Usage error, nothing sent. |
| file numbers 10000 and above | `SimpleProcessImage.getFileByNumber` refuses them (exception 2). |
| `serverIllegalFunction` | Over TCP an unknown function code closes the connection instead of exception 1: `IllegalFunctionRequest.readData` skips the MBAP length rather than the data length and reads past the frame (finding 1). Over UDP the library answers `c101` correctly, but the feature is declared for the adapter. |
| `serverFileRecord` | FC20 with two sub-requests answers the second one with the wrong records: `ReadFileRecordResponse.getMessage` advances the offset wrongly (finding 5; FIXTURES.md rule 10, spec 6.14). FC20 and FC21 are still answered by the library, with the adapter's checks in front. The self-tests send two sub-requests (`values.fc20.two-sub-requests`). |

## Findings about the library (3.4.0)

What the library's own `ModbusSlave` (`ModbusSlaveFactory.createTCPSlave`,
`createUDPSlave`, `createTCPSlave(…, useRtuOverTcp=true)`) does with a
`SimpleProcessImage` for unit identifier 1, and nothing in front of it;
request and response PDUs in hex (RTU: frames with CRC):

| Request | PDU | Answer | Specification (V1.1b3) |
|---|---|---|---|
| FC65 with data (TCP) | `410102` | connection closed, no answer | exception 1 (7) |
| FC65 with data (UDP) | `410102` | `c101` | correct |
| FC03, unit identifier 2 or 0 | `0300000001` | `8302` | a matter of the application; FIXTURES.md asks for 11 |
| FC03, RTU address 2 | `02 0300000001 8439` | `02 8302 30f1` | Serial Line V1.02, 2.2: no answer |
| FC03 RTU frame with a wrong CRC | `01 0300000001 7b0a` | answered normally | Serial Line V1.02, 2.5.1.2: discarded |
| FC03 / FC01 quantity 0 | `0300000000` | `0300` | exception 3 (6.3) |
| FC05 value 0x1234 | `0500011234` | `0500010000`: accepted as off, echo changed | exception 3 (6.5); the response is the echo |
| FC16 byte count 3 for 2 registers | `1000000002030001` | connection closed | exception 3 (6.12) |
| FC08, FC17, FC43/14, FC07, FC11, FC12 | `080000a537`, `11`, `2b0e0100`, `07`, … | exception 1 | FC08/FC17/FC43 are optional |
| FC20, two sub-requests | `140e0600010000000206000200000002` | `140c 0506000b 0506000100020000` | `140c 050600 0b01a4 0506 00010002`: the second sub-response overwrites the end of the first (6.14) |
| FC24 at 1246 over UDP | `1804de` | `18000600 0201b812` (MBAP length 9) | `1800060002 01b81284`: one byte short (6.18) |
| FC24 at 101 (empty) over UDP | `180065` | `180002 0000 000504`: three stale bytes | `1800020000` |
| FC20 over UDP | `140706000100000002` | MBAP length 0, no PDU | `1406 0506 000b01a4` |
| FC23 read 250..259, write 40..41 | `1700fa000a00280002040007 0008` | exception 2 after writing 40..41 | exception 2, nothing written (FIXTURES.md rule 6) |

In words:

1. **A function code it has no class for closes a TCP connection.**
   `ModbusTCPTransport.readRequest` reads the whole MBAP frame, then
   `IllegalFunctionRequest.readData` skips `getDataLength()` bytes, which is
   the MBAP length field (unit identifier and function code included) rather
   than the data length: it reads two bytes past the frame, gets
   `EOFException`, and the handler closes the connection. Over UDP the same
   code reads into the zero padding of the receive buffer and exception 1 is
   sent. Over RTU over TCP it skips no data at all and takes the first two
   data bytes for the CRC, so the stream is out of step afterwards. The
   adapter leaves this to the library (`raw --function 65` against the j2mod
   server: the connection closes).
2. **Unit identifiers.** A request for a unit identifier without a process
   image is answered with exception 2 (`AbstractModbusListener.handleRequest`),
   on RTU over TCP too: the auxiliary type `UNIT_ID_MISSMATCH` it sets
   suppresses only a log line.
3. **No quantity checks** in any request class: quantity 0 is a normal
   response with no data, quantities above the maximum are served as long as
   the frame holds them.
4. **RTU over TCP has no CRC check**, on either side: `ModbusTCPTransport` in
   headless mode reads the CRC and discards it ("TCP has proper error
   correction"). The adapter cannot add one without re-reading the stream, so
   the j2mod server answers a frame with a wrong CRC, and the j2mod client
   accepts a response with a wrong CRC. Unit identifier and function code are
   read as signed bytes there (no effect on the wire).
5. **FC05**: `WriteCoilRequest.readData` takes `0xFF` in the first value byte
   as on and anything else as off, keeps no raw value (the adapter cannot
   refuse it), and the response is encoded from the boolean, not echoed.
   `WriteCoilResponse.readData` sign-extends the echoed address: a client
   write of coil 32768 reads back address -32768 (the adapter then reports
   `invalid-response`; verified against an echoing peer).
6. **FC23 writes before it checks the read range**
   (`ReadWriteMultipleRequest.createResponse`); FC21 writes register by
   register and stops at the first missing one. The adapter checks first.
7. **File records** (FC20/FC21): file numbers are checked against the number
   of files as dense indexes; numbers of 10000 and above are refused; a
   record's registers are its own, so the fixture's register-index semantics
   need the layout above. `ReadFileRecordResponse.getMessage` advances by the
   data length only, so a response with more than one sub-response is
   corrupt (TCP and RTU encode through `getMessage`). An FC21 request without
   a complete sub-request ends in a `NullPointerException` while the response
   is written, and the connection closes.
8. **FC24** is served from the holding registers (count at the pointer
   address, values after it); the `FIFO` class of the process image is not
   used by it.
9. **UDP encodes responses by their data-length field**
   (`ModbusUDPTransport.writeMessage` uses `getOutputLength`/`writeTo`), and
   `ReadFIFOQueueResponse` and `ReadFileRecordResponse` never update theirs:
   FC24 responses over UDP always claim 7 data bytes (cut off or padded with
   stale bytes of the previous response), FC20 responses have none. TCP and
   RTU over TCP encode through `getMessage()` and are correct for one
   sub-request.
10. **UDP slave terminal**: `UDPSlaveTerminal` receives into 256 bytes (the
    longest MBAP frame is 260) and routes each reply to the sender of the
    request with the same first four bytes (transaction and protocol
    identifier), held in a table. Read from the source; the adapter has its
    own terminal (above).
11. **Client retries**: `ModbusTCPTransaction` reads `retries == 0` as its
    default of 5 retries, with random sleeps and a reconnect between them;
    `ModbusUDPTransaction` reads it as one attempt. A transaction connects
    again by itself when its connection is closed, and the transport's
    `writeMessage` reconnects on a socket error.
12. **UDP client**: after a request whose response did not come back,
    `ModbusUDPTransaction.execute()` logs "Cannot send UDP message" and
    returns normally; `getResponse()` is `null`, or the response of the
    transaction's previous request if it was reused. It does not compare
    transaction identifiers.
13. **Client unit identifier**: the TCP transaction ignores the unit
    identifier and function code of a response (`checkValidity` is called by
    the UDP and serial transactions only); a response of another function is
    decoded by the class of the function it carries.
14. **`ReadMEIResponse`** has no getters for conformity level, more follows
    and next object id; `getFieldCount()` returns 64 when a response has no
    objects. **`ReportSlaveIDResponse.getMessage`** writes the byte count as
    the data length plus 2.

## Choices made where the contract leaves room

- Unit identifiers 0 and 255 are "another unit identifier": exception 11.
- An address range that runs past 65535 is not a usage error: it is sent
  and the device answers.
- The fixture's `name` must equal the name of the directory it is in, as in
  `scripts/validate-fixtures.py`; otherwise exit 4.
- After a failure under `--repeat`, `response` holds the last normal response
  if there was one.
- A flag given twice: the last one counts.
- `read-device-identification` gives up after 64 requests, or when "more
  follows" names an object id that does not advance (`invalid-response`).

## Build

```sh
make image-j2mod smoke-j2mod
scripts/interop.sh j2mod
make candidate-j2mod CANDIDATE_VERSION=<version on Maven Central>
```

`UPSTREAM_VERSION` becomes the Maven property `j2mod.version`; the build
fails if the jar of that version is not on the class path afterwards, and the
version ends up in the adapter jar's manifest, which is where
`print-capabilities` reads it. Maven runs with `-C` (strict checksums).
Nothing in the image is architecture specific.
