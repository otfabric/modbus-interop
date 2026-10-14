# pymodbus adapter

Modbus TCP reference server and client built on
[PyModbus](https://github.com/pymodbus-dev/pymodbus), behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | PyModbus (Python), its own protocol engine |
| Version | 3.16.1, released 2026-10-11 (git tag `v3.16.1`, commit `804ae510d400e7690d86c8a1f648fa3f6c940330`) |
| Artifact | `pymodbus-3.16.1-py3-none-any.whl` from PyPI, `sha256:bb433f7417280bdea1190d95495aa6fb8e1fa8e0d720c436922b270bc378024c` |
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
  `request` event. For FC08, FC17, FC20, FC21 and FC24, whose stock handlers
  answer with built-in dummy data, the derived class answers from the
  fixture with the library's response classes (`ReturnQueryDataResponse`,
  `ReportDeviceIdResponse`, `ReadFileRecordResponse`,
  `WriteFileRecordResponse`, `ReadFifoQueueResponse`). File records and FIFO
  queues are kept by the adapter: the PyModbus datastore has no such
  tables. For every other function code 1..127 a class that answers
  exception 1.
- `DecodePDU.add_sub_pdu`: `custom_pdu` copies only the table of function
  codes into the server's decoder; the table of sub-functions (FC08, FC43)
  is global and would swap the request for the stock class of the
  sub-function after decoding. The derived class is registered for every
  known sub-function.
- `SimDevice(action=...)`: the exact bounds of the coil table for FC05 and
  FC15.
- A `ModbusTcpServer` subclass that overrides `callback_new_connection` to
  give each connection its own `trace_connect` callback: the stock callback
  has no access to the peer address. `trace_packet` and `trace_pdu` are used
  to print the event of a request the library answers by itself (see below).

**Client** (`modbus_interop/client.py`): `AsyncModbusTcpClient` with
`retries=0` and `reconnect_delay=0`, so that one request is one request and a
lost connection stays lost; the per-function methods (`read_coils`, ...,
`mask_write_register`, `readwrite_registers`, `read_device_information`,
`diag_query_data`, `report_device_id`, `read_file_record`,
`write_file_record`, `read_fifo_queue`); for a diagnostics sub-function
other than 0, the library's `DiagnosticBase` with that sub-function, sent
with `client.execute()`;
for `raw`, a `ModbusPDU` subclass registered with `client.register()` and
sent with `client.execute()`, as in PyModbus' custom message example.
`--repeat` reuses the connection. The adapter checks what the library does
not: that the response has the function code of the request, the number of
bits or registers asked for, the echo of a write (FC21 included) and of
Return Query Data, the sub-function of an FC08 response, and for FC24 the
byte count and the limit of 31 against the frame PyModbus decoded.

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
- FC20 with an odd record length is answered `80 01`: the request decoder
  builds `FileRecord(record_length=<registers>)`, which takes the length as
  bytes, requires it to be even and halves it. `14 07 06 0001 0000 0003` ->
  `80 01`. Even lengths are served (the handler doubles the field back).
- FC20 and FC21: the request decoders drop the reference type, so a
  sub-request with a reference type other than 6 is served as if it were 6,
  and the FC21 echo carries 6: `15 09 05 0002 0000 0001 ABCD` -> `15 09 06
  0002 0000 0001 ABCD`.
- Client, `report-server-id`: PyModbus takes the last byte as a boolean run
  indicator and everything before it as the identifier. `data` is the
  identifier followed by `FF` or `00`, so a run indicator other than 0x00 or
  0xFF is reported as `00`.
- Client, `diagnostics` with a sub-function other than 0: PyModbus decodes
  the data as registers and pads an odd length with 0x30 (ASCII `0`); the
  adapter cuts the data back to the length of the frame.
- The receive buffer is 264 bytes: when more than that is pending (several
  pipelined requests), PyModbus discards all of it without an answer.

## Where PyModbus 3.16.1 differs from the fixture rules, and what the adapter does

Verified against the installed package; requests and responses are PDUs in
hex.

| Behaviour of the library | Fixture rule | Adapter |
|--------------------------|--------------|---------|
| A request for a unit identifier that has no `SimDevice` (and no device 0) is answered with exception 4: `03 0000 0001` to unit 2 -> `83 04`. `ignore_missing_devices` has no effect on this path, and FC43 is answered for every unit identifier | exception 11 | the request classes check the unit identifier |
| An unknown function code is answered with function code 0x80: `41 0102` -> `80 01` | exception 1 (`C1 01`) | a request class for every function code |
| FC07, FC11, FC12 are served by stock classes | exception 1 | the same classes replace them |
| FC08 answers sub-functions 1..21 from built-in counters and any other sub-function with exception 4 (`08 0101 0000` -> `88 04`, a `KeyError` in the stock handler) | sub-function 0 echoes, any other is exception 1 | a request class registered for FC08 and for every sub-function of the global table |
| FC17 answers `Pymodbus` (or the identity objects joined by `-`) and run indicator 0xFF: `11` -> `11 09 50796D6F64627573 FF` | `serverId` and `runIndicator` of the fixture, exception 1 without `reportServerId` | the response is built from the fixture |
| FC20 answers every sub-request with the record `SERVER DUMMY RECORD.`; FC21 echoes and writes nothing; FC24 answers the queue `0, 1, 2, 3` for every address | the fixture's files and queues, exception 2 for a missing file, records past the end or an unknown queue | the responses are built from the fixture; FC21 checks every sub-request, then writes |
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
- The client does not check the function code of a response, the number of
  values in it, the echo of a write, or the byte count and the limit of 31
  of an FC24 response.
- The client closes the connection when it receives a frame it cannot
  decode, for example the `80 01` above: the asyncio protocol raises in
  `data_received`.
