# Capabilities

`print-capabilities` tells a consumer what an adapter image can do, so that a
test suite can skip what a stack does not support instead of failing on it.

A capability is declared `true` only when the self-tests of this repository
exercise it for that adapter. An upstream library having an API for something
is not enough.

## Document

One JSON object on one line ([schema](../schemas/capabilities.schema.json)):

```json
{
  "schemaVersion": "1.0",
  "adapter": "libmodbus",
  "adapterVersion": "v0.1.0",
  "protocol": "modbus-tcp",
  "upstream": {
    "name": "libmodbus",
    "version": "v3.1.11",
    "revision": "<commit or artifact identifier>",
    "license": "LGPL-2.1-or-later",
    "language": "C",
    "independentEngine": true
  },
  "roles": { "server": true, "client": true },
  "features": { "serverMaskWrite": true, "...": false },
  "serverFunctions": [1, 2, 3, 4, 5, 6, 15, 16, 22, 23],
  "transports": { "server": ["tcp"], "client": ["tcp", "udp"] },
  "clientOperations": ["read-coils", "..."]
}
```

| Field | Meaning |
|-------|---------|
| `adapter` | Adapter name; the image is `modbus-interop-<adapter>` |
| `adapterVersion` | Release of this repository the image was built from (`dev` for a local build) |
| `upstream` | The implementation inside: name, exact version and revision, licence, language |
| `upstream.independentEngine` | `false` when the adapter wraps the protocol engine of another adapter, or is OT Fabric's own stack (`own/`). Results from such an adapter do not count as an additional independent implementation |
| `roles` | Which of `server` and `client` the image provides |
| `features` | See below; every value is a boolean |
| `serverFunctions` | Function codes the server answers with something other than exception 1 |
| `clientOperations` | Operations of `client` |
| `transports` | Contract 1.2: the values of `--transport` each role accepts, `tcp` always first ([CONTAINER_CONTRACT.md](CONTAINER_CONTRACT.md#transports)). An empty list for a role the image does not have. A transport is listed only when the self-tests run it for that adapter |

## Features

Every adapter declares every feature, `true` or `false`.

| Feature | Meaning |
|---------|---------|
| `serverUnitIdFiltering` | The server answers a request for another unit identifier with exception 11 |
| `serverQuantityCheck` | The server answers a quantity of 0 or above the protocol maximum with exception 3 |
| `serverMaskWrite` | The server implements FC22 |
| `serverReadWriteMultiple` | The server implements FC23, write before read |
| `serverDeviceIdentification` | The server implements FC43/14 from the fixture |
| `requestEvents` | The server prints a `request` event for every request it answers |
| `connectionEvents` | The server prints `connection-opened` and `connection-closed` |
| `clientMaskWrite` | The `mask-write-register` operation |
| `clientReadWriteMultiple` | The `read-write-multiple-registers` operation |
| `clientDeviceIdentification` | The `read-device-identification` operation |
| `clientRaw` | The `raw` operation: any function code and PDU, the response PDU handed back as it arrived |
| `clientRepeat` | `--repeat`: several requests on one connection |
| `clientUnitIdCheck` | The client rejects a response whose unit identifier is not the one of the request (`invalid-response`) |
| `serverDiagnostics` | The server implements FC08 Return Query Data (sub-function 0) |
| `serverReportServerId` | The server implements FC17 from the fixture's `reportServerId` |
| `serverFileRecord` | The server implements FC20 and FC21 on the fixture's `fileRecords` |
| `serverFifoQueue` | The server implements FC24 from the fixture's `fifoQueues` |
| `clientDiagnostics` | The `diagnostics` operation |
| `clientReportServerId` | The `report-server-id` operation |
| `clientReadFileRecord` | The `read-file-record` operation |
| `clientWriteFileRecord` | The `write-file-record` operation |
| `clientFifoQueue` | The `read-fifo-queue` operation |
| `clientRawAnyResponse` | `raw` receives the normal response of any function code, framed by the MBAP length. Without it, `raw` receives exception responses and the normal responses of the functions the library knows |
| `serverIllegalFunction` | Contract 1.2: the server answers a function code it does not implement with exception 1 ([FIXTURES.md](FIXTURES.md) rule 12; Modbus Application Protocol V1.1b3, section 7) |
| `serverRejectedWriteUnchanged` | Contract 1.2: an FC15 or FC16 request that the server refuses with exception 2 writes nothing ([FIXTURES.md](FIXTURES.md) rule 2; the state diagrams of sections 6.11 and 6.12) |
| `clientLargestRegisterWrites` | Contract 1.2: the client sends FC16 with 123 registers and, with `clientReadWriteMultiple`, FC23 with 121 registers written, encoded as sections 6.12 and 6.17 define them |
| `serverLargestRegisterWrites` | Contract 1.2: the server accepts FC16 with 123 registers and, with `serverReadWriteMultiple`, FC23 with 121 registers written |
| `serverHighestAddress` | Contract 1.2: the server serves a block that ends at address 65535, the highest address of section 4.4 (the fixture's input registers 65280..65535) |

The eight basic functions (FC01 to FC06, FC15, FC16), in both roles, and
exception 2 for an address outside the fixture are not features: every
adapter has them. The five features of contract 1.2 split off the parts of
them that a stack of the matrix gets wrong, on any transport it declares, so that its pairings are recorded
as unsupported rather than waived.

The values per adapter are in [COMPATIBILITY.md](COMPATIBILITY.md).
