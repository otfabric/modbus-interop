# SPDX-License-Identifier: MIT
"""The `client` command: one bounded operation with PyModbus' asynchronous TCP client.

Requests are built, sent, matched and decoded by PyModbus (the per-function
methods of AsyncModbusTcpClient; a registered custom PDU class for `raw`).
The adapter turns the returned PDU, or the exception PyModbus raised, into
the result document. Retries and reconnects of the library are switched off:
one request is one request.
"""

from __future__ import annotations

import asyncio
import time

from pymodbus.client import AsyncModbusTcpClient
from pymodbus.exceptions import ConnectionException, ModbusException, ModbusIOException
from pymodbus.pdu import ModbusPDU
from pymodbus.pdu.diag_message import DiagnosticBase
from pymodbus.pdu.file_message import FileRecord

from . import ADAPTER, SCHEMA_VERSION

FUNCTIONS = {
    "read-coils": 1,
    "read-discrete-inputs": 2,
    "read-holding-registers": 3,
    "read-input-registers": 4,
    "write-single-coil": 5,
    "write-single-register": 6,
    "write-multiple-coils": 15,
    "write-multiple-registers": 16,
    "mask-write-register": 22,
    "read-write-multiple-registers": 23,
    "read-device-identification": 43,
    "diagnostics": 8,
    "report-server-id": 17,
    "read-file-record": 20,
    "write-file-record": 21,
    "read-fifo-queue": 24,
}
RETURN_QUERY_DATA = 0x0000
RUN_INDICATOR_ON = 0xFF
MBAP_SIZE = 7
MAX_FIFO_COUNT = 31
CATEGORIES = {"basic": 1, "regular": 2, "extended": 3}
MORE_FOLLOWS = 0xFF
MAX_IDENTIFICATION_REQUESTS = 256  # one per object at worst: the stream cannot loop forever


class Failure(Exception):
    """An operation failed with one of the contract's error codes."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class DeviceException(Exception):
    """The device answered with an exception response."""

    def __init__(self, function: int, code: int) -> None:
        super().__init__(f"exception response {code} (function {function})")
        self.function = function
        self.code = code


def raw_pdu_class(function: int) -> type[ModbusPDU]:
    """A PDU class that carries the bytes after the function code unchanged."""

    class RawPDU(ModbusPDU):
        function_code = function

        def __init__(self, data: bytes = b"", dev_id: int = 1, transaction_id: int = 0) -> None:
            super().__init__(dev_id=dev_id, transaction_id=transaction_id)
            self.data = data

        def encode(self) -> bytes:
            return self.data

        def decode(self, data: bytes) -> None:
            self.data = bytes(data)

    return RawPDU


class Session:
    """One connection and the requests of one invocation on it."""

    def __init__(self, operation: str, params: dict) -> None:
        self.operation = operation
        self.params = params
        self.unit_id = params["unit_id"]
        self.requests = 0
        self.responses = 0
        self.connected = False
        self.received = 0  # bytes received since the last request was sent
        self.receive_buffer = b""  # what PyModbus last framed from: the response is at its start
        self.lost = asyncio.Event()
        self.client: AsyncModbusTcpClient | None = None
        self.raw_class = raw_pdu_class(params["function"]) if operation == "raw" else None

    # --- trace hooks of PyModbus -------------------------------------------------
    def on_packet(self, sending: bool, data: bytes) -> bytes:
        if not sending:
            self.received += len(data)
            self.receive_buffer = bytes(data)
        return data

    def on_connect(self, connected: bool) -> None:
        if not connected:
            self.lost.set()

    # --- connection --------------------------------------------------------------
    async def connect(self) -> None:
        connect_timeout = self.params["connect_timeout_ms"] / 1000.0
        self.client = AsyncModbusTcpClient(
            self.params["host"],
            port=self.params["port"],
            timeout=connect_timeout,
            retries=0,  # one request is one request
            reconnect_delay=0,  # no automatic reconnect
            reconnect_delay_max=0,
            trace_packet=self.on_packet,
            trace_connect=self.on_connect,
        )
        if self.raw_class is not None:
            self.client.register(self.raw_class)
        try:
            ok = await self.client.connect()
        except (OSError, asyncio.TimeoutError, ModbusException) as exc:
            raise Failure("connect-failed", f"cannot connect: {exc}") from exc
        if not ok or not self.client.connected:
            raise Failure("connect-failed", f"cannot connect to {self.params['host']}:{self.params['port']}")
        self.connected = True
        # PyModbus has one timeout for connecting and for the wait for a
        # response. The contract has two, so the value is changed once the
        # connection is up.
        self.client.ctx.comm_params.timeout_connect = self.params["timeout_ms"] / 1000.0

    def close(self) -> None:
        if self.client is not None:
            self.client.close()

    # --- one request -------------------------------------------------------------
    async def exchange(self, function: int, call) -> ModbusPDU:
        """Send one request and return the normal response PDU."""
        assert self.client is not None
        if self.lost.is_set() or not self.client.connected:
            raise Failure("connection-lost", "the connection is closed")
        self.received = 0
        self.receive_buffer = b""
        try:
            coroutine = call()
        except ConnectionException as exc:
            raise Failure("connection-lost", str(exc)) from exc
        self.requests += 1
        task = asyncio.ensure_future(coroutine)
        lost = asyncio.ensure_future(self.lost.wait())
        try:
            await asyncio.wait({task, lost}, return_when=asyncio.FIRST_COMPLETED)
            if not task.done():
                # The connection ended first. Let a response that arrived in
                # the same turn of the loop win.
                for _ in range(3):
                    await asyncio.sleep(0)
                    if task.done():
                        break
            if not task.done():
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                raise self.no_response("the connection closed before a response arrived")
            try:
                response = task.result()
            except ModbusIOException as exc:
                raise self.no_response(str(exc)) from exc
            except ConnectionException as exc:
                raise Failure("connection-lost", str(exc)) from exc
            except asyncio.CancelledError as exc:
                raise Failure("failed", "the request was cancelled") from exc
            except (ModbusException, ValueError, TypeError) as exc:
                raise Failure("failed", f"{type(exc).__name__}: {exc}") from exc
        finally:
            lost.cancel()
        if response is None:
            raise Failure("failed", "the library returned no response")
        if response.isError():
            if response.function_code != (function | 0x80):
                raise Failure(
                    "invalid-response",
                    f"exception response for function {response.function_code & 0x7F}, request was function {function}",
                )
            raise DeviceException(int(response.function_code), int(response.exception_code))
        if response.function_code != function:
            raise Failure(
                "invalid-response",
                f"response has function {response.function_code}, request was function {function}",
            )
        self.responses += 1
        return response

    def no_response(self, detail: str) -> Failure:
        """PyModbus gave up waiting: say why.

        PyModbus drops a frame whose unit or transaction identifier is not
        the request's, and closes the connection on a frame it cannot decode;
        both end as "no response". Bytes that did arrive tell these apart
        from a device that stayed silent.
        """
        if self.received:
            return Failure(
                "invalid-response",
                f"{self.received} byte(s) arrived but PyModbus did not accept them as the response: {detail}",
            )
        if self.lost.is_set():
            return Failure("connection-lost", f"the connection closed during the operation: {detail}")
        return Failure("timeout", f"no response within {self.params['timeout_ms']} ms: {detail}")

    # --- operations --------------------------------------------------------------
    async def perform(self) -> dict:
        """Run the operation once and return its `response` object."""
        p, c, unit = self.params, self.client, self.unit_id
        op = self.operation
        assert c is not None
        if op in ("read-coils", "read-discrete-inputs"):
            function = FUNCTIONS[op]
            method = c.read_coils if function == 1 else c.read_discrete_inputs
            r = await self.exchange(function, lambda: method(p["address"], count=p["quantity"], device_id=unit))
            # PyModbus returns whole bytes; the response must have exactly the
            # bytes the quantity needs.
            self.check(len(r.bits) == (p["quantity"] + 7) // 8 * 8, f"{len(r.bits)} bits for a quantity of {p['quantity']}")
            return {"function": function, "bits": [bool(b) for b in r.bits[: p["quantity"]]]}
        if op in ("read-holding-registers", "read-input-registers"):
            function = FUNCTIONS[op]
            method = c.read_holding_registers if function == 3 else c.read_input_registers
            r = await self.exchange(function, lambda: method(p["address"], count=p["quantity"], device_id=unit))
            self.check(len(r.registers) == p["quantity"], f"{len(r.registers)} registers for a quantity of {p['quantity']}")
            return {"function": function, "registers": [int(v) for v in r.registers]}
        if op == "write-single-coil":
            r = await self.exchange(5, lambda: c.write_coil(p["address"], p["value"], device_id=unit))
            self.check(r.address == p["address"] and len(r.bits) >= 1 and bool(r.bits[0]) == p["value"],
                       "the response does not echo the request")
            return {"function": 5, "address": r.address, "value": bool(r.bits[0])}
        if op == "write-single-register":
            r = await self.exchange(6, lambda: c.write_register(p["address"], p["value"], device_id=unit))
            self.check(r.address == p["address"] and len(r.registers) >= 1 and r.registers[0] == p["value"],
                       "the response does not echo the request")
            return {"function": 6, "address": r.address, "value": int(r.registers[0])}
        if op == "write-multiple-coils":
            r = await self.exchange(15, lambda: c.write_coils(p["address"], list(p["values"]), device_id=unit))
            self.check(r.address == p["address"] and r.count == len(p["values"]), "the response does not echo the request")
            return {"function": 15, "address": r.address, "quantity": r.count}
        if op == "write-multiple-registers":
            r = await self.exchange(16, lambda: c.write_registers(p["address"], list(p["values"]), device_id=unit))
            self.check(r.address == p["address"] and r.count == len(p["values"]), "the response does not echo the request")
            return {"function": 16, "address": r.address, "quantity": r.count}
        if op == "mask-write-register":
            r = await self.exchange(22, lambda: c.mask_write_register(
                address=p["address"], and_mask=p["and_mask"], or_mask=p["or_mask"], device_id=unit))
            self.check(r.address == p["address"] and r.and_mask == p["and_mask"] and r.or_mask == p["or_mask"],
                       "the response does not echo the request")
            return {"function": 22, "address": r.address, "andMask": r.and_mask, "orMask": r.or_mask}
        if op == "read-write-multiple-registers":
            r = await self.exchange(23, lambda: c.readwrite_registers(
                read_address=p["read_address"], read_count=p["read_quantity"],
                write_address=p["write_address"], values=list(p["values"]), device_id=unit))
            self.check(len(r.registers) == p["read_quantity"],
                       f"{len(r.registers)} registers for a quantity of {p['read_quantity']}")
            return {"function": 23, "registers": [int(v) for v in r.registers]}
        if op == "read-device-identification":
            return await self.read_device_identification()
        if op == "diagnostics":
            return await self.diagnostics()
        if op == "report-server-id":
            r = await self.exchange(17, lambda: c.report_device_id(device_id=unit))
            # PyModbus hands out the bytes between the byte count and the
            # last byte as identifier and the last byte as a boolean status.
            identifier = bytes(r.identifier)
            return {"function": 17, "data": (identifier + bytes([RUN_INDICATOR_ON if r.status else 0])).hex()}
        if op == "read-file-record":
            count = p["length"]
            # FileRecord takes record_length in bytes.
            request = FileRecord(file_number=p["file"], record_number=p["record"], record_length=2 * count)
            r = await self.exchange(20, lambda: c.read_file_record([request], device_id=unit))
            self.check(len(r.records) == 1, f"{len(r.records)} sub-responses to one sub-request")
            data = bytes(r.records[0].record_data)
            self.check(len(data) == 2 * count, f"{len(data)} bytes of record data for {count} registers")
            return {"function": 20, "registers": [int.from_bytes(data[i:i + 2], "big") for i in range(0, len(data), 2)]}
        if op == "write-file-record":
            data = b"".join(v.to_bytes(2, "big") for v in p["values"])
            request = FileRecord(file_number=p["file"], record_number=p["record"], record_data=data)
            r = await self.exchange(21, lambda: c.write_file_record([request], device_id=unit))
            echo = r.records[0] if len(r.records) == 1 else None
            self.check(echo is not None and echo.file_number == p["file"] and echo.record_number == p["record"]
                       and echo.record_length == len(p["values"]) and bytes(echo.record_data) == data,
                       "the response does not echo the request")
            return {"function": 21, "file": echo.file_number, "record": echo.record_number, "quantity": echo.record_length}
        if op == "read-fifo-queue":
            r = await self.exchange(24, lambda: c.read_fifo_queue(address=p["address"], device_id=unit))
            values = [int(v) for v in r.values]
            # PyModbus' decoder reads the FIFO count and ignores the byte
            # count and the limit of 31: both are checked here, on the frame
            # PyModbus decoded.
            pdu = self.response_pdu()
            self.check(len(values) <= MAX_FIFO_COUNT, f"FIFO count {len(values)} is above {MAX_FIFO_COUNT}")
            self.check(pdu is not None and len(pdu) == 5 + 2 * len(values)
                       and int.from_bytes(pdu[1:3], "big") == 2 + 2 * len(values),
                       "the byte count or the length of the response does not match the FIFO count")
            return {"function": 24, "registers": values}
        if op == "raw":
            assert self.raw_class is not None
            request = self.raw_class(p["data"], dev_id=unit)
            r = await self.exchange(p["function"], lambda: c.execute(False, request))
            data = getattr(r, "data", None)
            self.check(isinstance(data, (bytes, bytearray)), "the response was not decoded as a raw PDU")
            return {"function": p["function"], "data": bytes(data).hex()}
        raise Failure("failed", f"operation {op} is not implemented")

    def response_pdu(self) -> bytes | None:
        """The PDU of the frame the response was decoded from, by its MBAP length."""
        buffer = self.receive_buffer
        if len(buffer) < MBAP_SIZE + 1:
            return None
        end = 6 + int.from_bytes(buffer[4:6], "big")
        return buffer[MBAP_SIZE:end] if len(buffer) >= end else None

    async def diagnostics(self) -> dict:
        """FC08 with any sub-function: Return Query Data is the library's
        own method, any other sub-function its DiagnosticBase with the
        sub-function set."""
        c, p, unit = self.client, self.params, self.unit_id
        assert c is not None
        sub, data = p["sub_function"], p["data"]
        if sub == RETURN_QUERY_DATA:
            r = await self.exchange(8, lambda: c.diag_query_data(data, device_id=unit))
        else:
            request = DiagnosticBase(message=data, dev_id=unit)
            request.sub_function_code = sub
            r = await self.exchange(8, lambda: c.execute(False, request))
        self.check(r.sub_function_code == sub,
                   f"the response is for sub-function {r.sub_function_code}, request was {sub}")
        message = r.message
        if isinstance(message, (bytes, bytearray)):
            answer = bytes(message)
        elif isinstance(message, int):
            answer = message.to_bytes(2, "big")
        elif isinstance(message, (list, tuple)):
            answer = b"".join(int(v).to_bytes(2, "big") for v in message)
        else:
            answer = b""
        # PyModbus decodes the data of other sub-functions as registers and
        # pads an odd length; the frame says how many bytes there were.
        pdu = self.response_pdu()
        if pdu is not None and sub != RETURN_QUERY_DATA:
            answer = answer[: max(len(pdu) - 3, 0)]
        if sub == RETURN_QUERY_DATA:
            self.check(answer == data, "the response does not echo the query data")
        return {"function": 8, "subFunction": int(r.sub_function_code), "data": answer.hex()}

    def check(self, condition: bool, message: str) -> None:
        """A response PyModbus accepted but that does not answer the request."""
        if not condition:
            self.responses -= 1
            raise Failure("invalid-response", message)

    async def read_device_identification(self) -> dict:
        """FC43/14, stream access from object 0, following "more follows"."""
        c, unit = self.client, self.unit_id
        assert c is not None
        read_code = CATEGORIES[self.params["category"]]
        objects: list[dict] = []
        conformity = 0
        object_id = 0
        for _ in range(MAX_IDENTIFICATION_REQUESTS):
            oid = object_id
            r = await self.exchange(43, lambda: c.read_device_information(read_code=read_code, object_id=oid, device_id=unit))
            self.check(hasattr(r, "information") and getattr(r, "sub_function_code", None) == 0x0E,
                       "the response is not a Read Device Identification response")
            self.check(r.read_code == read_code, f"the response is for read code {r.read_code}, request was {read_code}")
            conformity = int(r.conformity)
            for key, value in r.information.items():
                for item in value if isinstance(value, list) else [value]:
                    text = item.decode("latin-1") if isinstance(item, (bytes, bytearray)) else str(item)
                    objects.append({"id": int(key), "value": text})
            if r.more_follows != MORE_FOLLOWS:
                break
            self.check(r.next_object_id > object_id, "\"more follows\" does not advance")
            object_id = r.next_object_id
        else:
            raise Failure("invalid-response", "the device never ends the object stream")
        objects.sort(key=lambda o: o["id"])
        return {"function": 43, "conformityLevel": conformity, "objects": objects}


async def execute(operation: str, params: dict) -> tuple[dict, int]:
    """Run the operation; returns the result document and the exit code."""
    started = time.monotonic()
    session = Session(operation, params)
    response = None
    exception = None
    error = None
    try:
        await session.connect()
        for _ in range(params["repeat"]):
            current = await session.perform()
            if response is not None and current != response and error is None:
                error = {"code": "failed", "message": "the responses to the repeated request differ"}
            response = current
    except DeviceException as exc:
        response = None
        exception = {"function": exc.function, "code": exc.code}
        error = {"code": "exception", "message": f"the device answered with exception {exc.code}"}
    except Failure as exc:
        response = None
        error = {"code": exc.code, "message": exc.message}
    except Exception as exc:  # pylint: disable=broad-except
        response = None
        error = {"code": "failed", "message": f"{type(exc).__name__}: {exc}"}
    finally:
        session.close()
    document = {
        "schemaVersion": SCHEMA_VERSION,
        "adapter": ADAPTER,
        "operation": operation,
        "ok": error is None,
        "error": error,
        "connected": session.connected,
        "unitId": session.unit_id,
        "requests": session.requests,
        "responses": session.responses,
        "response": response,
        "exception": exception,
        "elapsedMs": round((time.monotonic() - started) * 1000.0, 3),
    }
    if error is None:
        return document, 0
    return document, 3 if error["code"] == "connect-failed" else 1
