# modbusesp adapter

Wraps [modbus-esp8266](https://github.com/emelianov/modbus-esp8266)
(Alexander Emelianov, "Modbus Library for Arduino"), an Arduino library for
ESP8266, ESP32 and other boards, behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | `emelianov/modbus-esp8266`, git tag `4.1.1` |
| Version | 4.1.1 (commit `299666307a052df52f757f3131e292c2513bb57f`, 2023-01-03; `library.properties` of that tag says 4.1.0) |
| Licence | BSD-3-Clause |
| Engine | its own (`independentEngine: true`) |
| Roles | server and client |
| Transports | `tcp` and `rtu-over-tcp`, both roles. The library has no Modbus over UDP |
| Build | g++ 14.2.0 (Debian trixie), the library compiled unmodified for Linux; runs on `debian:trixie-slim` |

The tag `4.1.1` is the library's latest release. Its default branch has 22
months of further commits (up to `73f19119a4f92b092d395eed0ce29d18499611fe`,
2024-10-27) that are not released; this adapter builds the release.

Other components in the image: cJSON 1.7.18 (Debian `libcjson1`, MIT), for
the JSON documents.

## How the library runs on Linux

modbus-esp8266 is written against the Arduino core API. Its Modbus engine
is `src/Modbus.cpp` (the PDU code of both roles), `ModbusTCPTemplate.h` (Modbus
TCP over any Arduino-style `SERVER` and `CLIENT` class), `ModbusRTU.cpp` (RTU
over any Arduino `Stream`) and `ModbusAPI.h` (the public API). The library is
meant to be bound to a network library through those template parameters:
`ModbusTCP` is `ModbusTCPTemplate<WiFiServer, WiFiClient>`, `ModbusEthernet`
is `ModbusTCPTemplate<EthernetServer, EthernetClient>`, and its ClearCore
example binds it to a third one in one line.

The adapter does the same with BSD sockets:

```cpp
class ModbusPosix : public ModbusAPI<ModbusTCPTemplate<PosixServer, PosixClient>> {};
```

`src/arduino/` is the platform layer a board's Arduino core and network
library provide, and nothing else: `millis()`, `micros()`, `delay()`, the bit
macros, `String`, `IPAddress`, `Print`, `Stream` (with the core's
`readBytes()` timeout semantics), and `PosixClient`/`PosixServer` as the
`Client`/`Server` (non-blocking accept, copyable client handles that share one
connection, `connected()` true while received bytes are unread, as the
Ethernet and WiFi libraries do). It contains no Modbus code: it does not
parse, build or check any frame. Written bytes go to a transmit buffer that
is sent in one piece on `flush()` or before the application loop waits (as
a UART's or a network chip's transmit buffer): `ModbusRTU::rawSend()` writes
address, PDU and CRC with four calls, and as four TCP segments a gap between
them longer than the RTU inter-frame time would split the frame at the
receiver.

The library's sources are fetched at the pinned commit and compiled as they
are; the build checks them against the `SOURCES.sha256` it recorded at the
checkout (`/licenses/modbus-esp8266/` in the image has both).

### Settings

The library is configured at compile time through `src/ModbusSettings.h`,
which its documentation names as the compile-time settings. That file
`#define`s nearly every setting unconditionally, so a `-D` cannot change one;
the library expects the user to edit it. The adapter does not edit it:
[src/settings_overlay.h](src/settings_overlay.h) is included first in every
translation unit, includes `ModbusSettings.h` (which `#pragma once` then
keeps from being read again) and redefines these settings, with the effect of
editing those lines:

| Setting | Library default | Here | Why |
|---------|-----------------|------|-----|
| `MODBUSIP_MAXFRAME` | 200 | 253 | the largest PDU (Application Protocol V1.1b3, 4.1). With 200 the server answers a longer request with exception 4, and the client drops every longer response (no read of more than 98 registers or 1568 coils) |
| `MODBUSIP_MAX_CLIENTS` | 4 (8 on ESP32) | 16 | the contract's 16 simultaneous connections |
| `MODBUSIP_UNIQUE_CLIENTS` | on | off | with it, a connection from an IP address closes the previous one from the same address |
| `MODBUSIP_TIMEOUT`, `MODBUSRTU_TIMEOUT` | 1000 ms | `--timeout-ms` | the library's response timeout is the operation's |

Everything else is the default, `MODBUS_GLOBAL_REGS` included (all Modbus
objects of a process share one register set). This is configuration through
the library's documented settings file, but it is a changed settings file:
the image is the library as a user would build it for this purpose, not with
its defaults.

## How the library is used

**Server, tcp.** One `ModbusPosix` in server mode (`server(port)`), and the
application loop calls `task()`, as a sketch's `loop()` does. The fixture's
tables are the library's registers (`addCoil`, `addIsts`, `addHreg`,
`addIreg`), so reading, writing, the bit packing, the arithmetic of FC22 and
the write-then-read of FC23 are the library's code (`Modbus::slavePDU`).
FC20 and FC21 are the library's code too; its `onFile()` callback is the
storage of the fixture's files.

**Server, rtu-over-tcp.** The adapter listens (`PosixServer`) and binds every
accepted connection to its own `ModbusRTU` in server mode
(`begin(Stream*)`, `server(unitId)`); `MODBUS_GLOBAL_REGS` makes all of them
one device. The library answers only its address, not broadcasts and not
frames with a bad CRC (checked by hand: `01 03 0000 0002 c40b` is answered
`01 03 04 0000 0001 3bf3`; the same with a bad CRC, with address 0 or with
address 2 gets no answer).

In front of the library, through its public callbacks (`onRaw`, `onRequest`,
`onFile`), the adapter applies the device rules of
[docs/FIXTURES.md](../../docs/FIXTURES.md):

- tcp, another unit identifier: exception 11. `ModbusTCPTemplate::task()`
  hands every frame to `slavePDU` whatever its unit identifier; `onRaw()` is
  where the unit identifier can be seen, `onRequest()` (called by `slavePDU`
  before FC01-06, 15, 16, 22 and 23) and `onFile()` are where a request can be
  refused with an exception code;
- a quantity of 0 or above the maximum of the function: exception 3;
- any address outside the block of the table: exception 2; FC23 checks both
  ranges before anything is written.

Every other function code the library answers itself with exception 1.

**Client.** The library's client API is asynchronous: a call such as
`readHreg(ip, offset, values, count, callback, unit)` sends the request, and
the application calls `task()` until the library calls the callback with a
`Modbus::ResultCode`. tcp: `ModbusPosix` (`client()`, `connect(ip, port)`);
rtu-over-tcp: a `ModbusRTU` (`client()`) bound to a `PosixClient`, the unit
identifier as slave id. The operations are `readCoil`, `readIsts`, `readHreg`,
`readIreg`, `writeCoil`, `writeHreg`, `maskHreg`, `readWriteHreg`,
`writeFileRec` and `rawRequest`. The library's `readFileRec()` is not
offered: it refuses every correct response (finding 3), so
`read-file-record` is a usage error.

Error mapping: `EX_TIMEOUT` is `timeout`; `EX_UNEXPECTED_RESPONSE`,
`EX_DATA_MISMACH` and `EX_GENERAL_FAILURE` are `invalid-response`; a closed
connection (`isConnected()` false) is `connection-lost`. An exception response
is reported by the library as its exception code, the same numbers it uses for
its own checks (`EX_ILLEGAL_VALUE` for a FC20 response it refuses), so
`onRaw()`, which the library hands every response first, notes the function
code: a code is an `exception` only when the device sent an exception
response, otherwise `invalid-response`. `raw` takes the response PDU from
`onRaw()` as the library framed it (by the MBAP length on tcp, by the
inter-frame silence on RTU) and stops the library from decoding it further.
The library does not hand out the echo of a write: the response fields are
those of the request.

## What it cannot do

| Feature | Why |
|---------|-----|
| `serverDeviceIdentification`, `clientDeviceIdentification` | no FC43 in the library |
| `serverDiagnostics`, `clientDiagnostics`, `serverReportServerId`, `clientReportServerId`, `serverFifoQueue`, `clientFifoQueue` | no FC08, FC17 or FC24 in the library (`FC_DIAGNOSTICS` is declared "Not implemented", Modbus.h) |
| `serverFileRecord` | FC21 is right, but the FC20 response carries a wrong byte count (finding 2). The server still answers FC20 and FC21 with the library's code (both are in `serverFunctions`) |
| `clientReadFileRecord` | `readFileRec()` refuses every FC20 response that has the right byte count (finding 3); it accepts only the library's own server. The operation is refused (exit 2) |
| `requestEvents` | the callbacks do not report what the library answered (its own exceptions are not passed back), so a `request` event could not say what was sent |
| `connectionEvents` | `onConnect()` passes the peer's address only, `onDisconnect()` passes nothing (`IPADDR_NONE`, ModbusTCPTemplate.h:444) |
| `clientUnitIdCheck` | a response is matched by transaction identifier and function code only (ModbusTCPTemplate.h:309-310); on RTU a frame from another address is dropped, which ends in a timeout |
| transport `udp` | the library has no Modbus over UDP |
| `serverHighestAddress` | `addReg` never creates a register at address 65535 (Modbus.cpp:65, off by one), so a read that reaches it is exception 2: `04 ffff 0001` and `04 ff83 007d` are answered `84 02` (finding 1; spec 4.4). |

The client also does not compare the echo of a write response with the
request (`masterPDU`, Modbus.cpp:813-818), and on rtu-over-tcp a response
with a bad CRC is dropped silently (ModbusRTU.cpp:272), so it ends in
`timeout`, not the contract's `invalid-response`.

## Findings about the library (4.1.1)

Line numbers are those of the tag `4.1.1`.

1. **Address 65535 does not exist.** `addReg()` caps the count with
   `if (0xFFFF - address.address < numregs) numregs = 0xFFFF - address.address`
   (Modbus.cpp:65), so a register at 65535 is never created, and the reads
   refuse any range that ends there with the same off-by-one
   (`(0xFFFF - startreg.address) < numregs`, Modbus.cpp:491 and 525; FC15/16:
   182, 278). Application Protocol V1.1b3, 4.4: data are addressed 0 to
   65535. `04 ffff 0001` is answered `84 02`, and so is
   `04 ff83 007d` (65411..65535). The baseline's input registers end at 65535:
   the cases `read-input-registers.address-65535.quantity-1` and
   `read-input-registers.address-65411.quantity-125` fail with this server.
2. **FC20 response: the byte count counts itself and the function code.**
   The response is sized from `bufSize = 2` (Modbus.cpp:303) and
   `_frame[1] = bufSize` (Modbus.cpp:348). `14 07 06 0001 0000 0002` is
   answered `14 08 05 06 000b 01a4`: the byte count is 0x08, six bytes follow.
   Application Protocol V1.1b3, 6.14: Resp. data length is the number of
   bytes after it (0x0C for the two sub-responses of length 5 of its example).
3. **FC20 client: correct responses are refused.** `masterPDU` takes the end
   of the response data as `frame + frame[1]` (Modbus.cpp:799), two bytes
   short, so `data + data[0] > eoFrame` (Modbus.cpp:803) is true for every
   response whose byte count is right; it only accepts the inflated byte count
   of finding 2, i.e. its own server. It also requires Resp. data length and
   File resp. length of at least 0x07 (Modbus.cpp:793, 803), as the range in
   6.14 says, which the specification's own example (File resp. length 0x05)
   does not meet, and copies one byte more than the record data
   (Modbus.cpp:807).
4. **FC20 server: the response size is a uint8_t.** `bufSize`
   (Modbus.cpp:303) wraps for a response longer than 255 bytes, the buffer
   handed to `onFile()` is then too small for the records asked for, and the
   size check meant for it (Modbus.cpp:318, `bufSize > MODBUS_MAX_FRAME`) can
   never be true. `14 07 06 0001 0000 0080` (128 records) would overflow the
   heap. The adapter's `onFile()` refuses such a request with exception 2
   before it copies anything.
5. **Reads check only the first address and answer a bad quantity with
   exception 2.** `readBits`/`readWords` look up the first register only
   (Modbus.cpp:504, 533; a read past the end returns zeros), and a quantity of
   0 or above the maximum is `EX_ILLEGAL_ADDRESS` (Modbus.cpp:491, 525).
   Application Protocol V1.1b3, 6.1-6.4 (state diagrams): quantity first,
   exception 3. The documented `MODBUS_STRICT_REG` setting that checks every
   address does not compile (its loop uses an undeclared `k`, Modbus.cpp:499,
   528). The adapter's `onRequest()` applies the rules instead.
6. **FC23 does not check the write range and writes before it checks the
   read range.** The range test is `0xFFFF - field1 < field2 || 0xFFFF -
   field1 < field2` (Modbus.cpp:416, the read range twice); the write
   happens at Modbus.cpp:421 and the read range is looked up after it
   (Modbus.cpp:425). The adapter checks both before the library runs.
7. **Write quantities above the maximum.** FC15 accepts up to 2000 coils
   (`MODBUS_MAX_BITS`, Modbus.cpp:278; 6.11: 1968), FC16 and the write of
   FC23 up to 125 registers (`MODBUS_MAX_WORDS`, 182, 415; 6.12: 123, 6.17:
   121). Not visible here: the adapter checks the quantity first.
8. **Short PDUs are read past their end.** `slavePDU` reads `frame[1]` to
   `frame[4]` before it knows the function (Modbus.cpp:133-134), also for a
   PDU of one byte.
9. **`ModbusRTUTemplate::_slaveId` is not initialised** (ModbusRTU.h:25), and
   a client's `send()` sends only while it is 0. A `ModbusRTU` with static
   storage (as in every example sketch) works; one on the stack may never
   send. The adapter's are static.

## Choices made where the contract leaves room

- Modbus TCP and RTU-over-TCP are both `ModbusRTU`/`ModbusPosix` objects of
  the library; RTU is not a separate Modbus implementation of the adapter.
- On rtu-over-tcp a client request to unit 0 is a broadcast: the library
  sends it and does not wait for an answer, and the operation ends in
  `timeout` after `--timeout-ms`.
- `--bind-address` must be an IPv4 literal, and the client resolves `--host`
  to an IPv4 address (the library connects to an `IPAddress`).
- FC20 with several sub-requests and FC21 with several sub-requests are the
  library's: FC21 writes sub-request by sub-request, so a later invalid one
  leaves the earlier ones written. The contract's operations send one.

## Build

```sh
make image-modbusesp
# or
docker build -f adapters/modbusesp/Dockerfile -t ghcr.io/otfabric/modbus-interop-modbusesp:dev .
```

Pins in the [Dockerfile](Dockerfile): `UPSTREAM_VERSION`/`UPSTREAM_SHA` (the
tag and the commit it must name), `GXX_VERSION`, `CJSON_VERSION` and
`DEBIAN_DIGEST`.
