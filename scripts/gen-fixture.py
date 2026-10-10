#!/usr/bin/env python3
"""Regenerates fixtures/baseline/fixture.json.

The values follow the rules documented in docs/FIXTURES.md, so that a test can
recompute them. Run from the repository root:

    python3 scripts/gen-fixture.py
"""
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent

coils = [(i * 7 + 3) % 5 < 2 for i in range(2000)]
discrete = [(i * 11 + 1) % 3 == 0 for i in range(2000)]
holding = [0, 1, 32767, 32768, 65535, 4660, 43981, 255]
holding += [(i * 257 + 13) % 65536 for i in range(len(holding), 256)]
inputs = [65535, 0, 32768, 1]
inputs += [(i * 263 + 7) % 65536 for i in range(len(inputs), 256)]

fixture = {
    "schemaVersion": "1.0",
    "name": "baseline",
    "description": "One block per table at a different place in the address space; "
                   "large enough for the biggest request of every function.",
    "unitId": 1,
    "coils": {"address": 0, "values": coils},
    "discreteInputs": {"address": 1000, "values": discrete},
    "holdingRegisters": {"address": 0, "values": holding},
    "inputRegisters": {"address": 65280, "values": inputs},
    "deviceIdentification": {
        "objects": [
            {"id": 0, "value": "otfabric"},
            {"id": 1, "value": "MBI-BASELINE"},
            {"id": 2, "value": "1.0"},
            {"id": 3, "value": "https://github.com/otfabric/modbus-interop"},
            {"id": 4, "value": "modbus-interop baseline device"},
            {"id": 5, "value": "baseline"},
            {"id": 6, "value": "interop"},
        ]
    },
}


def dump(value, indent=0):
    """JSON with one line per table: objects are indented, arrays of scalars are not."""
    pad = "  " * indent
    if isinstance(value, dict):
        items = [f'{pad}  {json.dumps(k)}: {dump(v, indent + 1)}' for k, v in value.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    if isinstance(value, list) and value and isinstance(value[0], dict):
        items = [f"{pad}  {json.dumps(v)}" for v in value]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    return json.dumps(value)


path = ROOT / "fixtures" / "baseline" / "fixture.json"
path.write_text(dump(fixture) + "\n")
print(f"wrote {path.relative_to(ROOT)}")
