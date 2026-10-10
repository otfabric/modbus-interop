# pymodbus adapter

Modbus TCP reference server and client built on
[PyModbus](https://github.com/pymodbus-dev/pymodbus), behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | PyModbus (Python), its own protocol engine |
| Version | 3.15.0, released 2026-08-13 (git tag `v3.15.0`, commit `39949d726efa67411a4feed664736de05c073f16`) |
| Artifact | `pymodbus-3.15.0-py3-none-any.whl` from PyPI, `sha256:74d8dee961f23845f18a8481253183504dca05c4fcd102890cd28f4d7ce09267` |
| Licence | BSD-3-Clause |
| Base image | `python:3.14.8-slim-trixie`, pinned by digest in the [Dockerfile](Dockerfile) |
| Other dependencies | none: PyModbus needs only the Python standard library for TCP, and so does the adapter |

The adapter code is MIT. `/licenses` in the image holds the licence of
PyModbus, of Python and of this repository.

## Build

`requirements.txt` pins the wheel by hash and the build installs it with
`--require-hashes`. `UPSTREAM_VERSION` must equal that pin for a release
build. A candidate build (`make candidate-pymodbus CANDIDATE_VERSION=x.y.z`)
overrides it: the hash of another release cannot be known in advance, so the
wheel of that version is installed without hash checking, and the sha256 of
what was downloaded is what `print-capabilities` reports as
`upstream.revision`. The build fails if the installed version is not the
requested one.

Set `MODBUS_INTEROP_LOG_LEVEL=DEBUG` to see PyModbus' frame log on stderr.

## How PyModbus is used

**Server** (`modbus_interop/server.py`): `ModbusTcpServer` (asyncio) with one
`SimDevice` for the fixture's unit identifier, built from four separate
`SimData` blocks (coils, discrete inputs, holding registers, input
registers), and `ModbusDeviceIdentification` for FC43/14. Framing, decoding,
the datastore, the execution of every function and every response are the
library's. The adapter uses these extension points:

- `custom_pdu=`: for each function the device serves, a request class derived
  from the stock one. Its `datastore_update` wraps the stock handler with the
  unit identifier rule (it raises `NoSuchIdException`, which PyModbus turns
  into exception 11), the exact bounds of the bit tables for reads, and the
  `request` event. For every other function code 1..127 a class that answers
  exception 1.
- `SimDevice(action=...)`: the exact bounds of the coil table for FC05 and
  FC15.
- A `ModbusTcpServer` subclass that overrides `callback_new_connection` to
  give each connection its own `trace_connect` callback: the stock callback
  has no access to the peer address. `trace_packet` and `trace_pdu` are used
  to print the event of a request the library answers by itself (see below).

**Client** (`modbus_interop/client.py`): `AsyncModbusTcpClient` with
`retries=0` and `reconnect_delay=0`, so that one request is one request and a
lost connection stays lost; the per-function methods (`read_coils`, ...,
`mask_write_register`, `readwrite_registers`, `read_device_information`);
for `raw`, a `ModbusPDU` subclass registered with `client.register()` and
sent with `client.execute()`, as in PyModbus' custom message example.
`--repeat` reuses the connection. The adapter checks what the library does
not: that the response has the function code of the request, the number of
bits or registers asked for, and the echo of a write.

Errors: `ExceptionResponse` -> `exception`; a failed `connect()` ->
`connect-failed`; `ConnectionException` or a connection that ends while the
request is pending -> `connection-lost`; `ModbusIOException` ("no response")
-> `timeout`, or `invalid-response` when bytes did arrive after the request
(PyModbus silently drops a frame with another unit or transaction identifier
and closes the connection on a frame it cannot decode; both end as "no
response").

## What it cannot do

| Feature | Value | Why |
|---------|-------|-----|
| `serverQuantityCheck` | `false` | The request decoders of FC01, FC02, FC03, FC04, FC15 and FC23 raise on a quantity of 0 or above the maximum. The server then answers `80 01`: function code 0x80 (not the request's function + 0x80) and exception 1, instead of exception 3. Only FC16, and the write quantity of FC23, are answered with exception 3. Changing this would mean replacing the library's decoders. |

Everything else is `true`. Limits that no feature covers:

- `clientUnitIdCheck` is `true`, with a hole: for a request with unit
  identifier 0 PyModbus accepts a response with any unit identifier.
- Two requests in one TCP segment: only the first is answered; the second
  stays in the receive buffer until more data arrives.
- A request the library cannot decode (too short, or a rejected quantity) is
  answered `80 01` by the library. Its `request` event has `unitId`,
  `function` and `exception` only.

## Where PyModbus 3.15.0 differs from the fixture rules, and what the adapter does

Verified against the installed package; requests and responses are PDUs in
hex.

| Behaviour of the library | Fixture rule | Adapter |
|--------------------------|--------------|---------|
| A request for a unit identifier that has no `SimDevice` (and no device 0) is answered with exception 4: `03 0000 0001` to unit 2 -> `83 04`. `ignore_missing_devices` has no effect on this path, and FC43 is answered for every unit identifier | exception 11 | the request classes check the unit identifier |
| An unknown function code is answered with function code 0x80: `41 0102` -> `80 01` | exception 1 (`C1 01`) | a request class for every function code |
| FC07, FC08, FC11, FC12, FC17, FC20, FC21, FC24 are served by stock classes | exception 1 | the same classes replace them |
| A bit block is stored in 16-bit registers and padded to register boundaries; the padding can be read and written. With discrete inputs 1000..2999, `02 03E0 0008` (992..999) -> `02 01 00` | exception 2 | bounds check in the request classes (reads) and in the `SimDevice` action (writes) |
| The four lists of a `SimDevice` cannot be empty and a bit list takes only bit blocks, so "no coil table" cannot be expressed | exception 2 for a table the fixture does not have | a placeholder bit that the bounds check never lets through |
| FC43/14 always reports conformity level 0x83 | the level of the highest category that has objects, +0x80 | the response's `conformity` attribute is set |
| FC23 writes, then reads: with a valid write range and an invalid read range the answer is exception 2 and the registers are changed | nothing is written | the registers are put back through the datastore interface |

Left as the library does it (not covered by a fixture rule or a self-test):

- FC05 with a value other than 0x0000 or 0xFF00 is taken as ON and echoed as
  `FF00`: `05 0005 1234` -> `05 0005 FF00` (the specification asks for
  exception 3).
- FC15 accepts up to 2000 coils (the specification: 1968, 0x7B0).
- FC43/14 with read device id code 0: `2B 0E 00 00` -> `AB 04` (the
  specification: exception 3).
- The client waits 100 ms after every successful `connect()`.
- The client does not check the function code of a response, the number of
  values in it or the echo of a write.
- The client closes the connection when it receives a frame it cannot
  decode, for example the `80 01` above: the asyncio protocol raises in
  `data_received`.
