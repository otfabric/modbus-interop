# SPDX-License-Identifier: MIT
"""Fixture loading and validation.

The rules are those of fixtures/schema/modbus-fixture.schema.json and of
scripts/validate-fixtures.py, written out by hand so that the image needs no
JSON Schema package.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from pathlib import Path

TABLES = ("coils", "discreteInputs", "holdingRegisters", "inputRegisters")
_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
_PRINTABLE = re.compile(r"^[\x20-\x7e]*$")
_ROOT_KEYS = {
    "schemaVersion", "name", "description", "unitId", "deviceIdentification",
    "reportServerId", "fileRecords", "fifoQueues", *TABLES,
}
MAX_FILE_RECORDS = 10000
MAX_FIFO_VALUES = 31


class FixtureError(Exception):
    """The fixture is missing or invalid."""


@dataclass
class Block:
    """One table: the address of its first element and its initial values."""

    address: int
    values: list

    @property
    def end(self) -> int:
        """Address after the last element."""
        return self.address + len(self.values)

    def contains(self, address: int, count: int) -> bool:
        """Whether address .. address+count-1 lies entirely within the block."""
        return count >= 1 and self.address <= address and address + count <= self.end


@dataclass
class Fixture:
    """A validated fixture."""

    name: str
    unit_id: int
    tables: dict = field(default_factory=dict)  # table name -> Block (absent: no such table)
    objects: dict = field(default_factory=dict)  # object id -> str, ascending
    has_identification: bool = False
    server_id: tuple | None = None  # (serverId, runIndicator) of Report Server ID; None: not served
    files: dict = field(default_factory=dict)  # file number -> list of registers
    fifos: dict = field(default_factory=dict)  # FIFO pointer address -> list of registers, oldest first

    @property
    def conformity_level(self) -> int:
        """Highest category the fixture has objects for: 1 basic, 2 regular, 3 extended."""
        ids = list(self.objects)
        if any(i >= 0x80 for i in ids):
            return 3
        if any(3 <= i <= 6 for i in ids):
            return 2
        return 1


def _is_int(value) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _check_block(table: str, block, want_bool: bool) -> Block:
    if not isinstance(block, dict):
        raise FixtureError(f"{table}: must be an object")
    if set(block) != {"address", "values"}:
        raise FixtureError(f"{table}: must have exactly 'address' and 'values'")
    address, values = block["address"], block["values"]
    if not _is_int(address) or not 0 <= address <= 65535:
        raise FixtureError(f"{table}: address must be an integer 0..65535")
    if not isinstance(values, list) or not 1 <= len(values) <= 65536:
        raise FixtureError(f"{table}: values must be an array of 1..65536 elements")
    for i, value in enumerate(values):
        if want_bool:
            if not isinstance(value, bool):
                raise FixtureError(f"{table}: values[{i}] is not a boolean")
        elif not _is_int(value) or not 0 <= value <= 65535:
            raise FixtureError(f"{table}: values[{i}] is not an integer 0..65535")
    end = address + len(values) - 1
    if end > 65535:
        raise FixtureError(f"{table}: block ends at {end}, past address 65535")
    return Block(address, list(values))


def _check_identification(ident) -> dict:
    if not isinstance(ident, dict) or set(ident) != {"objects"}:
        raise FixtureError("deviceIdentification: must be an object with exactly 'objects'")
    objects = ident["objects"]
    if not isinstance(objects, list) or len(objects) < 3:
        raise FixtureError("deviceIdentification: objects must be an array of at least 3 elements")
    result: dict = {}
    ids = []
    for i, obj in enumerate(objects):
        if not isinstance(obj, dict) or set(obj) != {"id", "value"}:
            raise FixtureError(f"deviceIdentification: objects[{i}] must have exactly 'id' and 'value'")
        oid, value = obj["id"], obj["value"]
        if not _is_int(oid) or not 0 <= oid <= 255:
            raise FixtureError(f"deviceIdentification: objects[{i}].id must be an integer 0..255")
        if not isinstance(value, str) or not 1 <= len(value) <= 240 or not _PRINTABLE.match(value):
            raise FixtureError(f"deviceIdentification: objects[{i}].value must be 1..240 printable ASCII characters")
        ids.append(oid)
        result[oid] = value
    if ids != sorted(set(ids)):
        raise FixtureError("deviceIdentification: object ids must be unique and ascending")
    for basic in (0, 1, 2):
        if basic not in result:
            raise FixtureError(f"deviceIdentification: basic object {basic} is missing")
    for oid in ids:
        if 7 <= oid <= 127:
            raise FixtureError(f"deviceIdentification: object id {oid} is reserved")
    return result


def _registers(where: str, values, low: int, high: int) -> list:
    if not isinstance(values, list) or not low <= len(values) <= high:
        raise FixtureError(f"{where}: must be an array of {low}..{high} elements")
    for i, value in enumerate(values):
        if not _is_int(value) or not 0 <= value <= 65535:
            raise FixtureError(f"{where}[{i}] is not an integer 0..65535")
    return list(values)


def _check_server_id(value) -> tuple:
    if not isinstance(value, dict) or set(value) != {"serverId", "runIndicator"}:
        raise FixtureError("reportServerId: must be an object with exactly 'serverId' and 'runIndicator'")
    server_id, run = value["serverId"], value["runIndicator"]
    if not _is_int(server_id) or not 0 <= server_id <= 255:
        raise FixtureError("reportServerId: serverId must be an integer 0..255")
    if not isinstance(run, bool):
        raise FixtureError("reportServerId: runIndicator must be a boolean")
    return server_id, run


def _check_files(value) -> dict:
    if not isinstance(value, list) or not value:
        raise FixtureError("fileRecords: must be an array of at least 1 element")
    result: dict = {}
    for i, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != {"file", "records"}:
            raise FixtureError(f"fileRecords[{i}]: must have exactly 'file' and 'records'")
        number = item["file"]
        if not _is_int(number) or not 1 <= number <= 65535:
            raise FixtureError(f"fileRecords[{i}].file must be an integer 1..65535")
        if number in result:
            raise FixtureError("fileRecords: file numbers must be unique")
        result[number] = _registers(f"fileRecords[{i}].records", item["records"], 1, MAX_FILE_RECORDS)
    return result


def _check_fifos(value) -> dict:
    if not isinstance(value, list) or not value:
        raise FixtureError("fifoQueues: must be an array of at least 1 element")
    result: dict = {}
    for i, item in enumerate(value):
        if not isinstance(item, dict) or set(item) != {"address", "values"}:
            raise FixtureError(f"fifoQueues[{i}]: must have exactly 'address' and 'values'")
        address = item["address"]
        if not _is_int(address) or not 0 <= address <= 65535:
            raise FixtureError(f"fifoQueues[{i}].address must be an integer 0..65535")
        if address in result:
            raise FixtureError("fifoQueues: addresses must be unique")
        result[address] = _registers(f"fifoQueues[{i}].values", item["values"], 0, MAX_FIFO_VALUES)
    return result


def load(path: str) -> Fixture:
    """Load and validate a fixture; raises FixtureError."""
    file = Path(path)
    try:
        doc = json.loads(file.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise FixtureError(f"{path}: {exc}") from exc
    if not isinstance(doc, dict):
        raise FixtureError("the fixture must be a JSON object")
    unknown = set(doc) - _ROOT_KEYS
    if unknown:
        raise FixtureError(f"unknown field(s): {', '.join(sorted(unknown))}")
    if doc.get("schemaVersion") != "1.0":
        raise FixtureError("schemaVersion must be \"1.0\"")
    name = doc.get("name")
    if not isinstance(name, str) or not _NAME.match(name):
        raise FixtureError("name must match ^[a-z0-9][a-z0-9-]*$")
    if "description" in doc and not isinstance(doc["description"], str):
        raise FixtureError("description must be a string")
    unit_id = doc.get("unitId")
    if not _is_int(unit_id) or not 1 <= unit_id <= 247:
        raise FixtureError("unitId must be an integer 1..247")
    directory = file.resolve().parent.name
    if name != directory:
        raise FixtureError(f"name {name!r} is not the directory name {directory!r}")

    fixture = Fixture(name=name, unit_id=unit_id)
    for table in TABLES:
        if table in doc:
            fixture.tables[table] = _check_block(table, doc[table], table in ("coils", "discreteInputs"))
    if "deviceIdentification" in doc:
        fixture.objects = _check_identification(doc["deviceIdentification"])
        fixture.has_identification = True
    if "reportServerId" in doc:
        fixture.server_id = _check_server_id(doc["reportServerId"])
    if "fileRecords" in doc:
        fixture.files = _check_files(doc["fileRecords"])
    if "fifoQueues" in doc:
        fixture.fifos = _check_fifos(doc["fifoQueues"])
    return fixture
