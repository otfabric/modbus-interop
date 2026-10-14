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
  "clientOperations": ["read-coils", "..."]
}
```

| Field | Meaning |
|-------|---------|
| `adapter` | Adapter name; the image is `modbus-interop-<adapter>` |
| `adapterVersion` | Release of this repository the image was built from (`dev` for a local build) |
| `upstream` | The implementation inside: name, exact version and revision, licence, language |
| `upstream.independentEngine` | `false` when the adapter wraps the protocol engine of another adapter. Results from such an adapter do not count as an additional independent implementation |
| `roles` | Which of `server` and `client` the image provides |
| `features` | See below; every value is a boolean |
| `serverFunctions` | Function codes the server answers with something other than exception 1 |
| `clientOperations` | Operations of `client` |

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

The eight basic functions (FC01 to FC06, FC15, FC16), in both roles, and
exception 2 for an address outside the fixture are not features: every
adapter has them.

The values per adapter are in [COMPATIBILITY.md](COMPATIBILITY.md).
