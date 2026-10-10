# digitalpetri adapter

Wraps [digitalpetri/modbus](https://github.com/digitalpetri/modbus), a Modbus
library for Java built on Netty, behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | `com.digitalpetri.modbus:modbus-tcp` (with `modbus`) from Maven Central |
| Version | 2.1.6 (tag `v2.1.6`, commit `0599935194c38c54c540293de2501ad3fe47da50`, released 2026-08-08) |
| Licence | EPL-2.0 |
| Engine | its own (`independentEngine: true`) |
| Roles | server and client |
| Build | Maven 3.9.16 on Temurin 25; runs on the Temurin 25 JRE |

The 2.x line is a rewrite of 1.x (`modbus-master-tcp`, `modbus-slave-tcp`,
last release 1.2.2): other artifacts, another API. This adapter is written
against 2.x.

Other jars in the image: Netty 4.1.136.Final, netty-channel-fsm 1.0.2,
strict-machine 1.0.0 and slf4j-api 2.0.18 (all at the versions the upstream
POM names; the adapter does not override them), slf4j-simple 2.0.18 (the
library's logging, to stderr) and Gson 2.14.0 with its
error_prone_annotations 2.48.0 (the JSON documents). `/licenses/NOTICE.txt`
in the image maps each to its licence text.

## How the library is used

**Server.** `ModbusTcpServer.create(NettyTcpServerTransport, ModbusServices)`.
The library decodes each request and calls the method of its function on the
services object with the unit identifier. The device is the library's own
model: the fixture is loaded into a `ProcessImage`, and the library's
`ReadWriteModbusServices` serves it. Reading, writing, the bit packing, the
mask arithmetic of FC22 and the write-before-read of FC23 are therefore the
library's code, not the adapter's.

The process image is sparse and unbounded (an address nobody wrote reads as
0) and those services check little (see the findings), so `Device` stands in
front of them. It implements `ModbusServices`, applies the rules of
[docs/FIXTURES.md](../../docs/FIXTURES.md) and then calls the library's
service; what must be refused it throws as the library's
`ModbusResponseException(function, code)`, which the library sends as an
exception response:

- another unit identifier: exception 11 (the library's own idiom,
  `UnknownUnitIdException`, makes it stay silent instead);
- quantity 0 or above the maximum of the function, a byte count that does not
  match the quantity (FC15, FC16, FC23), a coil value other than `0x0000` and
  `0xFF00` (FC05): exception 3;
- any address outside the block of the table: exception 2.

`Device` wraps `ReadWriteModbusServices` rather than extending it because its
write methods do not declare `ModbusResponseException`: a subclass cannot
refuse a write with an exception response.

`Device` also implements `RawModbusTcpServices` (new in 2.1.6), the hook the
library offers every request PDU to before decoding it. It is used for one
thing: a function code the library cannot decode is answered with exception 1
(see the findings for what happens without it). The ten functions the library
decodes pass through to the typed methods. Because of this hook, and of
`ModbusTcpClient.sendRaw` for the `raw` operation, 2.1.6 is the oldest
version the adapter builds against.

**Events.** `request` events are printed by `Device`, after the request was
served or rejected, so a request answered with an exception is reported too.
`connection-opened` and `connection-closed` come from the transport's
`pipelineCustomizer`, which the library calls once for every accepted Netty
channel; the adapter reads the peer address there and listens on the channel's
close future.

**Client.** `ModbusTcpClient.create(NettyTcpClientTransport, ...)` with
`connectTimeout` from `--connect-timeout-ms` and `requestTimeout` from
`--timeout-ms`; `connectPersistent=false` and `reconnectLazy=true` so that the
transport makes one connection attempt and never reconnects behind the
operation. Requests go out with `sendAsync(unitId, <RequestPdu>)`; `raw` uses
`sendRawAsync(unitId, pdu)`, which hands back the response PDU as it arrived.
`--repeat` sends the requests one after the other on the same client.

Error mapping:

| What the library reports | `error.code` |
|---|---|
| the connect future fails or does not complete in time | `connect-failed` |
| `ModbusResponseException` | `exception` |
| `TimeoutException` ("request timed out after N ms") | `timeout` |
| its `ConnectionListener.onConnectionLost` fires while a request is pending, or an `IOException` | `connection-lost` |
| `ModbusException` (empty PDU, another function code, malformed exception response), or a runtime exception from its response decoder (`BufferUnderflowException`) | `invalid-response` |
| a response the library accepted but whose data length does not fit the request, or whose echo differs from the request (checked by the adapter, see below) | `invalid-response` |
| responses that differ under `--repeat` | `failed` |

## What it cannot do

| Feature | Why |
|---|---|
| `serverDeviceIdentification`, `clientDeviceIdentification` | The library has no FC43. `FunctionCode` names 0x2B, but there is no request or response class and neither serializer knows it. The server answers FC43 with exception 1; the client has no `read-device-identification` operation (exit 2). It could be hand-built on the raw hooks, but that would be the adapter's implementation, not the library's. |
| `clientUnitIdCheck` | `ModbusTcpClient` matches a response to its request by transaction identifier only. A response with another unit identifier (or another protocol identifier) is accepted. Verified against a test peer that answers with the wrong unit identifier: the operation succeeds. |

## Findings about the library (2.1.6)

What the library's own server does, measured with `ReadWriteModbusServices`
on a `ProcessImage` and nothing in front of it (unit identifier 1, holding
registers 0 and 1 set), request and response PDUs in hex:

| Request | PDU | Answer | Specification (V1.1b3) |
|---|---|---|---|
| FC65, FC43/14, FC07, FC08, FC17 | `410102`, `2b0e0100`, `07`, ... | connection closed, no answer | exception 1 |
| FC03, unit identifier 2 | `0300000001` | no answer, connection stays open | a matter of the application; FIXTURES.md asks for exception 11 |
| FC03 at 5000, never written | `0313880001` | `03020000` | exception 2 for an address the device does not have |
| FC06 at 60000, never written | `06ea600042` | `06ea600042` | as above |
| FC03 quantity 0 / 126 | `0300000000` / `030000007e` | `8303` | exception 3: correct |
| FC01 quantity 2001 | `01000007d1` | `8103` | exception 3: correct |
| FC05 value 0x1234 | `0500071234` | `0500071234`, and the coil reads as on | exception 3 (6.5) |
| FC16 quantity 0, byte count 0 | `100000000000` | `1000000000` | exception 3 (6.12) |
| FC16 quantity 124, 248 bytes | `1000 2000 7c f8 ...` | `100020007c` | exception 3: the maximum is 123 |
| FC16 quantity 2, byte count 2 | `1000100002020001` | connection closed, no answer | exception 3 |
| FC15 quantity 1969 | `0f 0000 07b1 f7 ...` | `0f000007b1` | exception 3: the maximum is 1968 (6.11) |
| FC15 quantity 10, one data byte | `0f0000000a01ff` | connection closed, no answer | exception 3 |
| FC23 read quantity 0 | `17000a0000000b00020400070008` | `1700` | exception 3 (6.17) |
| FC23 read quantity 126 | `17000a007e000b00020400070008` | `17fc...`: 126 registers, a PDU of 254 bytes | exception 3; the PDU exceeds the 253-byte limit |
| FC23 write quantity 0 | `17000a0001000b000000` | `17020000` | exception 3 |
| FC22 on 0x1234, and 0x00F2, or 0x0025 | `16000000f20025` | echo; the register reads 0x0035 | correct |
| FC23 read 10..13, write 11..12 | `17000a0004000b00020400070008` | `17080000000700080000` | correct: write before read |

In words:

1. **A function code it cannot decode closes the connection.** The request
   serializer knows FC 1, 2, 3, 4, 5, 6, 15, 16, 22 and 23. For anything else
   `DefaultRequestSerializer.decode` throws, `NettyTcpServerTransport` logs
   "Error handling frame" and closes the channel. The `default ->` branch of
   `ModbusTcpServer` that would send exception 1 is reached only for a code
   that decodes, so never. The adapter answers through
   `RawModbusTcpServices`.
2. **A truncated request of a known function closes the connection too**
   (`BufferUnderflowException` in the decoder), e.g. FC03 with two data bytes.
   Nothing is answered; the adapter leaves this to the library, and no
   `request` event is printed.
3. **The library's services validate the reads only.** FC01 to FC04 check the
   quantity (`ModbusServices.checkBitRange/checkRegisterRange`); the write
   services, FC22 and FC23 check nothing: quantities above the maximum and of
   0 are served, and a byte count shorter than the quantity ends in an
   `ArrayIndexOutOfBoundsException` and a closed connection. The decoder does
   not compare byte count and quantity either.
4. **There are no address bounds.** The process image is a sparse map over
   the whole address space; unknown addresses read as 0 and can be written.
5. **Unknown unit identifiers: silence is the built-in behaviour**
   (`UnknownUnitIdException`). Exception 11 has to be thrown explicitly.
6. **A subclass of `ReadWriteModbusServices` cannot refuse a write**: its
   write methods narrow the `throws` clause of `ModbusServices` to
   `UnknownUnitIdException`.
7. **The client lets a pending request time out when the connection closes.**
   Nothing fails the pending promise on channel close; the caller gets
   "request timed out" after the full timeout. The adapter races the response
   against the transport's connection listener to report `connection-lost`
   at once.
8. **The client does not validate the MBAP header of a response** beyond the
   transaction identifier: unit identifier and protocol identifier are
   ignored (a response with another unit identifier is accepted). A response
   with an unknown transaction identifier is logged and dropped; the request
   then times out.
9. **The client does not compare a write response with the request** (FC05,
   FC06, FC15, FC16, FC22) and does not check that a read response has the
   number of bytes the request asked for; it hands the decoded response over.
   The adapter makes these comparisons when it builds the result document.
10. **The transport defaults are for long-lived connections**:
    `connectPersistent=true` keeps trying after a failed connect and
    `reconnectLazy=false` reconnects as soon as the connection is lost.
11. **One queue for all connections.** `NettyTcpServerTransport` runs the
    requests of all connections through one `ExecutionQueue` with concurrency
    1, so the services object is never called concurrently.
12. **MBAP framing is lenient**: `ModbusTcpCodec` does not check the protocol
    identifier (a request with protocol identifier 7 is served, and answered
    with protocol identifier 0) and accepts any length field. A length of 0
    or 1 (no PDU) makes the decoder throw and the connection close. Bytes
    after a complete request PDU are ignored (FC03 with two extra bytes is
    served).

## Choices made where the contract leaves room

- Unit identifiers 0 and 255 are "another unit identifier": exception 11.
- An address range that runs past 65535 (for example `--address 65535
  --quantity 2`) is not a usage error: it is sent and the device answers.
- The fixture's `name` must equal the name of the directory it is in, as in
  `scripts/validate-fixtures.py`; otherwise exit 4.
- After a failure under `--repeat`, `response` holds the last normal response
  if there was one.
- A flag given twice: the last one counts.

## Build

```sh
make image-digitalpetri smoke-digitalpetri
scripts/interop.sh digitalpetri
make candidate-digitalpetri CANDIDATE_VERSION=<version on Maven Central>
```

`UPSTREAM_VERSION` becomes the Maven property `modbus.version`; the build
fails if the jar of that version is not on the class path afterwards, and the
version ends up in the adapter jar's manifest, which is where
`print-capabilities` reads it. Maven runs with `-C` (strict checksums).
Nothing in the image is architecture specific.
