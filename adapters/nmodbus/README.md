# nmodbus adapter

A Modbus TCP reference server and client built on
[NModbus](https://github.com/NModbus/NModbus), behind the
[container contract](../../docs/CONTAINER_CONTRACT.md).

| | |
|---|---|
| Upstream | NModbus, NuGet package [`NModbus`](https://www.nuget.org/packages/NModbus) |
| Version | 3.0.83 (published 2026-04-16), the latest stable release; the 4.0.0-alpha packages are pre-releases of a different API |
| Revision | `d96e7707a08cebe7f436087e7d575c930963d91a`, the commit the package was built from (its `.nuspec`, and the informational version of `NModbus.dll`) |
| Licence | MIT |
| Language, runtime | C#, .NET 10 (LTS) |
| Dependencies | none: the package has no dependencies on `net6.0` and later, and the adapter uses only the .NET base class library (`System.Text.Json` included) |

Nothing of NModbus is vendored or patched. The package is restored from
nuget.org at the exact version (`[3.0.83]`), and `packages.lock.json` holds
its content hash: at the pin the build restores with `--locked-mode`. A
candidate build (`UPSTREAM_VERSION` set to another version) cannot use the
lock file, since it locks the pin; the Dockerfile detects that case and
restores that one version without it.

`print-capabilities` reads the upstream version and revision from the
`NModbus.dll` that is loaded, not from a constant.

## Layout

| File | |
|------|---|
| `Dockerfile` | SDK stage (compiles portable IL once, on the build platform), Alpine runtime stage |
| `ModbusInterop.csproj`, `packages.lock.json`, `nuget.config` | The project, the locked restore graph, nuget.org as the only source |
| `src/Program.cs` | Commands, capabilities, `print-fixture` |
| `src/Args.cs` | Flags |
| `src/Fixture.cs` | Loads a fixture and applies the rules of the schema and of `scripts/validate-fixtures.py` |
| `src/Server.cs`, `src/Device.cs` | The `server` command and the NModbus objects that make up the device |
| `src/Client.cs` | The `client` command |
| `src/Output.cs` | stdout (JSON only), stderr, the logger given to NModbus |

`/usr/local/bin/modbus-interop` is a two-line launcher that runs
`dotnet /opt/modbus-interop/modbus-interop.dll`. The application is
framework-dependent and has no native host, so the same build output runs on
linux/amd64 and linux/arm64.

`MODBUS_INTEROP_LOG=information|debug|trace` makes NModbus log more to stderr
(default: warnings).

## Server

`ModbusFactory.CreateSlaveNetwork(TcpListener)` with slaves added through
`IModbusSlaveNetwork.AddSlave`. NModbus accepts any number of connections and
serves each on its own task.

What a stock NModbus slave does differs from
[docs/FIXTURES.md](../../docs/FIXTURES.md) in several places. The adapter
closes the gaps only through NModbus's own extension points, and the request
parsing, the data handling of each function and every response frame remain
NModbus's:

| Rule | Stock NModbus 3.0.83 | What the adapter does |
|------|----------------------|-----------------------|
| Blocks, exception 2 | `DefaultSlaveDataStore` is 65536 zero-initialised points per table; it never refuses an address | `ISlaveDataStore` with one `IPointSource<T>` per table that holds exactly the fixture's block and throws `InvalidModbusRequestException(2)` outside it. This is the extension point NModbus intends for a device's memory map |
| Unit identifier, exception 11 | A request for a unit identifier without a slave is dropped without an answer (`ModbusMasterTcpConnection`: `GetSlave` returns null, nothing is written). There is no hook for it | A slave is registered for every other unit identifier (0 and 2..255 for the baseline), each a `ModbusSlave` whose function services all throw `InvalidModbusRequestException(11)`. That is what an application acting as a gateway would register |
| Unknown function, exception 1 | The connection is closed: `CreateModbusRequest` dereferences the missing function service (`NullReferenceException`) before the slave, which would answer exception 1, is reached | A function service that throws `InvalidModbusRequestException(1)` is registered for every function code from 1 to 127 the device does not have (`ModbusFactory(functionServices, includeBuiltIn: false)`) |
| Quantity 0, exception 3 | Answered normally with an empty response (byte count 0) | `CheckedService`, a wrapper around each NModbus function service, checks the parsed request before it hands it to the service |
| Quantity above the maximum, exception 3 | Up to 2040 bits and 127 registers are answered normally (`Modbus.MaximumDiscreteRequestResponseSize`, `MaximumRegisterRequestResponseSize`), although the protocol allows 2000 and 125 (1968 and 123 for the writes, 121 for the write of FC23). Above those limits the request class throws `ArgumentOutOfRangeException` and the connection is closed | `CheckedService` applies the protocol maximum, and turns the `ArgumentOutOfRangeException` of the request class into exception 3 |
| FC05 value, FC15/FC16 byte count | A value other than 0xFF00 is taken as "off". A byte count that does not match the quantity is not noticed | `CheckedService` answers exception 3, as the specification's state diagrams do |
| FC23 | Writes, then reads (as the fixture rules ask). A request whose read range is outside the block is answered with exception 2 after the write has been made | `CheckedService` checks both ranges first, so nothing is written |
| FC08, FC21 | Built in (echo; write file record) | Left out: the fixture rules answer every other function with exception 1 |

Not provided, because NModbus has nothing to build them on:

- **FC22 Mask Write Register** (`serverMaskWrite: false`). NModbus has no
  message class and no service for it. A custom function service could be
  written, but it would be the adapter's implementation of the function, not
  NModbus's. The request is answered with exception 1.
- **FC43/14 Read Device Identification** (`serverDeviceIdentification:
  false`). `ReadDeviceIdentificationService` exists for the client side only;
  its `HandleSlaveRequest` throws `NotImplementedException` (which a stock
  slave answers with exception 4). The service is not registered and the
  request is answered with exception 1.
- **Connection events** (`connectionEvents: false`). Connections are accepted
  and closed inside `ModbusTcpSlaveNetwork`; the closed event is internal and
  there is none for a new connection. The only trace is the text of log
  messages, which is not an interface.

Request events come from `ReportingSlave`, an `IModbusSlave` that wraps each
slave: it sees the parsed request and the response NModbus is about to send,
exception responses included.

Limits that remain, all inside NModbus's connection handling (internal, no
extension point):

- A request that consists of a function code only (FC07, FC11, FC12, FC17 and
  any unknown function sent without data) is not answered with exception 1:
  NModbus requires three bytes after the MBAP header's length field and
  closes the connection (`FormatException`).
- A request PDU that is too short for its function, or whose function code is
  0 or above 127, closes the connection without an answer.
- The server reads the 6-byte MBAP prefix with one `ReadAsync` and the rest
  of the frame with another, and does not loop on a short read. A request
  that reaches the server in more than one TCP segment (a client that sends
  the header and the PDU separately, or a slow link) is parsed from a partly
  filled buffer, the missing bytes read as zero: a 7-byte MBAP header
  followed by the PDU 200 ms later closes the connection, and
  `01 03 00 00` followed by `00 02` is answered as a read of 0 registers
  (exception 3 here; a stock data store would answer it normally). Clients that send a request
  with one write are not affected; none of the reference clients trips it on
  a local network.
- An idle connection is never closed by the server.

## Client

`ModbusFactory.CreateMaster(TcpClient)` and the synchronous `IModbusMaster`
methods: `ReadCoils`, `ReadInputs`, `ReadHoldingRegisters`,
`ReadInputRegisters`, `WriteSingleCoil`, `WriteSingleRegister`,
`WriteMultipleCoils`, `WriteMultipleRegisters`, `ReadWriteMultipleRegisters`.

- **Timeouts.** The TCP connect is bounded by `--connect-timeout-ms`.
  `Transport.ReadTimeout`/`WriteTimeout` are set to `--timeout-ms`; a
  watchdog closes the socket if a response as a whole takes longer, since the
  socket timeout applies to each read.
- **Retries.** `Transport.Retries` is 3 by default; the adapter sets 0. It
  also sets `SlaveBusyUsesRetryCount`: by default NModbus resends a request
  for ever when it is answered with exception 6.
- **Write responses.** NModbus compares the echo with the request and returns
  nothing, so the result document reports the request's values.
- **`read-device-identification`.** `IModbusMaster.ReadDeviceIdentification`
  returns the objects of a single response and neither the conformity level
  nor "more follows". The adapter sends NModbus's `ReadDeviceIdRequest`
  through `ExecuteCustomMessage<ReadDeviceIdResponse>` instead and follows
  "more follows" itself.
- **`raw`.** `ExecuteCustomMessage` with a request and a response class of
  the adapter that carry the bytes as they are. NModbus still checks the
  function code, unit identifier and transaction identifier of the response.
- **`mask-write-register`** is not available (`clientMaskWrite: false`):
  NModbus has no Mask Write Register. The operation is a usage error (exit 2).
- **`--repeat`** sends the request again on the same master and connection.

Error codes: NModbus raises `SlaveException` for an exception response and
`IOException` for nearly everything else. The adapter maps a socket timeout
to `timeout`, a closed or reset connection to `connection-lost`, and the
validation failures (function code, unit identifier, transaction identifier,
byte count, echo) and parsing failures to `invalid-response`.

Surprises on the client side:

- An exception response is raised before the response is compared with the
  request, so NModbus accepts one with another unit identifier, transaction
  identifier or function code. `SlaveException` carries the unit identifier
  and function code, and the adapter reports a mismatch of either as
  `invalid-response`; the transaction identifier of an exception response is
  not available and stays unchecked.
- Exception 5 (acknowledge) is never reported: NModbus waits for a second
  response to the same request, and the operation ends as `timeout`.

## Fixture validation

`server` applies the rules of the schema and of
`scripts/validate-fixtures.py`, including "the name is the directory name":
a fixture must be mounted as `<anything>/<name>/fixture.json`.
