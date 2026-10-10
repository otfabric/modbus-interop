# SPDX-License-Identifier: MIT
"""Command line of the adapter: the container contract."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import re
import sys

from . import ADAPTER, SCHEMA_VERSION

FIXTURES_DIR = "/fixtures"
BUILD_INFO = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "build-info.json")
_NUMBER = re.compile(r"^(0[xX][0-9a-fA-F]+|[0-9]+)$")
_FIXTURE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
_TRUE = {"true", "1", "on"}
_FALSE = {"false", "0", "off"}

CLIENT_OPERATIONS = [
    "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
    "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
    "mask-write-register", "read-write-multiple-registers", "read-device-identification", "raw",
]

USAGE = """usage:
  modbus-interop server [--fixture PATH] [--bind-address ADDR] [--port N] [--ready-file PATH]
  modbus-interop client <operation> --host HOST [--port N] [--unit-id N]
                        [--connect-timeout-ms N] [--timeout-ms N] [--repeat N] [operation flags]
  modbus-interop print-capabilities
  modbus-interop print-fixture [NAME]
  modbus-interop version
operations: """ + ", ".join(CLIENT_OPERATIONS)


class UsageError(Exception):
    """Exit 2: nothing was sent."""


def number(flag: str, text: str, low: int, high: int) -> int:
    """A decimal number, or a hexadecimal one with a 0x prefix, within low..high."""
    if not _NUMBER.match(text):
        raise UsageError(f"{flag}: {text!r} is not a number")
    value = int(text, 16) if text[:2].lower() == "0x" else int(text, 10)
    if not low <= value <= high:
        raise UsageError(f"{flag}: {value} is not within {low}..{high}")
    return value


def boolean(flag: str, text: str) -> bool:
    lowered = text.lower()
    if lowered in _TRUE:
        return True
    if lowered in _FALSE:
        return False
    raise UsageError(f"{flag}: {text!r} is not true/false, 1/0 or on/off")


def flags(args: list[str], known: set[str]) -> dict[str, str]:
    """Parse `--name value` pairs; anything else is a usage error."""
    result: dict[str, str] = {}
    i = 0
    while i < len(args):
        name = args[i]
        if not name.startswith("--") or name[2:] not in known:
            raise UsageError(f"unknown flag or argument {name!r}")
        if i + 1 >= len(args):
            raise UsageError(f"{name} needs a value")
        if name[2:] in result:
            raise UsageError(f"{name} is given more than once")
        result[name[2:]] = args[i + 1]
        i += 2
    return result


def required(given: dict[str, str], name: str) -> str:
    if name not in given:
        raise UsageError(f"--{name} is required")
    return given[name]


def value_list(flag: str, text: str, convert, low: int, high: int) -> list:
    items = text.split(",") if text else []
    if not low <= len(items) <= high:
        raise UsageError(f"{flag}: {len(items)} values, allowed are {low}..{high}")
    return [convert(flag, item) for item in items]


def register(flag: str, text: str) -> int:
    return number(flag, text, 0, 65535)


def build_info() -> dict:
    try:
        with open(BUILD_INFO, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return {}


def upstream_version() -> str:
    import pymodbus  # pylint: disable=import-outside-toplevel

    return str(pymodbus.__version__)


def capabilities() -> dict:
    info = build_info()
    return {
        "schemaVersion": SCHEMA_VERSION,
        "adapter": ADAPTER,
        "adapterVersion": info.get("adapterVersion", "dev"),
        "protocol": "modbus-tcp",
        "upstream": {
            "name": "PyModbus",
            "version": upstream_version(),
            "revision": info.get("upstreamRevision", "unknown"),
            "license": "BSD-3-Clause",
            "language": "Python",
            "independentEngine": True,
        },
        "roles": {"server": True, "client": True},
        "features": {
            "serverUnitIdFiltering": True,
            # PyModbus rejects a quantity of 0 or above the maximum of FC01..FC04
            # and FC15 while decoding and answers with function code 0x80 and
            # exception 1, not with exception 3.
            "serverQuantityCheck": False,
            "serverMaskWrite": True,
            "serverReadWriteMultiple": True,
            "serverDeviceIdentification": True,
            "requestEvents": True,
            "connectionEvents": True,
            "clientMaskWrite": True,
            "clientReadWriteMultiple": True,
            "clientDeviceIdentification": True,
            "clientRaw": True,
            "clientRepeat": True,
            "clientUnitIdCheck": True,
        },
        "serverFunctions": [1, 2, 3, 4, 5, 6, 15, 16, 22, 23, 43],
        "clientOperations": CLIENT_OPERATIONS,
    }


def print_json(document: dict) -> None:
    sys.stdout.write(json.dumps(document, separators=(",", ":")) + "\n")
    sys.stdout.flush()


def command_server(args: list[str]) -> int:
    given = flags(args, {"fixture", "bind-address", "port", "ready-file"})
    fixture = given.get("fixture", f"{FIXTURES_DIR}/baseline/fixture.json")
    bind_address = given.get("bind-address", "0.0.0.0")
    port = number("--port", given.get("port", "1502"), 1, 65535)
    ready_file = given.get("ready-file", "/run/modbus-interop/ready")
    if not fixture or not bind_address or not ready_file:
        raise UsageError("empty value")
    from . import server  # pylint: disable=import-outside-toplevel

    return server.run(fixture, bind_address, port, ready_file)


def client_params(operation: str, args: list[str]) -> dict:
    """Validate everything before any network access."""
    common = {"host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat"}
    specific = {
        "read-coils": {"address", "quantity"},
        "read-discrete-inputs": {"address", "quantity"},
        "read-holding-registers": {"address", "quantity"},
        "read-input-registers": {"address", "quantity"},
        "write-single-coil": {"address", "value"},
        "write-single-register": {"address", "value"},
        "write-multiple-coils": {"address", "values"},
        "write-multiple-registers": {"address", "values"},
        "mask-write-register": {"address", "and-mask", "or-mask"},
        "read-write-multiple-registers": {"read-address", "read-quantity", "write-address", "values"},
        "read-device-identification": {"category"},
        "raw": {"function", "data"},
    }[operation]
    given = flags(args, common | specific)
    host = required(given, "host")
    if not host:
        raise UsageError("--host is empty")
    params: dict = {
        "host": host,
        "port": number("--port", given.get("port", "1502"), 1, 65535),
        "unit_id": number("--unit-id", given.get("unit-id", "1"), 0, 255),
        "connect_timeout_ms": number("--connect-timeout-ms", given.get("connect-timeout-ms", "5000"), 1, 3600000),
        "timeout_ms": number("--timeout-ms", given.get("timeout-ms", "5000"), 1, 3600000),
        "repeat": number("--repeat", given.get("repeat", "1"), 1, 1000),
    }

    def address(name: str = "address") -> int:
        return number(f"--{name}", required(given, name), 0, 65535)

    if operation in ("read-coils", "read-discrete-inputs"):
        params["address"] = address()
        params["quantity"] = number("--quantity", required(given, "quantity"), 1, 2000)
    elif operation in ("read-holding-registers", "read-input-registers"):
        params["address"] = address()
        params["quantity"] = number("--quantity", required(given, "quantity"), 1, 125)
    elif operation == "write-single-coil":
        params["address"] = address()
        params["value"] = boolean("--value", required(given, "value"))
    elif operation == "write-single-register":
        params["address"] = address()
        params["value"] = register("--value", required(given, "value"))
    elif operation == "write-multiple-coils":
        params["address"] = address()
        params["values"] = value_list("--values", required(given, "values"), boolean, 1, 1968)
    elif operation == "write-multiple-registers":
        params["address"] = address()
        params["values"] = value_list("--values", required(given, "values"), register, 1, 123)
    elif operation == "mask-write-register":
        params["address"] = address()
        params["and_mask"] = register("--and-mask", required(given, "and-mask"))
        params["or_mask"] = register("--or-mask", required(given, "or-mask"))
    elif operation == "read-write-multiple-registers":
        params["read_address"] = address("read-address")
        params["read_quantity"] = number("--read-quantity", required(given, "read-quantity"), 1, 125)
        params["write_address"] = address("write-address")
        params["values"] = value_list("--values", required(given, "values"), register, 1, 121)
    elif operation == "read-device-identification":
        category = given.get("category", "basic")
        if category not in ("basic", "regular", "extended"):
            raise UsageError(f"--category: {category!r} is not basic, regular or extended")
        params["category"] = category
    elif operation == "raw":
        params["function"] = number("--function", required(given, "function"), 1, 127)
        data = required(given, "data")
        if len(data) % 2 or not re.fullmatch(r"[0-9a-fA-F]*", data):
            raise UsageError("--data: not a string of hexadecimal byte pairs")
        if len(data) // 2 > 252:
            raise UsageError("--data: a PDU has at most 252 bytes after the function code")
        params["data"] = bytes.fromhex(data)
    return params


def command_client(args: list[str]) -> int:
    if not args:
        raise UsageError("client needs an operation")
    operation = args[0]
    if operation not in CLIENT_OPERATIONS:
        raise UsageError(f"unknown operation {operation!r}")
    params = client_params(operation, args[1:])
    from . import client  # pylint: disable=import-outside-toplevel

    async def bounded() -> None:
        document, code = await client.execute(operation, params)
        print_json(document)
        # Leave from inside the loop: closing it would wait for helper
        # threads, and a name lookup that is still running must not outlive
        # the timeouts of the operation.
        sys.stderr.flush()
        os._exit(code)

    try:
        asyncio.run(bounded())
        raise RuntimeError("the operation ended without a result")
    except BaseException as exc:  # pylint: disable=broad-except
        # Never without a result document once the arguments were accepted.
        document = {
            "schemaVersion": SCHEMA_VERSION, "adapter": ADAPTER, "operation": operation, "ok": False,
            "error": {"code": "failed", "message": f"{type(exc).__name__}: {exc}"},
            "connected": False, "unitId": params["unit_id"], "requests": 0, "responses": 0,
            "response": None, "exception": None, "elapsedMs": 0,
        }
        code = 1
    print_json(document)
    return code


def command_print_fixture(args: list[str]) -> int:
    if len(args) > 1:
        raise UsageError("print-fixture takes at most one name")
    name = args[0] if args else "baseline"
    if not _FIXTURE_NAME.match(name):
        print(f"modbus-interop: no fixture named {name!r}", file=sys.stderr)
        return 1
    try:
        with open(os.path.join(FIXTURES_DIR, name, "fixture.json"), "rb") as handle:
            data = handle.read()
    except OSError as exc:
        print(f"modbus-interop: no fixture named {name!r}: {exc}", file=sys.stderr)
        return 1
    sys.stdout.buffer.write(data)
    sys.stdout.buffer.flush()
    return 0


def main(argv: list[str]) -> int:
    # Library logging and diagnostics go to stderr; stdout carries JSON only.
    level = os.environ.get("MODBUS_INTEROP_LOG_LEVEL", "WARNING").upper()
    logging.basicConfig(stream=sys.stderr, level=getattr(logging, level, logging.WARNING),
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        if not argv:
            raise UsageError("a command is required")
        command, args = argv[0], argv[1:]
        if command == "server":
            return command_server(args)
        if command == "client":
            return command_client(args)
        if command == "print-capabilities":
            if args:
                raise UsageError("print-capabilities takes no arguments")
            print_json(capabilities())
            return 0
        if command == "print-fixture":
            return command_print_fixture(args)
        if command == "version":
            if args:
                raise UsageError("version takes no arguments")
            info = build_info()
            print(f"modbus-interop {ADAPTER} {info.get('adapterVersion', 'dev')} "
                  f"(PyModbus {upstream_version()}, {info.get('upstreamRevision', 'unknown')})")
            return 0
        raise UsageError(f"unknown command {command!r}")
    except UsageError as exc:
        print(f"modbus-interop: {exc}", file=sys.stderr)
        print(USAGE, file=sys.stderr)
        return 2
