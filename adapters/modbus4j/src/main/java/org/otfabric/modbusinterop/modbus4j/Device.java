// SPDX-License-Identifier: GPL-3.0-or-later
package org.otfabric.modbusinterop.modbus4j;

import com.serotonin.modbus4j.ProcessImage;
import com.serotonin.modbus4j.exception.IllegalDataAddressException;

/**
 * The device of docs/FIXTURES.md as a modbus4j {@link ProcessImage}: the library's extension point
 * for the data of one slave. The library decodes a request, finds the process image of its unit
 * identifier and calls it once per coil or register; it encodes the response, and it turns an
 * {@link IllegalDataAddressException} thrown here into exception 2. Function codes, quantities,
 * bit packing and FC17 are the library's code.
 *
 * <p>What the image adds is the fixture's blocks: an address outside the block of its table, or a
 * table the fixture does not have, is an {@link IllegalDataAddressException}. The image sees one
 * element at a time and not the request, so it cannot refuse a write that runs past the end of a
 * block before the part inside it is written (README.md, findings).
 *
 * <p>The library calls the image from the thread of each connection (TCP) or datagram (UDP), so
 * every method is synchronized.
 */
final class Device implements ProcessImage {

    private final int unitId;
    private final Table coils;
    private final Table discreteInputs;
    private final Table holdingRegisters;
    private final Table inputRegisters;
    private final int serverId;
    private final boolean runIndicator;

    /** One block of a table: its first address and its values (0/1 for bits). */
    private static final class Table {
        final int first;
        final int[] values;

        Table(int first, int[] values) {
            this.first = first;
            this.values = values == null ? new int[0] : values;
        }

        boolean contains(int address) {
            return address >= first && address - first < values.length;
        }

        int get(int address) throws IllegalDataAddressException {
            if (!contains(address)) {
                throw new IllegalDataAddressException();
            }
            return values[address - first];
        }

        void set(int address, int value) throws IllegalDataAddressException {
            if (!contains(address)) {
                throw new IllegalDataAddressException();
            }
            values[address - first] = value;
        }
    }

    Device(Fixture fixture) {
        unitId = fixture.unitId;
        coils = new Table(fixture.coilsAddress, bits(fixture.coils));
        discreteInputs = new Table(fixture.discreteInputsAddress, bits(fixture.discreteInputs));
        holdingRegisters = new Table(fixture.holdingRegistersAddress, copy(fixture.holdingRegisters));
        inputRegisters = new Table(fixture.inputRegistersAddress, copy(fixture.inputRegisters));
        serverId = fixture.serverId;
        runIndicator = fixture.runIndicator;
    }

    private static int[] bits(boolean[] values) {
        if (values == null) {
            return null;
        }
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i] ? 1 : 0;
        }
        return out;
    }

    private static int[] copy(int[] values) {
        return values == null ? null : values.clone();
    }

    @Override
    public int getSlaveId() {
        return unitId;
    }

    // ---- coils ------------------------------------------------------------------------------

    @Override
    public synchronized boolean getCoil(int offset) throws IllegalDataAddressException {
        return coils.get(offset) != 0;
    }

    /** A local write (the application's side); the library does not call it. */
    @Override
    public synchronized void setCoil(int offset, boolean value) {
        if (coils.contains(offset)) {
            coils.values[offset - coils.first] = value ? 1 : 0;
        }
    }

    /** A write from the network: FC05 and FC15. */
    @Override
    public synchronized void writeCoil(int offset, boolean value) throws IllegalDataAddressException {
        coils.set(offset, value ? 1 : 0);
    }

    // ---- discrete inputs --------------------------------------------------------------------

    @Override
    public synchronized boolean getInput(int offset) throws IllegalDataAddressException {
        return discreteInputs.get(offset) != 0;
    }

    @Override
    public synchronized void setInput(int offset, boolean value) {
        if (discreteInputs.contains(offset)) {
            discreteInputs.values[offset - discreteInputs.first] = value ? 1 : 0;
        }
    }

    // ---- holding registers ------------------------------------------------------------------

    @Override
    public synchronized short getHoldingRegister(int offset) throws IllegalDataAddressException {
        return (short) holdingRegisters.get(offset);
    }

    @Override
    public synchronized void setHoldingRegister(int offset, short value) {
        if (holdingRegisters.contains(offset)) {
            holdingRegisters.values[offset - holdingRegisters.first] = value & 0xFFFF;
        }
    }

    /** A write from the network: FC06 and FC16. */
    @Override
    public synchronized void writeHoldingRegister(int offset, short value) throws IllegalDataAddressException {
        holdingRegisters.set(offset, value & 0xFFFF);
    }

    // ---- input registers --------------------------------------------------------------------

    @Override
    public synchronized short getInputRegister(int offset) throws IllegalDataAddressException {
        return (short) inputRegisters.get(offset);
    }

    @Override
    public synchronized void setInputRegister(int offset, short value) {
        if (inputRegisters.contains(offset)) {
            inputRegisters.values[offset - inputRegisters.first] = value & 0xFFFF;
        }
    }

    // ---- FC07 and FC17 ----------------------------------------------------------------------

    /** FC07 Read Exception Status: the fixture has no exception status, so no bit is set. */
    @Override
    public byte getExceptionStatus() {
        return 0;
    }

    /**
     * FC17 Report Server ID: the library sends the byte count and these bytes, the fixture's server
     * ID and run indicator. A fixture without {@code reportServerId} has no answer; the library turns
     * the exception into exception 4 (server device failure), the only refusal it lets this method
     * make.
     */
    @Override
    public byte[] getReportSlaveIdData() {
        if (serverId < 0) {
            throw new IllegalStateException("the fixture has no reportServerId");
        }
        return new byte[] {(byte) serverId, (byte) (runIndicator ? 0xFF : 0x00)};
    }
}
