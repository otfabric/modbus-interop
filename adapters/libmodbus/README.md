# libmodbus adapter

Wraps [libmodbus](https://github.com/stephane/libmodbus) behind the
[container contract](../../docs/CONTAINER_CONTRACT.md), as a Modbus TCP
server and client.

| | |
|---|---|
| Upstream | libmodbus, C |
| Version | v3.2.0 (released 2026-07-02), commit `a9b025d12289855490b10d77461c99e001abfc0f` |
| Licence | LGPL-2.1-or-later |
| Linking | dynamic; the image ships `/usr/local/lib/libmodbus.so.5` |
| Other libraries | cJSON 1.7.18 (Debian `libcjson1` 1.7.18-3.1+deb13u1, MIT), for the JSON documents |
| Base image | `debian:trixie-slim`, pinned by digest |
| Adapter | C11, MIT, `src/` |

The library is built from its git tag, unpatched, with the commit checked
against `UPSTREAM_SHA`. Because it is LGPL, it stays a separate shared
library: replace `/usr/local/lib/libmodbus.so.5` to run the adapter on another
build. `/licenses/libmodbus/SOURCE` names the commit; `modbus-interop version`
prints the version the adapter was built against and the one that is loaded.

A candidate build (`make candidate-libmodbus CANDIDATE_VERSION=v3.1.12`) builds
that tag. With `CANDIDATE_SHA` the commit is checked; without it there is
nothing to check against and the build says so. `print-capabilities` always
reports the commit that was built.

## How the adapter uses libmodbus

### Server (`src/server.c`)

libmodbus has no server object: an application owns the sockets and calls the
library for each request. The adapter is that application, written the way
the library's own multi-client example is:

- `modbus_mapping_new_start_address()` creates the four tables at the
  addresses of the fixture; a table the fixture does not have gets size 0.
- `modbus_new_tcp()` + `modbus_tcp_listen()` open the listener, one
  `pselect()` loop in one thread waits on it and on every connection,
  `modbus_tcp_accept()` accepts, `modbus_set_socket()` + `modbus_receive()`
  read the request of the connection that is readable.
- `modbus_reply()` answers from the mapping. All data access, the quantity
  checks (exception 3), the address checks (exception 2), FC22, FC23 (write
  before read) and exception 1 for unknown functions are the library's.

Three things are decided by the adapter before `modbus_reply()` is called,
because the library would do something the fixture rules do not allow:

| Request | libmodbus on its own | Adapter |
|---------|----------------------|---------|
| Another unit identifier | answers it (no filtering on TCP) | `modbus_reply_exception()` with exception 11 |
| FC17 Report Server ID | answers with `LMB3.2.0` | `modbus_reply_exception()` with exception 1 |
| Function code 0 or above 127 | answers with the function code plus 128, truncated to a byte (read in its source, not observed) | not answered |

Up to 64 connections are served at a time (`select()` and one thread); a 65th
is accepted and closed.

**Events.** There is no callback in libmodbus: the adapter prints
`connection-opened` after `modbus_tcp_accept()`, `connection-closed` when
`modbus_receive()` fails, and `request` after the reply was sent.
`modbus_reply()` does not say what it sent, only how many bytes. The adapter
therefore works out the expected exception from the request, with the rules
of `modbus_reply()`, and compares: an exception response is always 9 bytes on
TCP. If the two ever disagree (another libmodbus version), it logs that on
stderr and prints no event rather than a wrong one.

### Client (`src/client.c`)

`modbus_new_tcp_pi()` (host names) and `modbus_connect()`, then the call an
application would make:

| Operation | Call |
|-----------|------|
| `read-coils`, `read-discrete-inputs` | `modbus_read_bits`, `modbus_read_input_bits` |
| `read-holding-registers`, `read-input-registers` | `modbus_read_registers`, `modbus_read_input_registers` |
| `write-single-coil`, `write-single-register` | `modbus_write_bit`, `modbus_write_register` |
| `write-multiple-coils`, `write-multiple-registers` | `modbus_write_bits`, `modbus_write_registers` |
| `mask-write-register` | `modbus_mask_write_register` |
| `read-write-multiple-registers` | `modbus_write_and_read_registers` |
| `raw` | `modbus_send_raw_request` + `modbus_receive_confirmation` |

- **Timeouts.** libmodbus has no connect timeout: `modbus_connect()` waits for
  the response timeout. The adapter sets it to `--connect-timeout-ms` for the
  connect and to `--timeout-ms` afterwards, and switches the byte timeout off
  so that the response timeout bounds the whole response. The name lookup is
  the C library's; it is bounded through `RES_OPTIONS` (whole seconds).
- **Errors.** The library reports through `errno` only. `EMBXILFUN` ..
  `EMBXGTAR` is an exception response (code 1..11), `ETIMEDOUT` is `timeout`,
  `ECONNRESET`/`EPIPE` is `connection-lost`, `EMBBADDATA`/`EMBBADEXC` is
  `invalid-response`.
- **Write responses.** libmodbus checks them and hands nothing out, so the
  result document carries the values of the request. For FC05, FC06, FC15 and
  FC16 the library compared the echo; for FC22 it checks only the function
  code and the length.
- **`raw`.** `modbus_receive_confirmation()` does not compare the response
  with the request; the adapter checks the transaction, protocol and unit
  identifier, the MBAP length and the function code itself.
- **Unit identifiers.** `MODBUS_QUIRK_MAX_SLAVE` is enabled: without it
  `modbus_set_slave()` refuses 248..254.

## What it cannot do

| Feature | Value | Why |
|---------|-------|-----|
| `serverDeviceIdentification` | false | libmodbus has no FC43; `modbus_reply()` answers exception 1. Not re-implemented in the adapter |
| `clientDeviceIdentification` | false | No API. `raw` cannot stand in: of a response to a function it does not know, libmodbus reads one byte and leaves the rest (see below). `read-device-identification` is a usage error (exit 2) |
| `clientUnitIdCheck` | false | The TCP backend compares the transaction and protocol identifiers of a response, not its unit identifier (`EMBBADSLAVE` exists for RTU only). Verified: a response from unit 2 to a request for unit 1 is accepted. Only `raw`, where the adapter does the checks, rejects it |

Limits within what is declared:

- **`raw` works for responses libmodbus can frame.** The library takes the
  length of a response from its function code and ignores the MBAP length. It
  knows FC01..FC07, FC15..FC17, FC22, FC23 and exception responses. For any
  other function it reads one byte after the function code; the adapter
  notices that the MBAP header announced more and reports `failed` instead of
  a truncated PDU. A raw request carries at most 252 bytes of data.
- **Exception codes above 11** in a response to a typed operation are
  `invalid-response` (`EMBBADEXC`): the library does not hand out the code.
  `raw` reports them.
- **Malformed requests are not answered.** A request shorter than its
  function needs makes `modbus_receive()` wait for the byte timeout (500 ms)
  and fail; the server then closes the connection. During that wait the one
  thread serves nobody else.
- **No pipelining after a rejected request.** Before it answers exception 1
  or 3, `modbus_reply()` sleeps for the response timeout and discards
  everything unread on the socket, including a following request that had
  already arrived. A client that waits for each response is not affected.

## Surprises in libmodbus 3.2.0

- **The server sleeps before it rejects.** `modbus_reply()` sleeps for the
  response timeout (500 ms by default) before exception 3 for a bad quantity
  and exception 1 for an unknown function. The adapter sets the response
  timeout of the server context to 20 ms, or every such request would stall
  all connections for half a second.
- **The MBAP length is never used**, in either direction, and the protocol
  identifier of a request is not checked (a request with protocol identifier
  5 is answered, with 0).
- **FC15 byte count.** A Write Multiple Coils request whose byte count is
  larger than the quantity needs is served; the specification asks for
  exception 3. A byte count that is too small is rejected.
- **`EMBXILVAL` has two meanings.** Since 3.2.0 the client calls report a
  quantity above the protocol maximum with `EMBXILVAL`, the errno of
  exception 3, without sending anything. The adapter checks quantities before
  it calls the library, so an `EMBXILVAL` it sees came from the device.
- **Debug output goes to stdout** (`modbus_set_debug`). The adapter never
  enables it: stdout carries JSON only.
- **`modbus_tcp_listen()` listens on every address** when the address string
  starts with the character `0`, whatever follows.
