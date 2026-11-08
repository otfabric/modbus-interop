# jsmodbus adapter

Wraps [jsmodbus](https://github.com/Cloud-Automation/node-modbus)
(Cloud-Automation/node-modbus), a Modbus TCP and RTU library for Node.js,
behind the [container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | npm package [`jsmodbus`](https://www.npmjs.com/package/jsmodbus) |
| Version | 5.0.0 (published 2026-09-14). The npm metadata names commit `fdf02b2600b22519ae4e7465e9bec381a1653172` (branch `v5.0-dev`); the release has no git tag |
| Artifact | `jsmodbus-5.0.0.tgz`, integrity `sha512-YuJlEoWn06eeV4xgMNw8v+07QEaJcq3x7ROtapk3z1QWmbZJn94JkV2yrxULeWIYSrgbLDivifUL2QFq7m/low==` (sha256 `4050912b07b9f5d0017c1c1ea20135fd723d5c1d3e6611641be5d2c5397d0f97`) |
| Licence | MIT |
| Engine | its own (`independentEngine: true`) |
| Roles | server and client |
| Transports | `tcp` and `rtu-over-tcp`, both roles |
| Runtime | Node.js 26.13.0 (LTS), `node:26.13.0-trixie-slim` |

Other packages in the image, at the versions `package-lock.json` pins with
their integrity: commander 11.1.0, crc 4.3.2, debug 4.4.3 and ms 2.1.3, all
MIT, all dependencies of jsmodbus (the adapter adds none). `npm ci` installs
them with `--ignore-scripts`; `/licenses/BUNDLED-PACKAGES.txt` lists them.
jsmodbus ships no licence file: its README ends with the MIT licence, which
the build copies to `/licenses/jsmodbus/LICENSE`.

The adapter is plain JavaScript (CommonJS), no build step:

| File | |
|------|---|
| `src/main.js` | Commands, flags, capabilities, `print-fixture` |
| `src/fixture.js` | Loads a fixture with the rules of the schema and `scripts/validate-fixtures.py` |
| `src/server.js` | The `server` command and `Device` |
| `src/client.js` | The `client` command |

`DEBUG=*` makes the library log (with `debug`) to stderr.

## How the library is used

**Server.** `new Modbus.server.TCP(net.Server, options)` for `tcp`. The
library frames and decodes every request (`ModbusTCPRequest.fromBuffer`,
`RequestFactory`), and when a table is absent from `options` it emits the
request with a callback that sends a response (`readCoils`,
`writeMultipleRegisters`, ..., `readDeviceIdentification`). Leaving the
tables undefined "to use the events" is the documented way
(`examples/javascript/tcp/SimpleServer.js`) to put the application in charge
of the device, and the only place where an application can refuse a request:
with the tables given, the library's handlers answer before any event could
intervene, and the `pre*` events cannot stop them.

`Device` handles those events. It applies the rules of
[docs/FIXTURES.md](../../docs/FIXTURES.md): another unit identifier is
exception 11 (unit identifiers 0 and 255 included), a quantity of 0 or above
the maximum of the function is exception 3, an address outside the block of
the table is exception 2, in that order. A request that passes is answered
with the library's response body classes: reads are cut from the device's
tables by `ReadCoilsResponseBody.fromRequest` (and the three others) on a
buffer in the library's own layout; writes are applied to that buffer by the
adapter (a bit, a big-endian word, a copy of the request's data) and answered
with `WriteSingleCoilResponseBody.fromRequest` and the like. Exceptions are
the library's `ExceptionResponseBody`, framed with `ModbusTCPResponse` (or
`ModbusRTUResponse`). What the adapter writes to a table is therefore its
own code, as it would be for any application on this event interface; the
library's code for its own tables could not be used behind the checks above.

FC43/14 is the library's event-only function: it decodes the request, refuses
another MEI type (exception 1) and a read code other than 1..4 (exception 3)
itself, and hands the rest to the `readDeviceIdentification` listener.
`Device` builds a `ReadDeviceIdentificationResponseBody` from the fixture:
stream access (codes 1 to 3) returns the objects of the category from the
requested object id on (from 0 when the category has no such object, 6.21),
as many as fit into one PDU, with "more follows"; individual access (code 4)
returns the one object or exception 2. The conformity level is the fixture's
level plus 0x80 (individual access is offered): 0x82 for the baseline.

`rtu-over-tcp`: `ModbusRTUServer` serves one duplex stream. The adapter
creates one per accepted TCP connection (`new Modbus.server.RTU(socket,
options)`, a `net.Socket` is a duplex stream) and attaches the same `Device`,
so the state is shared. The library frames RTU by the length of the decoded
request body and drops a frame whose CRC does not match. A request for
another unit identifier (broadcast 0 included) is not answered: the handler
does not call the callback.

**Events.** `request` events are printed by `Device` after the response was
sent, for every request that reaches it, exceptions included. A request the
library does not decode (see the findings) reaches nobody and has no event.
`connection-opened` and `connection-closed` come from the library's
`connection` event (`ModbusServerClient.socket`) for `tcp`, and from the
`net.Server` for `rtu-over-tcp`.

**Client.** `new Modbus.client.TCP(socket, unitId, timeout)` on a
`net.Socket` the adapter connects (bounded by `--connect-timeout-ms`), with
`--timeout-ms` as the library's request timeout. Every operation is one of the
library's methods (`readCoils`, ..., `writeMultipleRegisters`,
`readDeviceIdentification`); the library encodes, frames, matches by
transaction identifier and decodes. `--repeat` awaits the requests one after
the other. `read-device-identification` follows "more follows" from object 0.

`rtu-over-tcp`: `new Modbus.client.RTU(socket, unitId, timeout)`. Its
request handler goes online on the `open` event of a serial port (or when
`socket.isOpen` is set); a `net.Socket` has `connect` instead, so the adapter
emits `open` on the socket when it connects. Nothing else is adapted.

Error mapping:

| What the library reports | `error.code` |
|---|---|
| the socket fails to connect, or does not connect in time | `connect-failed` |
| `UserRequestError` `ModbusException` | `exception`, with the response's function code + 0x80 and code |
| `UserRequestError` `Timeout`, nothing received | `timeout` |
| `UserRequestError` `Timeout` after bytes arrived that the library did not accept (another unit identifier, a frame it cannot decode) | `invalid-response` |
| `UserRequestError` `OutOfSync` (transaction identifier, function code), `Protocol` (protocol identifier), `crcMismatch` | `invalid-response` |
| `UserRequestError` `Offline`, or the socket closes while a request is pending | `connection-lost` |
| an exception response for another function, a read response with another number of bytes or registers than asked for, a write response that does not echo the request (checked by the adapter) | `invalid-response` |
| a runtime error thrown by the library while it decodes (finding 9) | `invalid-response` |
| responses that differ under `--repeat` | `failed` |

## What it cannot do

| Feature | Why |
|---|---|
| `serverQuantityCheck` | The library's request classes throw for a read of more than 2000 coils, inputs or registers, and its decoder then reports "not enough data" for ever: the request is not answered and the connection stalls (finding 1). Quantities of 0, and 126..2000 registers, reach `Device` and are exception 3, so three of the four quantity cases pass; FC01 with 2001 coils does not. |
| `serverMaskWrite`, `serverReadWriteMultiple`, `clientMaskWrite`, `clientReadWriteMultiple` | The library has no FC22 or FC23: no request or response class, no client method. The server does not answer them at all (finding 1); the client refuses the operations (exit 2). |
| `serverDiagnostics`, `serverReportServerId`, `serverFileRecord`, `serverFifoQueue` and the client operations for them | No FC08, FC17, FC20, FC21 or FC24 in either role. The fixture's `reportServerId`, `fileRecords` and `fifoQueues` are validated (exit 4 when invalid) but not served. |
| `clientRaw`, `clientRawAnyResponse` | There is no public way to send a PDU: requests are built only by the client's methods, and `register(requestBody)` of the request handler is protected. The client's response factory knows only the nine functions above. |
| `clientUnitIdCheck` | `MBClient._onData` hands a response to the request handler only if its unit identifier is the client's; any other is dropped without a word, and the request times out. The adapter reports that as `invalid-response` when bytes arrived, but the library itself does not reject the response. |
| Exception 1 for unknown function codes (FIXTURES.md rule 12) | Not a feature, and not met: see finding 1. The `raw` cases that send FC65 fail against this server for every client that has `raw`. |
| `udp` | The library has no UDP transport. |
| `serverIllegalFunction` | The server never answers a function code it cannot decode, and the connection stalls (a request with function 65 times out). |
| `clientLargestRegisterWrites` | FC16 with 123 registers is sent with byte count 246 but only 240 data bytes, because the library caps the data it encodes; the server cannot answer it. |
| `serverLargestRegisterWrites` | The RTU server frames an FC16 request by the byte count the library caps at 0xF6, misses the CRC of a request with 121-123 registers and never answers it (finding 7). Over TCP the server accepts them, but the feature is declared for every transport. |

## Findings about the library (5.0.0)

What the library's own server does, measured with `new
Modbus.server.TCP(netServer)` and its default tables (1024 bytes each,
holding registers 0 and 1 set to 0x1234, 0x5678) and nothing in front of it.
Request and response PDUs in hex (after the MBAP header), unit identifier 1
unless said otherwise:

| Request | PDU | Answer | Specification (V1.1b3) |
|---|---|---|---|
| FC65, FC07, FC08, FC22 | `410102`, `07ff`, `0800000000`, `16000000f20025` | no answer; later requests on the connection are not answered either | exception 1 (7, 6.x) |
| FC01 quantity 2001 | `01000007d1` | no answer, connection stalls | exception 3 (6.1) |
| FC03 quantity 0 | `0300000000` | `0300` | exception 3 (6.3) |
| FC03 quantity 126 | `030000007e` | `03fc...`: 126 registers, a PDU of 254 bytes | exception 3; the PDU exceeds 253 bytes (4.1) |
| FC03 at 5000, outside the table | `031388000a` | `0300`: a byte count of 0 | exception 2 (6.3) |
| FC03, unit identifier 2 | `0300000001` | `03021234`: served | a matter of the application; FIXTURES.md asks for exception 11 |
| FC05 value 0x1234 | `0500071234` | `0500070000`, and the coil is off | exception 3 (6.5) |
| FC16 quantity 2, byte count 2 | `100000000202000a` | `1000000001`: one register written | exception 3 (6.12) |
| FC16 quantity 0, byte count 0 | `100000000000` | `1000000000` | exception 3 (6.12) |
| FC15 quantity 10, one data byte | `0f0000000a01ff` | no answer, connection stalls | exception 3 (6.11) |
| FC43/14 without a listener | `2b0e0100` | `ab0e01` | `ab01` (6.21: error = 0xAB and the exception code) |
| FC43, MEI type 13 | `2b0d0100` | `ab0d01` | `ab01` |
| FC43/14, read code 5 | `2b0e0500` | `ab0e03` | `ab03` |
| FC06 then FC03 in one TCP segment | `0600000777`, `0300000001` | `03021234` first (the old value), then `0600000777` | requests are served in order |

In words:

1. **A request the decoder does not accept is never answered, and stalls the
   connection.** `RequestFactory.fromBuffer` knows FC 1-6, 15, 16 and 43. For
   any other code up to 0x2B it constructs `ExceptionRequestBody(fc, 1)`,
   whose constructor throws `InvalidFunctionCode` for exactly those codes, so
   the factory returns null; above 0x2B it returns undefined. A request class
   that throws in its constructor (quantity above 2000, FC15 with fewer data
   bytes than the quantity needs) ends the same way. `ModbusTCPRequest.fromBuffer`
   then returns null, which `ModbusServerRequestHandler` reads as "not enough
   data yet": the bytes stay at the head of its buffer, and nothing after them
   is decoded. There is no public hook before decoding, so the adapter cannot
   answer exception 1 or 3 there (and would have to re-implement the MBAP
   framing to do so). The same holds for `rtu-over-tcp`.
2. **No unit-identifier filtering**: every unit identifier is served.
3. **No address or quantity checks in the stock handlers.** Reads slice the
   table buffer: past its end the response is short or empty, with a byte
   count to match; quantity 0 and quantities up to 2000 registers are served.
4. **The decoder normalises what it should check.** FC05: the value becomes a
   boolean (`=== 0xFF00`), so the handler's own check for an invalid value is
   dead code. FC16: the quantity field is not read; the quantity is the byte
   count / 2. FC15: a quantity of 0 becomes the byte count
   (`quantity || values.length`). The adapter sees the normalised values and
   cannot refuse these requests either.
5. **FC43 exception responses carry the MEI type** (`ab 0e 01`), three bytes,
   where 6.21 has two (`ab 01`). The adapter sends its own FC43 exceptions
   with the library's two-byte `ExceptionResponseBody(fc, code)`; the
   library's own (MEI type other than 14, read code other than 1..4) keep the
   three-byte form.
6. **Requests that arrive together are served last-in first-out.**
   `ModbusServerRequestHandler.handle` adds each decoded request with
   `unshift`, and the client loop takes them with `shift`. Two requests in one
   TCP segment are executed in reverse order: a write followed by a read
   returns the value from before the write. The contract's clients send one
   request at a time, so the self-tests do not see it.
7. **The TCP client truncates FC16 with 121 to 123 registers.**
   `WriteMultipleRegistersRequestBody` caps its `byteCount` at 0xF6 (246),
   and `ModbusTCPRequest.createPayload` allocates `7 + byteCount` bytes: the
   request goes out with quantity 123 and byte count 246, but only 240 data
   bytes and an MBAP length of 247. In the self-tests no server writes the 123
   registers: libmodbus, PyModbus, digitalpetri, NModbus and tokio-modbus write
   nothing, and the jsmodbus server (whose decoder takes the quantity from the
   data, finding 4) writes 120. The RTU client builds the frame from the body and is not
   affected; the RTU server is: it frames requests by `byteCount + 3`, takes
   the CRC from the wrong place, drops the frame as corrupt and stalls the
   connection (FC16 with 121 to 123 registers is not answered over
   `rtu-over-tcp`).
8. **The RTU client cannot decode an FC43 exception response.**
   `ExceptionResponseBody.fromBuffer` reads the three-byte MEI form when the
   buffer has at least three bytes; an RTU buffer always does (the CRC follows),
   so `01 ab 01 crc crc` is read as MEI type 1, exception code = the first CRC
   byte, and the CRC is then looked for past the end: the client waits for
   more data until it times out.
9. **An exception code outside 1-6, 8, 10, 11 throws in the client's 'data'
   handler** (`errorCodeToMessage` throws while the rejection message is
   built), which would end the process. The adapter catches it and reports
   `invalid-response`.
10. **The RTU client never goes offline**: its request handler has no `close`
    listener, so a pending request waits for its timeout when the connection
    closes. The adapter races the request against the socket's `close` and
    reports `connection-lost`.
11. **The client does not validate a response against the request** beyond
    the function code and (TCP) the transaction and protocol identifiers: not
    the number of bits or registers, not the echo of a write, not that an
    exception response is for the request's function. The adapter checks
    these when it builds the result document.
12. **A response with another unit identifier is dropped silently**
    (`MBClient._onData`); the request times out.

## Choices made where the contract leaves room

- Unit identifiers 0 and 255 are "another unit identifier": exception 11
  (`tcp`), no answer (`rtu-over-tcp`).
- A device without `deviceIdentification` answers FC43/14 with exception 1.
- An address range that runs past 65535 is not a usage error: it is sent and
  the device answers.
- After a failure under `--repeat`, `response` holds the last normal response
  if there was one; after an exception response it is `null`.
- A flag given twice: the last one counts.
- `write-single-coil` reports the value as the library decoded it (on for
  0xFF00, off for anything else).

## Build

```sh
make image-jsmodbus smoke-jsmodbus
scripts/interop.sh jsmodbus
make candidate-jsmodbus CANDIDATE_VERSION=<version on npm>
```

At the pin, `npm ci --omit=dev --ignore-scripts` installs exactly what
`package-lock.json` names and checks every tarball against its sha512
integrity. A candidate build (`UPSTREAM_VERSION` set to another version) sets
that version in `package.json` and resolves a new lock file first. The build
fails if the installed jsmodbus is not `UPSTREAM_VERSION`, and records the
integrity of the installed tarball in `build-info.json`, which is where
`print-capabilities` reads the revision. Nothing in the image is
architecture specific.
