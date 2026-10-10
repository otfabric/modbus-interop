#!/usr/bin/env python3
"""Validate every fixtures/*/fixture.json.

Two layers, both required:

  1. The structural rules of fixtures/schema/modbus-fixture.schema.json. They
     are checked with the `jsonschema` package when it is installed; CI installs
     it, so a missing package is an error there (--require-schema).
  2. The cross-field rules a JSON Schema cannot express: the name matches the
     directory, a block ends at or before address 65535, device identification
     objects are unique, in ascending order and include the basic ones.

The adapters apply the same rules when they load a fixture; this script makes
a broken fixture fail before any image is built.

Usage: validate-fixtures.py [--require-schema] [fixture.json ...]
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "fixtures" / "schema" / "modbus-fixture.schema.json"
TABLES = ("coils", "discreteInputs", "holdingRegisters", "inputRegisters")


def semantic_errors(fx, path):
    errors = []
    if fx.get("name") != path.parent.name:
        errors.append(f"name {fx.get('name')!r} is not the directory name {path.parent.name!r}")
    for table in TABLES:
        block = fx.get(table)
        if not isinstance(block, dict):
            continue
        end = block.get("address", 0) + len(block.get("values", [])) - 1
        if end > 65535:
            errors.append(f"{table}: block ends at {end}, past address 65535")
    ident = fx.get("deviceIdentification")
    if isinstance(ident, dict):
        ids = [o.get("id") for o in ident.get("objects", [])]
        if ids != sorted(set(ids)):
            errors.append("deviceIdentification: object ids must be unique and ascending")
        for basic in (0, 1, 2):
            if basic not in ids:
                errors.append(f"deviceIdentification: basic object {basic} is missing")
        for i in ids:
            if isinstance(i, int) and 7 <= i <= 127:
                errors.append(f"deviceIdentification: object id {i} is reserved")
    return errors


def main(argv):
    require_schema = "--require-schema" in argv
    paths = [Path(a) for a in argv if not a.startswith("--")]
    if not paths:
        paths = sorted((ROOT / "fixtures").glob("*/fixture.json"))
    if not paths:
        print("no fixtures found", file=sys.stderr)
        return 1

    validator = None
    try:
        import jsonschema

        schema = json.loads(SCHEMA.read_text())
        jsonschema.Draft202012Validator.check_schema(schema)
        validator = jsonschema.Draft202012Validator(schema)
    except ImportError:
        if require_schema:
            print("the jsonschema package is required (pip install jsonschema)", file=sys.stderr)
            return 1
        print("note: jsonschema is not installed; only the cross-field rules are checked", file=sys.stderr)

    failed = 0
    for path in paths:
        try:
            fx = json.loads(path.read_text())
        except (OSError, ValueError) as e:
            print(f"FAIL {path}: {e}")
            failed += 1
            continue
        errors = []
        if validator is not None:
            for e in validator.iter_errors(fx):
                where = "/".join(str(x) for x in e.absolute_path) or "(root)"
                errors.append(f"{where}: {e.message}")
        if not errors or validator is None:
            errors += semantic_errors(fx, path.resolve())
        if errors:
            failed += 1
            for e in errors:
                print(f"FAIL {path}: {e}")
        else:
            print(f"ok   {path.relative_to(ROOT) if path.is_absolute() else path}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
