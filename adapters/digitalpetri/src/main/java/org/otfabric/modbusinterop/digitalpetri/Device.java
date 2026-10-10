// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.exceptions.UnknownUnitIdException;
import com.digitalpetri.modbus.pdu.MaskWriteRegisterRequest;
import com.digitalpetri.modbus.pdu.MaskWriteRegisterResponse;
import com.digitalpetri.modbus.pdu.ReadCoilsRequest;
import com.digitalpetri.modbus.pdu.ReadCoilsResponse;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsRequest;
import com.digitalpetri.modbus.pdu.ReadDiscreteInputsResponse;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadHoldingRegistersResponse;
import com.digitalpetri.modbus.pdu.ReadInputRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadInputRegistersResponse;
import com.digitalpetri.modbus.pdu.ReadWriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.ReadWriteMultipleRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleCoilsRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleCoilsResponse;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersRequest;
import com.digitalpetri.modbus.pdu.WriteMultipleRegistersResponse;
import com.digitalpetri.modbus.pdu.WriteSingleCoilRequest;
import com.digitalpetri.modbus.pdu.WriteSingleCoilResponse;
import com.digitalpetri.modbus.pdu.WriteSingleRegisterRequest;
import com.digitalpetri.modbus.pdu.WriteSingleRegisterResponse;
import com.digitalpetri.modbus.server.ModbusRequestContext;
import com.digitalpetri.modbus.server.ModbusRequestContext.ModbusTcpRequestContext;
import com.digitalpetri.modbus.server.ProcessImage;
import com.digitalpetri.modbus.server.RawModbusTcpRequest;
import com.digitalpetri.modbus.server.RawModbusTcpResponse;
import com.digitalpetri.modbus.server.RawModbusTcpServices;
import com.digitalpetri.modbus.server.ReadWriteModbusServices;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The device of docs/FIXTURES.md, built on the library's own device model: a {@link ProcessImage}
 * served by {@link ReadWriteModbusServices}. The library decodes a request and calls the method
 * of its function with the unit identifier; the library's services read or change the process
 * image, so the bit packing, FC22's mask arithmetic and FC23's write-before-read are its code.
 *
 * <p>The process image is sparse and unbounded (an address nobody wrote reads as 0) and the
 * library's services check little, so this class stands in front of them and first applies the
 * rules of the fixture: the unit identifier, the quantity, the block of the table. What is
 * thrown as a {@link ModbusResponseException} the library sends as an exception response.
 * (In front of, not a subclass: the write methods of {@code ReadWriteModbusServices} do not
 * declare {@code ModbusResponseException}, so an override could not reject a write.)
 *
 * <p>The state is shared by all connections. The library already runs the requests of all
 * connections one after the other; check and execution are synchronized anyway.
 */
final class Device implements RawModbusTcpServices {

    static final int ILLEGAL_FUNCTION = 1;
    static final int ILLEGAL_DATA_ADDRESS = 2;
    static final int ILLEGAL_DATA_VALUE = 3;
    static final int GATEWAY_TARGET_FAILED_TO_RESPOND = 11;

    /** The functions the library decodes and dispatches: the ones this device serves. */
    static final int[] FUNCTIONS = {1, 2, 3, 4, 5, 6, 15, 16, 22, 23};
    private static final Set<Integer> SERVED = Set.of(1, 2, 3, 4, 5, 6, 15, 16, 22, 23);

    private interface Body<T> {
        T run() throws ModbusResponseException, UnknownUnitIdException;
    }

    private final int unitId;
    private final ProcessImage image = new ProcessImage();
    /** The library's services on the process image; they ask for the image of a unit identifier. */
    private final ReadWriteModbusServices services = new ReadWriteModbusServices() {
        @Override
        protected Optional<ProcessImage> getProcessImage(int unit) {
            return unit == unitId ? Optional.of(image) : Optional.empty();
        }
    };
    private final int coilsAddress;
    private final int coilsCount;
    private final int discreteInputsAddress;
    private final int discreteInputsCount;
    private final int holdingAddress;
    private final int holdingCount;
    private final int inputAddress;
    private final int inputCount;
    private final Out out;

    Device(Fixture fixture, Out out) {
        unitId = fixture.unitId;
        coilsAddress = fixture.coilsAddress;
        coilsCount = fixture.coils == null ? 0 : fixture.coils.length;
        discreteInputsAddress = fixture.discreteInputsAddress;
        discreteInputsCount = fixture.discreteInputs == null ? 0 : fixture.discreteInputs.length;
        holdingAddress = fixture.holdingRegistersAddress;
        holdingCount = fixture.holdingRegisters == null ? 0 : fixture.holdingRegisters.length;
        inputAddress = fixture.inputRegistersAddress;
        inputCount = fixture.inputRegisters == null ? 0 : fixture.inputRegisters.length;
        this.out = out;
        image.with(tx -> {
            tx.writeCoils(map -> load(map, coilsAddress, fixture.coils));
            tx.writeDiscreteInputs(map -> load(map, discreteInputsAddress, fixture.discreteInputs));
            tx.writeHoldingRegisters(map -> load(map, holdingAddress, fixture.holdingRegisters));
            tx.writeInputRegisters(map -> load(map, inputAddress, fixture.inputRegisters));
        });
    }

    private static void load(Map<Integer, Boolean> map, int address, boolean[] values) {
        for (int i = 0; values != null && i < values.length; i++) {
            map.put(address + i, values[i]);
        }
    }

    private static void load(Map<Integer, byte[]> map, int address, int[] values) {
        for (int i = 0; values != null && i < values.length; i++) {
            map.put(address + i, new byte[] {(byte) (values[i] >> 8), (byte) values[i]});
        }
    }

    // ---- function codes the library cannot decode ---------------------------------------------

    /**
     * The library offers every request PDU here before it decodes it. Left alone, a function code
     * its request serializer does not know makes it close the connection; this answers it with
     * exception 1 instead (docs/FIXTURES.md, rule 8). The functions the library does decode go on
     * to the typed methods below.
     */
    @Override
    public synchronized Optional<RawModbusTcpResponse> handleRawTcpRequest(
            ModbusTcpRequestContext context, RawModbusTcpRequest request) {
        byte[] pdu = request.pdu();
        if (pdu.length == 0) {
            return Optional.empty();
        }
        int function = pdu[0] & 0xFF;
        if (SERVED.contains(function)) {
            return Optional.empty();
        }
        int code = request.unitId() == unitId ? ILLEGAL_FUNCTION : GATEWAY_TARGET_FAILED_TO_RESPOND;
        JsonObject event = event(request.unitId(), function);
        event.addProperty("exception", code);
        out.line(event);
        return Optional.of(new RawModbusTcpResponse(new byte[] {(byte) (function | 0x80), (byte) code}));
    }

    // ---- reads ----------------------------------------------------------------------------------

    @Override
    public ReadCoilsResponse readCoils(ModbusRequestContext context, int unit, ReadCoilsRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 1, request.address(), request.quantity());
        return answer(event, () -> {
            quantity(1, request.quantity(), 2000);
            block(1, request.address(), request.quantity(), coilsAddress, coilsCount);
            return services.readCoils(context, unit, request);
        });
    }

    @Override
    public ReadDiscreteInputsResponse readDiscreteInputs(
            ModbusRequestContext context, int unit, ReadDiscreteInputsRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 2, request.address(), request.quantity());
        return answer(event, () -> {
            quantity(2, request.quantity(), 2000);
            block(2, request.address(), request.quantity(), discreteInputsAddress, discreteInputsCount);
            return services.readDiscreteInputs(context, unit, request);
        });
    }

    @Override
    public ReadHoldingRegistersResponse readHoldingRegisters(
            ModbusRequestContext context, int unit, ReadHoldingRegistersRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 3, request.address(), request.quantity());
        return answer(event, () -> {
            quantity(3, request.quantity(), 125);
            block(3, request.address(), request.quantity(), holdingAddress, holdingCount);
            return services.readHoldingRegisters(context, unit, request);
        });
    }

    @Override
    public ReadInputRegistersResponse readInputRegisters(
            ModbusRequestContext context, int unit, ReadInputRegistersRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 4, request.address(), request.quantity());
        return answer(event, () -> {
            quantity(4, request.quantity(), 125);
            block(4, request.address(), request.quantity(), inputAddress, inputCount);
            return services.readInputRegisters(context, unit, request);
        });
    }

    // ---- writes ---------------------------------------------------------------------------------

    @Override
    public WriteSingleCoilResponse writeSingleCoil(
            ModbusRequestContext context, int unit, WriteSingleCoilRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 5, request.address(), 1);
        JsonArray bits = new JsonArray();
        bits.add(request.value() == 0xFF00);
        event.add("bits", bits);
        return answer(event, () -> {
            // Only 0xFF00 and 0x0000 are coil values (V1.1b3, 6.5); the library's service
            // would switch the coil on for anything but 0.
            if (request.value() != 0xFF00 && request.value() != 0x0000) {
                throw new ModbusResponseException(5, ILLEGAL_DATA_VALUE);
            }
            block(5, request.address(), 1, coilsAddress, coilsCount);
            return services.writeSingleCoil(context, unit, request);
        });
    }

    @Override
    public WriteSingleRegisterResponse writeSingleRegister(
            ModbusRequestContext context, int unit, WriteSingleRegisterRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 6, request.address(), 1);
        JsonArray registers = new JsonArray();
        registers.add(request.value());
        event.add("registers", registers);
        return answer(event, () -> {
            block(6, request.address(), 1, holdingAddress, holdingCount);
            return services.writeSingleRegister(context, unit, request);
        });
    }

    @Override
    public WriteMultipleCoilsResponse writeMultipleCoils(
            ModbusRequestContext context, int unit, WriteMultipleCoilsRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        byte[] values = request.values();
        int count = Math.min(request.quantity(), values.length * 8);
        JsonObject event = event(unit, 15, request.address(), request.quantity());
        JsonArray bits = new JsonArray();
        for (int i = 0; i < count; i++) {
            bits.add((values[i / 8] >> (i % 8) & 1) == 1);
        }
        event.add("bits", bits);
        return answer(event, () -> {
            quantity(15, request.quantity(), 1968);
            if (values.length != (request.quantity() + 7) / 8) {
                throw new ModbusResponseException(15, ILLEGAL_DATA_VALUE);
            }
            block(15, request.address(), request.quantity(), coilsAddress, coilsCount);
            return services.writeMultipleCoils(context, unit, request);
        });
    }

    @Override
    public WriteMultipleRegistersResponse writeMultipleRegisters(
            ModbusRequestContext context, int unit, WriteMultipleRegistersRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        byte[] values = request.values();
        JsonObject event = event(unit, 16, request.address(), request.quantity());
        event.add("registers", words(values));
        return answer(event, () -> {
            quantity(16, request.quantity(), 123);
            if (values.length != request.quantity() * 2) {
                throw new ModbusResponseException(16, ILLEGAL_DATA_VALUE);
            }
            block(16, request.address(), request.quantity(), holdingAddress, holdingCount);
            return services.writeMultipleRegisters(context, unit, request);
        });
    }

    /** FC22: the library computes (current AND andMask) OR (orMask AND NOT andMask). */
    @Override
    public MaskWriteRegisterResponse maskWriteRegister(
            ModbusRequestContext context, int unit, MaskWriteRegisterRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        JsonObject event = event(unit, 22, request.address(), 1);
        event.addProperty("andMask", request.andMask());
        event.addProperty("orMask", request.orMask());
        return answer(event, () -> {
            block(22, request.address(), 1, holdingAddress, holdingCount);
            return services.maskWriteRegister(context, unit, request);
        });
    }

    /** FC23: the library writes, then reads, in one transaction on the process image. */
    @Override
    public ReadWriteMultipleRegistersResponse readWriteMultipleRegisters(
            ModbusRequestContext context, int unit, ReadWriteMultipleRegistersRequest request)
            throws ModbusResponseException, UnknownUnitIdException {
        byte[] values = request.values();
        JsonObject event = event(unit, 23, request.readAddress(), request.readQuantity());
        event.addProperty("writeAddress", request.writeAddress());
        event.add("writeRegisters", words(values));
        return answer(event, () -> {
            quantity(23, request.readQuantity(), 125);
            quantity(23, request.writeQuantity(), 121);
            if (values.length != request.writeQuantity() * 2) {
                throw new ModbusResponseException(23, ILLEGAL_DATA_VALUE);
            }
            block(23, request.readAddress(), request.readQuantity(), holdingAddress, holdingCount);
            block(23, request.writeAddress(), request.writeQuantity(), holdingAddress, holdingCount);
            return services.readWriteMultipleRegisters(context, unit, request);
        });
    }

    // ---- rules ----------------------------------------------------------------------------------

    /**
     * Applies the unit identifier rule, runs the function and reports the request with the way it
     * was answered.
     */
    private synchronized <T> T answer(JsonObject event, Body<T> body)
            throws ModbusResponseException, UnknownUnitIdException {
        int function = event.get("function").getAsInt();
        try {
            // Thrown here, before the library's service looks for the process image: it would
            // throw UnknownUnitIdException, which the transport turns into no answer at all.
            if (event.get("unitId").getAsInt() != unitId) {
                throw new ModbusResponseException(function, GATEWAY_TARGET_FAILED_TO_RESPOND);
            }
            T response = body.run();
            event.add("exception", JsonNull.INSTANCE);
            out.line(event);
            return response;
        } catch (ModbusResponseException e) {
            event.addProperty("exception", e.getExceptionCode());
            out.line(event);
            throw e;
        }
    }

    /** A quantity of 0 or above the maximum of the function: exception 3. */
    private static void quantity(int function, int quantity, int max) throws ModbusResponseException {
        if (quantity < 1 || quantity > max) {
            throw new ModbusResponseException(function, ILLEGAL_DATA_VALUE);
        }
    }

    /** Exception 2 when any address of the request lies outside the block of the table. */
    private static void block(int function, int address, int quantity, int first, int count)
            throws ModbusResponseException {
        if (address < first || address + quantity > first + count) {
            throw new ModbusResponseException(function, ILLEGAL_DATA_ADDRESS);
        }
    }

    private static JsonArray words(byte[] values) {
        JsonArray words = new JsonArray();
        for (int i = 0; i + 1 < values.length; i += 2) {
            words.add((values[i] & 0xFF) << 8 | (values[i + 1] & 0xFF));
        }
        return words;
    }

    private static JsonObject event(int unit, int function) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "request");
        event.addProperty("unitId", unit);
        event.addProperty("function", function);
        return event;
    }

    private static JsonObject event(int unit, int function, int address, int quantity) {
        JsonObject event = event(unit, function);
        event.addProperty("address", address);
        event.addProperty("quantity", quantity);
        return event;
    }
}
