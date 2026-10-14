# Plan

## Scope of the first release (contract 1.0)

Modbus TCP, both roles, five stacks: libmodbus, PyModbus, digitalpetri/modbus,
NModbus and tokio-modbus.

| Area | In 1.0 |
|------|--------|
| Functions | FC01 to FC06, FC15, FC16 on every stack; FC22, FC23 and FC43/14 where the stack has them |
| Client | One bounded operation per invocation; `--repeat` for many requests on one connection; `raw` for requests the protocol does not allow |
| Server | One fixture, shared state, request and connection events |
| Exceptions | Illegal function (1), illegal data address (2), illegal data value (3), gateway target device failed to respond (11) |
| Self-tests | Smoke per image; every client against every server; values, writes, events, sessions |

## Contract 1.1

FC08 Return Query Data, FC17, FC20, FC21 and FC24 as operations and server
behaviour where a stack has them (see the matrix in
[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)); fixture fields for server
ID, file records and FIFO queues; `clientRawAnyResponse`.

## Later

| Item | Why it is not in 1.0 |
|------|----------------------|
| Modbus RTU over TCP and over a serial line (pseudo-terminal pair between containers) | Needs a second framing in every adapter and a way to connect serial ports across containers |
| Modbus over TLS (Modbus Security) | Few of the stacks support it |
| FC07, FC11, FC12, and FC08 sub-functions other than Return Query Data | Support differs widely; serial-line functions with little typed support |
| Several blocks per table, several unit identifiers in one server | libmodbus maps one block per table; needs a custom reply path there |
| Fault-injecting server (delays, truncated or malformed frames) | A reference stack cannot be asked to misbehave; this belongs in a separate, purpose-built image |
| Device identification individual access (read code 4) | Not all stacks that have FC43 expose it |

## Order of work

1. Contract, fixture format, baseline fixture, schemas, self-test scripts.
2. The five adapters.
3. Cross-stack self-test; findings and the capability matrix in
   [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).
4. CI on both architectures, weekly upstream candidates, release workflow.
