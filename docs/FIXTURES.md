# Fixtures

A fixture describes a Modbus device: which coils and registers exist and what
they hold when the device starts. Every reference server serves the same
fixture the same way, so a client sees the same device whichever stack is
behind it.

Fixtures are JSON, validated against
[fixtures/schema/modbus-fixture.schema.json](../fixtures/schema/modbus-fixture.schema.json)
and the cross-field rules in `scripts/validate-fixtures.py`.

## Document

```json
{
  "schemaVersion": "1.0",
  "name": "baseline",
  "description": "...",
  "unitId": 1,
  "coils":            { "address": 0,     "values": [true, false, "..."] },
  "discreteInputs":   { "address": 1000,  "values": [false, true, "..."] },
  "holdingRegisters": { "address": 0,     "values": [0, 1, 32767, "..."] },
  "inputRegisters":   { "address": 65280, "values": [65535, 0, "..."] },
  "deviceIdentification": {
    "objects": [ { "id": 0, "value": "otfabric" }, { "id": 1, "value": "MBI-BASELINE" } ]
  },
  "reportServerId": { "serverId": 42, "runIndicator": true },
  "fileRecords": [ { "file": 1, "records": [11, 420, "..."] } ],
  "fifoQueues": [ { "address": 1246, "values": [440, 4740] } ]
}
```

| Field | Meaning |
|-------|---------|
| `name` | Must equal the directory name under `fixtures/` |
| `unitId` | The unit identifier the device answers to, 1..247 |
| `coils`, `discreteInputs`, `holdingRegisters`, `inputRegisters` | One block per table: the address of its first element and the initial values. A table that is absent does not exist on the device |
| `deviceIdentification.objects` | The objects of Read Device Identification, by object id: 0..2 basic, 3..6 regular, 128..255 extended. Optional |
| `reportServerId` | Report Server ID: a one-byte `serverId` and the `runIndicator` (true = 0xFF, false = 0x00). Optional. One byte and no additional data is the shape every stack that serves FC17 can produce |
| `fileRecords` | Files for Read/Write File Record: a file number 1..65535 and its registers, record number = index, at most 10000. Optional |
| `fifoQueues` | FIFO queues for Read FIFO Queue: the FIFO pointer address and at most 31 values, oldest first. Optional |

A block is `address` .. `address + len(values) - 1` and must end at or before
65535. There is exactly one block per table: every stack can serve that, and
it is enough to give each table a different place in the address space.

## Behaviour of the device

These rules are the same for every server adapter. Where a stack cannot
follow one, its capability document says so, and the self-test cases that
need it name the feature.

1. **Unit identifier.** A request for the fixture's `unitId` is served. A
   request for any other unit identifier is answered with exception 11
   (gateway target device failed to respond), 0 and 255 included. Feature
   `serverUnitIdFiltering`.
2. **Addresses.** A read or write that lies entirely within the block of its
   table is served. One that touches any address outside it, including a
   table the fixture does not have, is answered with exception 2 (illegal
   data address), and nothing is written.
3. **Quantities.** A quantity of 0, or above the protocol maximum of the
   function, is answered with exception 3 (illegal data value), also when
   the address is outside the block: the quantity is checked first, as in the
   state diagrams of the specification. Feature `serverQuantityCheck`. The
   self-tests cover the read functions; what a stack does with an invalid
   write (FC05 value, FC15/FC16 quantity or byte count) is recorded per stack
   in [COMPATIBILITY.md](COMPATIBILITY.md).
4. **Writes** change the state: FC05, FC06, FC15, FC16, FC21, FC22 and the write
   part of FC23 change coils, holding registers and file records, and a later read on any
   connection returns the new values. Discrete inputs and input registers
   never change.
5. **FC22** sets the register to `(current AND andMask) OR (orMask AND NOT
   andMask)`. Feature `serverMaskWrite`.
6. **FC23** performs the write before the read, so a read range that
   overlaps the write range returns the values just written. Both ranges
   are checked before anything is written. Feature `serverReadWriteMultiple`.
7. **FC43/14** (stream access) returns the fixture's objects in ascending id:
   ids 0..2 for the basic category, 0..6 for regular and all of them for
   extended. The conformity level is that of the highest category the fixture
   has objects for (1 basic, 2 regular, 3 extended), plus 128 when the server
   also offers individual access. Feature `serverDeviceIdentification`.
8. **FC08** sub-function 0 (Return Query Data) answers with the request:
   the sub-function and its data. Any other sub-function is answered with
   exception 1. The self-tests send two data bytes; NModbus echoes exactly
   one data word and answers other lengths with exception 3. Feature
   `serverDiagnostics`.
9. **FC17** answers with the byte count, `serverId` and the run indicator
   (0xFF or 0x00) of `reportServerId`. Feature `serverReportServerId`.
10. **FC20** reads, for each sub-request with reference type 6, `record
    length` registers of the file from `record number` on. A file the fixture
    does not have, or records past its end, are exception 2. **FC21** writes
    them the same way, checks every sub-request before it writes any, and
    answers with the echo of the request; a later FC20 on any connection reads
    what was written. Feature `serverFileRecord`.
11. **FC24** answers with the queue at the FIFO pointer address: the byte
    count, the FIFO count and the values. An address the fixture has no queue
    for is exception 2. Reading does not empty the queue. Feature
    `serverFifoQueue`.
12. **Any other function code** from 1 to 127 that arrives with at least one
   data byte is answered with exception 1 (illegal function). Function code
   0, codes above 127 (no exception response can be encoded for them) and a
   request that is only a function code are outside the rules: a stack may
   answer or close the connection.
13. **State** is shared by all connections and is reset only by restarting the
   process.

## The baseline fixture

[fixtures/baseline/fixture.json](../fixtures/baseline/fixture.json) is
generated by `scripts/gen-fixture.py`, so its values follow rules a test can
recompute:

| Table | Addresses | Values |
|-------|-----------|--------|
| Coils | 0..1999 | `(i * 7 + 3) % 5 < 2` for index `i` |
| Discrete inputs | 1000..2999 | `(i * 11 + 1) % 3 == 0` |
| Holding registers | 0..255 | `0, 1, 32767, 32768, 65535, 4660, 43981, 255`, then `(i * 257 + 13) mod 65536` |
| Input registers | 65280..65535 | `65535, 0, 32768, 1`, then `(i * 263 + 7) mod 65536` |

The tables sit at different places on purpose: a stack that confuses two
tables, or addresses with 1-based register numbers, reads the wrong values or
gets an exception. The input registers end at the last address there is.
2000 coils and 256 registers allow the largest request of every function.

Report Server ID is server ID 42 with the run indicator on. File 1 has 200
records, `(i * 409 + 11) mod 65536`; file 2 has the records `1, 2, 3,
65535`. The FIFO queues are `440, 4740` at address 1246 (0x04DE, the
example of the specification), 31 values `(i * 1031 + 5) mod 65536` at
address 100, and an empty queue at address 101.

Unit identifier 1. The device identification objects are VendorName
`otfabric`, ProductCode `MBI-BASELINE`, MajorMinorRevision `1.0`, VendorUrl
`https://github.com/otfabric/modbus-interop`, ProductName `modbus-interop
baseline device`, ModelName `baseline` and UserApplicationName `interop`.

## Adding a fixture

1. Create `fixtures/<name>/fixture.json` with `"name": "<name>"`.
2. `make validate`.
3. Rebuild the images: fixtures are baked in.
4. If the fixture needs a behaviour the list above does not have, specify it
   here first and implement it in every published adapter.
