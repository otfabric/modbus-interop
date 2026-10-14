# SPDX-License-Identifier: MIT
"""The `server` command: a PyModbus asynchronous TCP server serving one fixture.

What is PyModbus and what is this adapter:

- Framing, decoding, the transaction handling, the datastore (SimDevice /
  SimData), the execution of FC01..FC06, FC15, FC16, FC22, FC23 and FC43/14,
  the request and response classes of FC08, FC17, FC20, FC21 and FC24 and
  every response and exception response are PyModbus.
- The adapter plugs into three documented extension points:
    * ``custom_pdu``: request classes derived from the stock ones, whose
      ``datastore_update`` adds the unit identifier rule, the exact bounds of
      the two bit tables and the ``request`` event around the stock handler;
      and one class per function code the fixture device does not have, which
      answers "illegal function". PyModbus' own handlers of FC08, FC17,
      FC20, FC21 and FC24 answer with built-in dummy data (a fixed record,
      the queue 0, 1, 2, 3, "Pymodbus" as server ID); their
      ``datastore_update`` is replaced by one that answers from the
      fixture, built from the library's response classes.
    * ``SimDevice(action=...)``: the exact bounds of the coil table for
      writes.
    * ``trace_connect`` (per connection) for the connection events.
"""

from __future__ import annotations

import asyncio
import json
import os
import signal
import sys

from pymodbus.constants import ExcCodes
from pymodbus.exceptions import NoSuchIdException
from pymodbus.pdu import DecodePDU, ExceptionResponse, ModbusPDU
from pymodbus.pdu import bit_message, diag_message, file_message, mei_message, other_message, register_message
from pymodbus.pdu.device import ModbusDeviceIdentification
from pymodbus.server import ModbusTcpServer
from pymodbus.simulator import DataType, SimData, SimDevice

from . import ADAPTER
from .fixture import Fixture, FixtureError, load

SERVER_FUNCTIONS = [1, 2, 3, 4, 5, 6, 8, 15, 16, 17, 20, 21, 22, 23, 24, 43]
MEI_READ_DEVICE_IDENTIFICATION = 0x0E
RETURN_QUERY_DATA = 0x0000
MAX_FILE_RESPONSE_BYTES = 0xF5  # byte count of an FC20 response (specification 6.14)
MAX_RECORD_NUMBER = 9999


def emit(event: dict) -> None:
    """Print one event as one JSON line on stdout."""
    sys.stdout.write(json.dumps(event, separators=(",", ":")) + "\n")
    sys.stdout.flush()


class Device:
    """The fixture device: what the request classes need to know."""

    def __init__(self, fixture: Fixture) -> None:
        self.fixture = fixture
        self.unit_id = fixture.unit_id
        self.coils = fixture.tables.get("coils")
        self.discrete_inputs = fixture.tables.get("discreteInputs")
        # File records and FIFO queues live here, outside the SimDevice: the
        # PyModbus datastore has no file or FIFO tables. One process, one
        # event loop: the state is shared by all connections.
        self.files = {number: list(records) for number, records in fixture.files.items()}
        self.fifos = {address: list(values) for address, values in fixture.fifos.items()}
        # Individual access (read code 4) is implemented by PyModbus: +128.
        self.conformity = 0x80 | fixture.conformity_level

    def bits_in_table(self, function: int, address: int, count: int) -> bool:
        """Whether a bit request lies entirely within its table."""
        block = self.discrete_inputs if function == 2 else self.coils
        return block is not None and block.contains(address, count)

    async def action(self, function, _start, address, _count, _registers, values):
        """SimDevice action: exact bounds of the coil table for writes.

        PyModbus stores bits in 16-bit registers and pads a bit block to
        register boundaries on both sides; the padding is addressable. The
        fixture rule is exception 2 for everything outside the block. For a
        write the number of bits is the length of ``values``; reads are
        checked in the request classes, which know the quantity.
        """
        if function in (5, 15) and values is not None:
            if not self.bits_in_table(function, address, len(values)):
                return ExcCodes.ILLEGAL_ADDRESS
        return None

    def simdevice(self) -> SimDevice:
        """The PyModbus datastore for the fixture: four separate tables."""
        tables = self.fixture.tables

        def bits(name):
            block = tables.get(name)
            if block is None:  # no such table: one never-reachable bit (see bits_in_table)
                return [SimData(0, values=[False], datatype=DataType.BITS)]
            return [SimData(block.address, values=list(block.values), datatype=DataType.BITS)]

        def registers(name):
            block = tables.get(name)
            if block is None:  # no such table: every address is invalid
                return [SimData(0, datatype=DataType.INVALID)]
            return [SimData(block.address, values=list(block.values), datatype=DataType.REGISTERS)]

        return SimDevice(
            self.unit_id,
            simdata=(bits("coils"), bits("discreteInputs"), registers("holdingRegisters"), registers("inputRegisters")),
            action=self.action,
        )


class Served:
    """Mixin for the request classes registered with ``custom_pdu``.

    Wraps the stock ``datastore_update``: unit identifier rule first, then
    the request-specific check, then PyModbus, then the ``request`` event.
    """

    device: Device  # set once before the server starts

    def describe(self) -> dict:
        """Fields of the request event besides unitId, function and exception."""
        return {}

    def precheck(self) -> ExcCodes | None:
        """A check that comes before the stock handler."""
        return None

    def adjust(self, response: ModbusPDU) -> None:
        """Adjust the stock response."""

    async def respond(self, context, device_id: int) -> ModbusPDU:
        """The response: the stock handler, unless a class answers itself."""
        return await super().datastore_update(context, device_id)  # type: ignore[misc]

    def exception(self, code: ExcCodes) -> ModbusPDU:
        return ExceptionResponse(self.function_code, code)

    def report(self, unit_id: int, exception: int | None) -> None:
        event = {"event": "request", "unitId": unit_id, "function": self.function_code}
        event.update(self.describe())
        event["exception"] = exception
        emit(event)

    async def datastore_update(self, context, device_id: int) -> ModbusPDU:
        if device_id != self.device.unit_id:
            # PyModbus turns this exception into exception response 11
            # (gateway target device failed to respond).
            self.report(device_id, int(ExcCodes.GATEWAY_NO_RESPONSE))
            raise NoSuchIdException(f"unit identifier {device_id} is not {self.device.unit_id}")
        code = self.precheck()
        if code is not None:
            response: ModbusPDU = ExceptionResponse(self.function_code, code)
        else:
            try:
                response = await self.respond(context, device_id)
            except Exception:
                # PyModbus answers an error of a stock handler with exception 4.
                self.report(device_id, int(ExcCodes.DEVICE_FAILURE))
                raise
            if not response.isError():
                self.adjust(response)
        self.report(device_id, int(response.exception_code) if response.isError() else None)
        return response


class ReadBits(Served):
    """FC01 and FC02."""

    def describe(self):
        return {"address": self.address, "quantity": self.count}

    def precheck(self):
        if not self.device.bits_in_table(self.function_code, self.address, self.count):
            return ExcCodes.ILLEGAL_ADDRESS
        return None


class ReadCoils(ReadBits, bit_message.ReadCoilsRequest):
    pass


class ReadDiscreteInputs(ReadBits, bit_message.ReadDiscreteInputsRequest):
    pass


class ReadRegisters(Served):
    """FC03 and FC04."""

    def describe(self):
        return {"address": self.address, "quantity": self.count}


class ReadHoldingRegisters(ReadRegisters, register_message.ReadHoldingRegistersRequest):
    pass


class ReadInputRegisters(ReadRegisters, register_message.ReadInputRegistersRequest):
    pass


class WriteSingleCoil(Served, bit_message.WriteSingleCoilRequest):
    def describe(self):
        return {"address": self.address, "quantity": 1, "bits": [bool(b) for b in self.bits[:1]]}


class WriteSingleRegister(Served, register_message.WriteSingleRegisterRequest):
    def describe(self):
        return {"address": self.address, "quantity": 1, "registers": list(self.registers[:1])}


class WriteMultipleCoils(Served, bit_message.WriteMultipleCoilsRequest):
    def describe(self):
        return {"address": self.address, "quantity": self.count, "bits": [bool(b) for b in self.bits]}


class WriteMultipleRegisters(Served, register_message.WriteMultipleRegistersRequest):
    def describe(self):
        return {"address": self.address, "quantity": self.count, "registers": list(self.registers)}


class MaskWriteRegister(Served, register_message.MaskWriteRegisterRequest):
    def describe(self):
        return {"address": self.address, "quantity": 1, "andMask": self.and_mask, "orMask": self.or_mask}


class NothingWrittenOnError:
    """FC23: undo the write when the request ends in an exception response.

    PyModbus writes first and reads afterwards, so a request whose write
    range is valid and whose read range is not has changed the registers by
    the time it is answered with exception 2. The fixture rule is that such
    a request writes nothing: the registers are put back through the
    datastore interface of the library.
    """

    async def datastore_update(self, context, device_id: int) -> ModbusPDU:
        before = None
        if self.write_registers:
            before = await context.async_getValues(device_id, 3, self.write_address, len(self.write_registers))
        response = await super().datastore_update(context, device_id)  # type: ignore[misc]
        if response.isError() and isinstance(before, list):
            await context.async_setValues(device_id, 16, self.write_address, before)
        return response


class ReadWriteMultipleRegisters(Served, NothingWrittenOnError, register_message.ReadWriteMultipleRegistersRequest):
    def describe(self):
        return {
            "address": self.read_address,
            "quantity": self.read_count,
            "writeAddress": self.write_address,
            "writeRegisters": list(self.write_registers),
        }


class EncapsulatedInterface(Served, mei_message.ReadDeviceInformationRequest):
    """FC43. MEI type 14 is the stock Read Device Identification; any other
    MEI type is not a function of the fixture device."""

    def precheck(self):
        if self.sub_function_code != MEI_READ_DEVICE_IDENTIFICATION:
            return ExcCodes.ILLEGAL_FUNCTION
        return None

    def adjust(self, response):
        # PyModbus always announces conformity level 0x83; the fixture rule is
        # the level of the highest category the device has objects for.
        response.conformity = self.device.conformity


class Diagnostics(Served, diag_message.DiagnosticBase):
    """FC08. Sub-function 0 (Return Query Data) echoes the request; every
    other sub-function is not a function of the fixture device. PyModbus'
    decoder keeps the data of sub-function 0 as bytes, unchanged."""

    async def respond(self, context, device_id):
        if self.sub_function_code != RETURN_QUERY_DATA:
            return self.exception(ExcCodes.ILLEGAL_FUNCTION)
        data = self.message if isinstance(self.message, bytes) else b""
        return diag_message.ReturnQueryDataResponse(message=data, dev_id=device_id, transaction_id=self.transaction_id)


class ReportServerId(Served, other_message.ReportDeviceIdRequest):
    """FC17 from the fixture: a one-byte server ID and the run indicator."""

    async def respond(self, context, device_id):
        server_id, run = self.device.fixture.server_id
        return other_message.ReportDeviceIdResponse(
            identifier=bytes([server_id]), status=run, dev_id=device_id, transaction_id=self.transaction_id)


class ReadFileRecord(Served, file_message.ReadFileRecordRequest):
    """FC20 on the fixture's files."""

    async def respond(self, context, device_id):
        if not self.records:
            return self.exception(ExcCodes.ILLEGAL_VALUE)
        answers = []
        size = 0
        for record in self.records:
            # The decoder builds FileRecord(record_length=<registers>), and
            # FileRecord takes record_length as bytes and halves it: the
            # number of registers requested is twice the field. An odd
            # number never gets here (the decoder rejects it).
            count = record.record_length * 2
            if count < 1:
                return self.exception(ExcCodes.ILLEGAL_VALUE)
            size += 2 + 2 * count
            if size > MAX_FILE_RESPONSE_BYTES:
                return self.exception(ExcCodes.ILLEGAL_VALUE)
            records = self.device.files.get(record.file_number)
            first = record.record_number
            if records is None or first > MAX_RECORD_NUMBER or first + count > len(records):
                return self.exception(ExcCodes.ILLEGAL_ADDRESS)
            data = b"".join(value.to_bytes(2, "big") for value in records[first:first + count])
            answers.append(file_message.FileRecord(record_data=data))
        return file_message.ReadFileRecordResponse(records=answers, dev_id=device_id, transaction_id=self.transaction_id)


class WriteFileRecord(Served, file_message.WriteFileRecordRequest):
    """FC21 on the fixture's files: every sub-request is checked before any
    is written; the response is the echo of the request."""

    async def respond(self, context, device_id):
        if not self.records:
            return self.exception(ExcCodes.ILLEGAL_VALUE)
        for record in self.records:
            # Here the decoder sets record_length to the number of registers.
            count = record.record_length
            if count < 1 or len(record.record_data) != 2 * count:
                return self.exception(ExcCodes.ILLEGAL_VALUE)
            records = self.device.files.get(record.file_number)
            first = record.record_number
            if records is None or first > MAX_RECORD_NUMBER or first + count > len(records):
                return self.exception(ExcCodes.ILLEGAL_ADDRESS)
        for record in self.records:
            data = record.record_data
            values = [int.from_bytes(data[i:i + 2], "big") for i in range(0, len(data), 2)]
            self.device.files[record.file_number][record.record_number:record.record_number + len(values)] = values
        return file_message.WriteFileRecordResponse(records=self.records, dev_id=device_id, transaction_id=self.transaction_id)


class ReadFifoQueue(Served, file_message.ReadFifoQueueRequest):
    """FC24 from the fixture's queues; reading does not empty a queue."""

    async def respond(self, context, device_id):
        values = self.device.fifos.get(self.address)
        if values is None:
            return self.exception(ExcCodes.ILLEGAL_ADDRESS)
        return file_message.ReadFifoQueueResponse(values=list(values), dev_id=device_id, transaction_id=self.transaction_id)


class IllegalFunction(Served, ModbusPDU):
    """A function code the fixture device does not have."""

    def decode(self, data: bytes) -> None:
        """Nothing to decode: the answer does not depend on the data."""

    def precheck(self):
        return ExcCodes.ILLEGAL_FUNCTION


def request_classes(fixture: Fixture) -> list:
    """The classes for ``custom_pdu``: one per function code 1..127."""
    classes = [
        ReadCoils, ReadDiscreteInputs, ReadHoldingRegisters, ReadInputRegisters,
        WriteSingleCoil, WriteSingleRegister, WriteMultipleCoils, WriteMultipleRegisters,
        MaskWriteRegister, ReadWriteMultipleRegisters,
        Diagnostics, ReadFileRecord, WriteFileRecord, ReadFifoQueue,
    ]
    # custom_pdu copies only the table of function codes into the server's
    # decoder; the table of sub-functions is global, and after decoding FC08
    # the decoder replaces the request by the stock class of the
    # sub-function. Registering the class for every known sub-function keeps
    # it; an unknown sub-function stays with the class itself.
    for sub_function, (_, response) in list(DecodePDU.pdu_sub_table.get(Diagnostics.function_code, {}).items()):
        DecodePDU.add_sub_pdu(
            type(f"Diagnostics{sub_function}", (Diagnostics,), {"sub_function_code": sub_function}), response)
    if fixture.server_id is not None:
        classes.append(ReportServerId)
    if fixture.has_identification:
        classes.append(EncapsulatedInterface)
        # custom_pdu replaces the class of a function code; the decoder then
        # looks the MEI type up in the table of sub-functions, which is this
        # registration.
        DecodePDU.add_sub_pdu(EncapsulatedInterface, mei_message.ReadDeviceInformationResponse)
    served = {cls.function_code for cls in classes}
    # Without a class of its own PyModbus cannot decode the request and
    # answers with function code 0x80 instead of the request's + 0x80; and
    # the stock classes for diagnostics, file records, FIFO and so on are not
    # functions of the fixture device.
    for function in range(1, 128):
        if function not in served:
            classes.append(type(f"IllegalFunction{function}", (IllegalFunction,), {"function_code": function}))
    return classes


class Server(ModbusTcpServer):
    """ModbusTcpServer with connection events that carry the peer address."""

    def __init__(self, *args, **kwargs) -> None:
        self.last_packet = b""
        super().__init__(*args, trace_packet=self.on_packet, trace_pdu=self.on_pdu, **kwargs)

    def callback_new_connection(self):
        handler = super().callback_new_connection()
        peer = {"value": "unknown"}

        def on_connect(connected: bool) -> None:
            if connected:
                name = handler.transport.get_extra_info("peername") if handler.transport else None
                if name:
                    host = f"[{name[0]}]" if ":" in name[0] else name[0]
                    peer["value"] = f"{host}:{name[1]}"
                emit({"event": "connection-opened", "peer": peer["value"]})
            else:
                emit({"event": "connection-closed", "peer": peer["value"]})

        handler.trace_connect = on_connect
        return handler

    def on_packet(self, sending: bool, data: bytes) -> bytes:
        if not sending:
            self.last_packet = bytes(data)
        return data

    def on_pdu(self, sending: bool, pdu: ModbusPDU) -> ModbusPDU:
        # A request PyModbus could not decode (too short, or a quantity its
        # decoder rejects) is answered by the library itself, synchronously
        # in the receive path, with function code 0x80 and exception 1. It
        # never reaches a request class, so its event is printed here.
        if sending and pdu.function_code == 0x80:
            event = {"event": "request", "unitId": pdu.dev_id}
            if len(self.last_packet) >= 8:
                event["function"] = self.last_packet[7]
            event["exception"] = int(pdu.exception_code)
            emit(event)
        return pdu


async def serve(fixture: Fixture, bind_address: str, port: int, ready_file: str) -> int:
    device = Device(fixture)
    Served.device = device
    identity = None
    if fixture.has_identification:
        identity = ModbusDeviceIdentification(info=dict(fixture.objects))
    try:
        server = Server(
            device.simdevice(),
            identity=identity,
            address=(bind_address, port),
            custom_pdu=request_classes(fixture),
        )
        await server.serve_forever(background=True)
    except (RuntimeError, OSError, TypeError, ValueError) as exc:
        print(f"modbus-interop: cannot listen on {bind_address}:{port}: {exc}", file=sys.stderr)
        return 1

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)

    try:
        directory = os.path.dirname(ready_file)
        if directory:
            os.makedirs(directory, exist_ok=True)
        with open(ready_file, "w", encoding="ascii") as handle:
            handle.write("ready\n")
    except OSError as exc:
        print(f"modbus-interop: cannot write the readiness file {ready_file}: {exc}", file=sys.stderr)
        await server.shutdown()
        return 1
    emit({
        "event": "ready",
        "adapter": ADAPTER,
        "address": f"{bind_address}:{port}",
        "fixture": fixture.name,
        "unitId": fixture.unit_id,
    })

    await stop.wait()
    try:
        os.unlink(ready_file)
    except OSError:
        pass
    await server.shutdown()
    emit({"event": "stopped"})
    return 0


def run(fixture_path: str, bind_address: str, port: int, ready_file: str) -> int:
    """Entry point of the `server` command; returns the exit code."""
    try:
        fixture = load(fixture_path)
    except FixtureError as exc:
        print(f"modbus-interop: invalid fixture: {exc}", file=sys.stderr)
        return 4
    return asyncio.run(serve(fixture, bind_address, port, ready_file))
