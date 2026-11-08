// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.j2mod;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.msg.ExceptionResponse;
import com.ghgande.j2mod.modbus.msg.MaskWriteRegisterRequest;
import com.ghgande.j2mod.modbus.msg.ModbusRequest;
import com.ghgande.j2mod.modbus.msg.ModbusResponse;
import com.ghgande.j2mod.modbus.msg.ReadCoilsRequest;
import com.ghgande.j2mod.modbus.msg.ReadFIFOQueueRequest;
import com.ghgande.j2mod.modbus.msg.ReadInputDiscretesRequest;
import com.ghgande.j2mod.modbus.msg.ReadInputRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadWriteMultipleRequest;
import com.ghgande.j2mod.modbus.msg.WriteCoilRequest;
import com.ghgande.j2mod.modbus.msg.WriteFileRecordRequest;
import com.ghgande.j2mod.modbus.msg.WriteMultipleCoilsRequest;
import com.ghgande.j2mod.modbus.msg.WriteMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.AbstractModbusListener;
import com.ghgande.j2mod.modbus.procimg.File;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.Record;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalIn;
import com.ghgande.j2mod.modbus.procimg.SimpleDigitalOut;
import com.ghgande.j2mod.modbus.procimg.SimpleInputRegister;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;

/**
 * The device of docs/FIXTURES.md on the library's own device model: a {@link SimpleProcessImage}
 * that the library's request classes read and change in {@code createResponse}. The library
 * decodes the request, applies it to the process image (bit packing, FC22's mask arithmetic,
 * FC23's write-before-read, file records, the FIFO) and encodes the response.
 *
 * <p>The adapter is the {@link AbstractModbusListener} the library hands to {@code
 * createResponse}: the listener is the library's extension point that names the process image of
 * a request. In front of {@code createResponse} it applies the rules of the fixture the library
 * does not check: the unit identifier (exception 11; no answer at all on RTU over TCP), the
 * quantity (exception 3), FC23's read range and FC21's records before anything is written
 * (exception 2). Every exception response, the adapter's included, is the library's
 * ({@link ModbusRequest#createExceptionResponse}).
 *
 * <p>The state is shared by all connections; one request at a time is applied to it.
 */
final class Device extends AbstractModbusListener {

    static final int ILLEGAL_DATA_ADDRESS = 2;
    static final int ILLEGAL_DATA_VALUE = 3;
    static final int GATEWAY_TARGET_FAILED_TO_RESPOND = 11;

    /**
     * The functions the library's request classes serve from a process image. FC07, FC08, FC11,
     * FC12, FC17 and FC43 have request classes whose {@code createResponse} answers exception 1.
     */
    static final int[] FUNCTIONS = {1, 2, 3, 4, 5, 6, 15, 16, 20, 21, 22, 23, 24};

    /**
     * The longest record window: FC21 writes at most 122 registers in one sub-request (a request
     * PDU of 253 bytes), and an FC20 read of more could not be answered within a PDU.
     */
    static final int RECORD_WINDOW = 122;

    private final int unitId;
    private final Out out;
    private final SimpleProcessImage image = new SimpleProcessImage();
    /** FC24: one process image per FIFO pointer address (see {@link #fifoImage}). */
    private final Map<Integer, SimpleProcessImage> fifos = new HashMap<>();
    private final SimpleProcessImage nothing = new SimpleProcessImage();
    private final int holdingAddress;
    private final int holdingCount;
    /** Number of registers of each file of the fixture. */
    private final Map<Integer, Integer> fileLengths = new HashMap<>();
    /** The image the library is given for the request being answered. */
    private ProcessImage current;

    Device(Fixture fixture, Out out) {
        unitId = fixture.unitId;
        this.out = out;
        holdingAddress = fixture.holdingRegistersAddress;
        holdingCount = fixture.holdingRegisters == null ? 0 : fixture.holdingRegisters.length;

        // A sparse process image: the library answers exception 2 for an address with no
        // element, and the blocks of the fixture are the only elements.
        for (int i = 0; fixture.coils != null && i < fixture.coils.length; i++) {
            image.addDigitalOut(fixture.coilsAddress + i, new SimpleDigitalOut(fixture.coils[i]));
        }
        for (int i = 0; fixture.discreteInputs != null && i < fixture.discreteInputs.length; i++) {
            image.addDigitalIn(fixture.discreteInputsAddress + i, new SimpleDigitalIn(fixture.discreteInputs[i]));
        }
        for (int i = 0; fixture.holdingRegisters != null && i < fixture.holdingRegisters.length; i++) {
            image.addRegister(holdingAddress + i, new SimpleRegister(fixture.holdingRegisters[i]));
        }
        for (int i = 0; fixture.inputRegisters != null && i < fixture.inputRegisters.length; i++) {
            image.addInputRegister(fixture.inputRegistersAddress + i, new SimpleInputRegister(fixture.inputRegisters[i]));
        }
        addFiles(fixture.files);
        for (Map.Entry<Integer, int[]> queue : fixture.fifoQueues.entrySet()) {
            SimpleProcessImage fifo = fifoImage(queue.getKey(), queue.getValue());
            if (fifo != null) {
                fifos.put(queue.getKey(), fifo);
            }
        }
    }

    /**
     * Files for FC20 and FC21. The library's file model differs from the fixture's in two ways,
     * and the layout bridges both through the public process-image API:
     *
     * <ul>
     *   <li>The library refuses a file number that is not below the number of files of the
     *       image ({@code fileNumber >= getFileCount()}), as if the numbers were dense indexes
     *       from 0, and then looks the file up by its number. Every number from 0 to the
     *       highest one gets a file: the fixture's, or an empty one, which has no records and
     *       is answered with exception 2 like a file that does not exist.
     *   <li>A library record holds its own registers, and FC20/FC21 read or write {@code
     *       length} registers from inside one record. In the fixture the record number is the
     *       index of a register in the file and a request runs on over the following ones. So
     *       record {@code r} holds the file's registers from {@code r} on (up to {@link
     *       #RECORD_WINDOW}), the same register objects as every other record that covers them.
     * </ul>
     *
     * The library also refuses file numbers of 10000 and above (exception 2).
     */
    private void addFiles(Map<Integer, int[]> files) {
        int highest = -1;
        for (Map.Entry<Integer, int[]> file : files.entrySet()) {
            fileLengths.put(file.getKey(), file.getValue().length);
            if (file.getKey() < 10000) {
                highest = Math.max(highest, file.getKey());
            }
        }
        for (int number = 0; number <= highest; number++) {
            int[] values = files.get(number);
            if (values == null) {
                image.addFile(new File(number, 0));
                continue;
            }
            SimpleRegister[] registers = new SimpleRegister[values.length];
            for (int i = 0; i < values.length; i++) {
                registers[i] = new SimpleRegister(values[i]);
            }
            File file = new File(number, values.length);
            for (int r = 0; r < values.length; r++) {
                int window = Math.min(values.length - r, RECORD_WINDOW);
                Record record = new Record(r, window);
                for (int j = 0; j < window; j++) {
                    record.setRegister(j, registers[r + j]);
                }
                file.setRecord(r, record);
            }
            image.addFile(file);
        }
    }

    /**
     * FC24: the library reads the FIFO count from the holding register at the FIFO pointer
     * address and the values from the holding registers that follow it. The fixture's queues are
     * not in the holding registers (in the baseline, 100 and 101 are holding registers with
     * other values, and the queues at 100 and 101 overlap), so each queue gets a process image
     * of its own, laid out the library's way: the count at the pointer address, the values
     * after it. The listener hands the library that image for an FC24 request. A queue whose
     * values would run past address 65535 cannot be laid out and is left out (exception 2).
     */
    private static SimpleProcessImage fifoImage(int address, int[] values) {
        if (address + values.length > 65535) {
            System.err.println("modbus-interop: the FIFO queue at " + address
                    + " runs past address 65535 in the library's layout and is not served");
            return null;
        }
        SimpleProcessImage fifo = new SimpleProcessImage();
        fifo.addRegister(address, new SimpleRegister(values.length));
        for (int i = 0; i < values.length; i++) {
            fifo.addRegister(address + 1 + i, new SimpleRegister(values[i]));
        }
        return fifo;
    }

    // ---- AbstractModbusListener -----------------------------------------------------------------

    /** The library asks for the process image from inside {@code createResponse}. */
    @Override
    public ProcessImage getProcessImage(int unit) {
        return current;
    }

    /** Not used: the adapter runs the accept loop itself (ServerCommand). */
    @Override
    public void run() {
        throw new UnsupportedOperationException("the adapter runs its own accept loop");
    }

    @Override
    public void stop() {}

    // ---- one request --------------------------------------------------------------------------

    /**
     * Answers one request the library decoded. Returns the response to send, or {@code null}
     * when nothing is sent (RTU over TCP, a request for another address).
     */
    synchronized ModbusResponse answer(ModbusRequest request, boolean rtu) {
        int unit = request.getUnitID() & 0xFF;
        int function = request.getFunctionCode() & 0xFF;
        JsonObject event = event(request, unit, function);
        ModbusResponse response;
        if (unit != unitId) {
            if (rtu) {
                // Modbus over Serial Line V1.02, 2.2: only the addressed server answers, and
                // nobody answers a broadcast. Not answered, so no event either.
                return null;
            }
            response = request.createExceptionResponse(GATEWAY_TARGET_FAILED_TO_RESPOND);
        } else {
            int code = check(request);
            if (code != 0) {
                response = request.createExceptionResponse(code);
            } else {
                current = function == Modbus.READ_FIFO_QUEUE
                        ? fifos.getOrDefault(((ReadFIFOQueueRequest) request).getReference(), nothing)
                        : image;
                try {
                    response = request.createResponse(this);
                } finally {
                    current = null;
                }
            }
        }
        if (response instanceof ExceptionResponse exception) {
            event.addProperty("exception", exception.getExceptionCode());
        } else {
            event.add("exception", JsonNull.INSTANCE);
        }
        out.line(event);
        return response;
    }

    /**
     * The rules of the fixture the library does not apply itself; 0 when the request goes on to
     * the library. The library already answers exception 2 for an address outside the blocks
     * (the sparse image has no element there) and writes FC15/FC16/FC23 only after it has found
     * every register of the write range.
     */
    private int check(ModbusRequest request) {
        return switch (request) {
            case ReadCoilsRequest r -> quantity(r.getBitCount(), 2000);
            case ReadInputDiscretesRequest r -> quantity(r.getBitCount(), 2000);
            case ReadMultipleRegistersRequest r -> quantity(r.getWordCount(), 125);
            case ReadInputRegistersRequest r -> quantity(r.getWordCount(), 125);
            case WriteMultipleCoilsRequest r -> {
                int code = quantity(r.getBitCount(), 1968);
                // The library takes the bits from the bytes that arrived and fails on too few.
                yield code != 0 || r.getByteCount() == (r.getBitCount() + 7) / 8 ? code : ILLEGAL_DATA_VALUE;
            }
            case WriteMultipleRegistersRequest r -> quantity(r.getWordCount(), 123);
            case ReadWriteMultipleRequest r -> {
                int code = quantity(r.getReadWordCount(), 125);
                if (code == 0) {
                    code = quantity(r.getWriteWordCount(), 121);
                }
                // The library writes before it reads and finds the read range only then: an
                // invalid read range would be answered with exception 2 after the write.
                if (code == 0 && !inHolding(r.getReadReference(), r.getReadWordCount())) {
                    code = ILLEGAL_DATA_ADDRESS;
                }
                if (code == 0 && !inHolding(r.getWriteReference(), r.getWriteWordCount())) {
                    code = ILLEGAL_DATA_ADDRESS;
                }
                yield code;
            }
            case WriteFileRecordRequest r -> {
                // The library writes register by register and stops at the first one that is
                // missing: every sub-request is checked first (docs/FIXTURES.md, rule 10).
                for (int i = 0; i < r.getRequestCount(); i++) {
                    WriteFileRecordRequest.RecordRequest sub = r.getRecord(i);
                    Integer length = fileLengths.get(sub.getFileNumber());
                    if (length == null || sub.getRecordNumber() + sub.getWordCount() > length) {
                        yield ILLEGAL_DATA_ADDRESS;
                    }
                }
                yield 0;
            }
            default -> 0;
        };
    }

    private static int quantity(int quantity, int max) {
        return quantity < 1 || quantity > max ? ILLEGAL_DATA_VALUE : 0;
    }

    private boolean inHolding(int address, int quantity) {
        return address >= holdingAddress && address + quantity <= holdingAddress + holdingCount;
    }

    // ---- request events -------------------------------------------------------------------------

    private static JsonObject event(ModbusRequest request, int unit, int function) {
        JsonObject event = new JsonObject();
        event.addProperty("event", "request");
        event.addProperty("unitId", unit);
        event.addProperty("function", function);
        switch (request) {
            case ReadCoilsRequest r -> span(event, r.getReference(), r.getBitCount());
            case ReadInputDiscretesRequest r -> span(event, r.getReference(), r.getBitCount());
            case ReadMultipleRegistersRequest r -> span(event, r.getReference(), r.getWordCount());
            case ReadInputRegistersRequest r -> span(event, r.getReference(), r.getWordCount());
            case WriteCoilRequest r -> {
                span(event, r.getReference(), 1);
                JsonArray bits = new JsonArray();
                bits.add(r.getCoil());
                event.add("bits", bits);
            }
            case WriteSingleRegisterRequest r -> {
                span(event, r.getReference(), 1);
                JsonArray registers = new JsonArray();
                registers.add(r.getRegister().getValue());
                event.add("registers", registers);
            }
            case WriteMultipleCoilsRequest r -> {
                span(event, r.getReference(), r.getBitCount());
                BitVector coils = r.getCoils();
                int count = Math.min(r.getBitCount(), r.getByteCount() * 8);
                JsonArray bits = new JsonArray();
                for (int i = 0; i < count; i++) {
                    bits.add(coils.getBit(i));
                }
                event.add("bits", bits);
            }
            case WriteMultipleRegistersRequest r -> {
                span(event, r.getReference(), r.getWordCount());
                JsonArray registers = new JsonArray();
                for (int i = 0; i < r.getWordCount(); i++) {
                    registers.add(r.getRegisterValue(i));
                }
                event.add("registers", registers);
            }
            case MaskWriteRegisterRequest r -> {
                span(event, r.getReference(), 1);
                event.addProperty("andMask", r.getAndMask());
                event.addProperty("orMask", r.getOrMask());
            }
            case ReadWriteMultipleRequest r -> {
                span(event, r.getReadReference(), r.getReadWordCount());
                event.addProperty("writeAddress", r.getWriteReference());
                JsonArray registers = new JsonArray();
                for (int i = 0; i < r.getWriteWordCount(); i++) {
                    registers.add(r.getRegister(i).getValue());
                }
                event.add("writeRegisters", registers);
            }
            default -> {
                // FC08, FC17, FC20, FC21, FC24 and every other function: unitId, function and
                // exception only.
            }
        }
        return event;
    }

    private static void span(JsonObject event, int address, int quantity) {
        event.addProperty("address", address);
        event.addProperty("quantity", quantity);
    }
}
