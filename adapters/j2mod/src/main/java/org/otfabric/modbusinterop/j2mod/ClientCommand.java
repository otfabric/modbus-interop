// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.j2mod;

import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.ModbusSlaveException;
import com.ghgande.j2mod.modbus.io.ModbusTCPTransaction;
import com.ghgande.j2mod.modbus.io.ModbusTransaction;
import com.ghgande.j2mod.modbus.io.ModbusUDPTransaction;
import com.ghgande.j2mod.modbus.msg.ExceptionResponse;
import com.ghgande.j2mod.modbus.msg.MaskWriteRegisterRequest;
import com.ghgande.j2mod.modbus.msg.MaskWriteRegisterResponse;
import com.ghgande.j2mod.modbus.msg.ModbusRequest;
import com.ghgande.j2mod.modbus.msg.ModbusResponse;
import com.ghgande.j2mod.modbus.msg.ReadCoilsRequest;
import com.ghgande.j2mod.modbus.msg.ReadCoilsResponse;
import com.ghgande.j2mod.modbus.msg.ReadFIFOQueueRequest;
import com.ghgande.j2mod.modbus.msg.ReadFIFOQueueResponse;
import com.ghgande.j2mod.modbus.msg.ReadFileRecordRequest;
import com.ghgande.j2mod.modbus.msg.ReadFileRecordResponse;
import com.ghgande.j2mod.modbus.msg.ReadInputDiscretesRequest;
import com.ghgande.j2mod.modbus.msg.ReadInputDiscretesResponse;
import com.ghgande.j2mod.modbus.msg.ReadInputRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadInputRegistersResponse;
import com.ghgande.j2mod.modbus.msg.ReadMEIRequest;
import com.ghgande.j2mod.modbus.msg.ReadMEIResponse;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.msg.ReadSerialDiagnosticsRequest;
import com.ghgande.j2mod.modbus.msg.ReadSerialDiagnosticsResponse;
import com.ghgande.j2mod.modbus.msg.ReadWriteMultipleRequest;
import com.ghgande.j2mod.modbus.msg.ReadWriteMultipleResponse;
import com.ghgande.j2mod.modbus.msg.ReportSlaveIDRequest;
import com.ghgande.j2mod.modbus.msg.ReportSlaveIDResponse;
import com.ghgande.j2mod.modbus.msg.WriteCoilRequest;
import com.ghgande.j2mod.modbus.msg.WriteCoilResponse;
import com.ghgande.j2mod.modbus.msg.WriteFileRecordRequest;
import com.ghgande.j2mod.modbus.msg.WriteFileRecordResponse;
import com.ghgande.j2mod.modbus.msg.WriteMultipleCoilsRequest;
import com.ghgande.j2mod.modbus.msg.WriteMultipleCoilsResponse;
import com.ghgande.j2mod.modbus.msg.WriteMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.WriteMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterResponse;
import com.ghgande.j2mod.modbus.net.TCPMasterConnection;
import com.ghgande.j2mod.modbus.net.UDPMasterConnection;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@code client}: one bounded operation, one result document. */
final class ClientCommand {

    /** Every operation of the contract but {@code raw}: the library has no class for any PDU. */
    static final List<String> OPERATIONS = List.of(
            "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
            "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
            "mask-write-register", "read-write-multiple-registers", "read-device-identification",
            "diagnostics", "report-server-id", "read-file-record", "write-file-record", "read-fifo-queue");

    static final List<String> TRANSPORTS = List.of("tcp", "udp", "rtu-over-tcp");

    private static final Set<String> COMMON =
            Set.of("host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat", "transport");

    /** Extra time on top of the socket timeout the library enforces, in case a peer trickles. */
    private static final long GRACE_MS = 2000;

    /** Read Device Identification: at most this many requests to collect one category. */
    private static final int MAX_DEVICE_ID_REQUESTS = 64;

    private ClientCommand() {}

    /** A response that arrived but is not a valid answer to the request. */
    private static final class InvalidResponse extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidResponse(String message) {
            super(message);
        }
    }

    /** The connection closed between two requests: the library would reconnect, the contract not. */
    private static final class ConnectionLost extends Exception {
        private static final long serialVersionUID = 1L;

        ConnectionLost(String message) {
            super(message);
        }
    }

    /** The device answered with an exception response. */
    private static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        final int function;
        final int code;

        Rejected(int function, int code) {
            super("exception " + code + " (function 0x" + Integer.toHexString(function) + ")");
            this.function = function;
            this.code = code;
        }
    }

    /** One request sent through the library and its response, as the library decoded it. */
    private interface Link {
        ModbusResponse execute(ModbusRequest request) throws Exception;

        void close();
    }

    /** How to run one operation on a link and report its normal response. */
    private interface Operation {
        JsonObject run(Session session) throws Exception;
    }

    /** Sends the requests of one operation and counts them. */
    private static final class Session {
        final Link link;
        final int unitId;
        int requests;

        Session(Link link, int unitId) {
            this.link = link;
            this.unitId = unitId;
        }

        /** Sends {@code request} and returns its normal response of the expected class. */
        <T extends ModbusResponse> T send(ModbusRequest request, Class<T> expected) throws Exception {
            request.setUnitID(unitId);
            requests++;
            ModbusResponse response = link.execute(request);
            int function = request.getFunctionCode() & 0xFF;
            if (response instanceof ExceptionResponse exception) {
                int got = exception.getFunctionCode() & 0xFF;
                if (got != (function | 0x80)) {
                    throw new InvalidResponse("exception response with function code " + got);
                }
                throw new Rejected(got, exception.getExceptionCode());
            }
            if (!expected.isInstance(response)) {
                throw new InvalidResponse("unexpected response: "
                        + (response == null ? "none" : response.getClass().getSimpleName()));
            }
            return expected.cast(response);
        }
    }

    static int run(List<String> tokens) throws Args.UsageException {
        if (tokens.isEmpty() || tokens.get(0).startsWith("--")) {
            throw new Args.UsageException("client needs an operation: " + String.join(", ", OPERATIONS));
        }
        String operation = tokens.get(0);
        if (operation.equals("raw")) {
            throw new Args.UsageException("raw is not supported: j2mod has no request class for an arbitrary PDU");
        }
        Set<String> flags = new HashSet<>(COMMON);
        flags.addAll(switch (operation) {
            case "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers" ->
                    Set.of("address", "quantity");
            case "write-single-coil", "write-single-register" -> Set.of("address", "value");
            case "write-multiple-coils", "write-multiple-registers" -> Set.of("address", "values");
            case "mask-write-register" -> Set.of("address", "and-mask", "or-mask");
            case "read-write-multiple-registers" ->
                    Set.of("read-address", "read-quantity", "write-address", "values");
            case "read-device-identification" -> Set.of("category");
            case "diagnostics" -> Set.of("sub-function", "data");
            case "report-server-id" -> Set.of();
            case "read-file-record" -> Set.of("file", "record", "length");
            case "write-file-record" -> Set.of("file", "record", "values");
            case "read-fifo-queue" -> Set.of("address");
            default -> throw new Args.UsageException("unknown operation: " + operation);
        });
        Args args = new Args(tokens.subList(1, tokens.size()), flags);

        String transport = args.transport(TRANSPORTS);
        String host = args.required("host");
        if (host.isEmpty()) {
            throw new Args.UsageException("--host is empty");
        }
        int port = args.number("port", 1502, 1, 65535);
        int unitId = args.number("unit-id", 1, 0, 255);
        int connectTimeoutMs = args.number("connect-timeout-ms", 5000, 1, 3_600_000);
        int timeoutMs = args.number("timeout-ms", 5000, 1, 3_600_000);
        int repeat = args.number("repeat", 1, 1, 1000);
        Operation call = operation(operation, args);

        // ---- from here on there is exactly one result document ----
        long start = System.nanoTime();
        boolean connected = false;
        int responses = 0;
        JsonObject response = null;
        JsonObject exception = null;
        String errorCode = null;
        String errorMessage = null;
        Session session = null;
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "modbus-interop-client");
            thread.setDaemon(true);
            return thread;
        });

        Link link = null;
        try {
            InetAddress address = InetAddress.getByName(host);
            link = switch (transport) {
                case "udp" -> udp(address, port, timeoutMs);
                default -> tcp(address, port, connectTimeoutMs, timeoutMs, transport.equals("rtu-over-tcp"));
            };
            connected = true;
            session = new Session(link, unitId);
            for (int i = 0; errorCode == null && i < repeat; i++) {
                Session current = session;
                Future<JsonObject> pending = worker.submit(() -> call.run(current));
                try {
                    JsonObject got = pending.get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS);
                    responses++;
                    if (response != null && !response.equals(got)) {
                        errorCode = "failed";
                        errorMessage = "response " + (i + 1) + " differs from the one before";
                    }
                    response = got;
                } catch (TimeoutException e) {
                    errorCode = "timeout";
                    errorMessage = "no response within " + timeoutMs + " ms";
                    link.close();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    errorMessage = describe(cause);
                    errorCode = classify(cause);
                    if (cause instanceof Rejected rejected) {
                        exception = new JsonObject();
                        exception.addProperty("function", rejected.function);
                        exception.addProperty("code", rejected.code);
                    }
                }
            }
        } catch (UnknownHostException e) {
            errorCode = "connect-failed";
            errorMessage = "cannot resolve " + host + ": " + describe(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCode = "failed";
            errorMessage = "interrupted";
        } catch (Exception e) {
            errorCode = connected ? "failed" : "connect-failed";
            errorMessage = (connected ? "" : "cannot connect to " + host + ":" + port + ": ") + describe(e);
        }

        boolean ok = errorCode == null;
        JsonObject doc = new JsonObject();
        doc.addProperty("schemaVersion", "1.0");
        doc.addProperty("adapter", Main.ADAPTER);
        doc.addProperty("operation", operation);
        doc.addProperty("ok", ok);
        if (ok) {
            doc.add("error", JsonNull.INSTANCE);
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("code", errorCode);
            error.addProperty("message", errorMessage == null ? errorCode : errorMessage);
            doc.add("error", error);
            System.err.println("modbus-interop: " + operation + ": " + errorCode + ": " + errorMessage);
        }
        doc.addProperty("connected", connected);
        doc.addProperty("unitId", unitId);
        doc.addProperty("requests", session == null ? 0 : session.requests);
        doc.addProperty("responses", responses);
        doc.add("response", response == null ? JsonNull.INSTANCE : response);
        doc.add("exception", exception == null ? JsonNull.INSTANCE : exception);
        doc.addProperty("elapsedMs", Math.round((System.nanoTime() - start) / 1000.0) / 1000.0);
        new Out().line(doc);

        if (link != null) {
            link.close();
        }
        worker.shutdownNow();
        if (ok) {
            return Main.EXIT_OK;
        }
        return "connect-failed".equals(errorCode) ? Main.EXIT_CONNECT : Main.EXIT_FAILED;
    }

    // ---- the links ------------------------------------------------------------------------------

    /**
     * Modbus TCP, or RTU frames on TCP: the library's {@link TCPMasterConnection} and one {@link
     * ModbusTCPTransaction} per request.
     *
     * <p>The library retries a transaction {@code retries} times, and 0 means its default of 5:
     * {@code setRetries(1)} is exactly one attempt. A transaction connects again when it finds the
     * connection closed; the link fails the operation instead, so nothing is sent on a second
     * connection behind its back.
     */
    private static Link tcp(InetAddress address, int port, int connectTimeoutMs, int timeoutMs, boolean rtu)
            throws Exception {
        TCPMasterConnection connection = new TCPMasterConnection(address);
        connection.setPort(port);
        // One timeout serves both the connect and every read; set it to each in turn.
        connection.setTimeout(connectTimeoutMs);
        connection.connect(rtu);
        connection.setTimeout(timeoutMs);
        return new Link() {
            @Override
            public ModbusResponse execute(ModbusRequest request) throws Exception {
                if (!connection.isConnected()) {
                    throw new ConnectionLost("the connection closed before the request");
                }
                ModbusTCPTransaction transaction = new ModbusTCPTransaction(connection);
                transaction.setRetries(1);
                transaction.setReconnecting(false);
                return run(transaction, request);
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }

    /**
     * MBAP in UDP datagrams: the library's {@link UDPMasterConnection} and one {@link
     * ModbusUDPTransaction} per request. Its retry loop runs {@code retries + 1} times, so 0 is
     * one attempt. After a datagram that did not come back it returns without an exception and
     * without a response: that is a timeout.
     */
    private static Link udp(InetAddress address, int port, int timeoutMs) throws Exception {
        UDPMasterConnection connection = new UDPMasterConnection(address);
        connection.setPort(port);
        connection.setTimeout(timeoutMs);
        connection.connect();
        return new Link() {
            @Override
            public ModbusResponse execute(ModbusRequest request) throws Exception {
                ModbusUDPTransaction transaction = new ModbusUDPTransaction(connection);
                transaction.setRetries(0);
                ModbusResponse response = run(transaction, request);
                if (response == null) {
                    throw new java.net.SocketTimeoutException("no datagram came back");
                }
                return response;
            }

            @Override
            public void close() {
                connection.close();
            }
        };
    }

    /**
     * Executes one transaction. The library reports an exception response by throwing; its
     * response object is still the transaction's, so the caller sees function and code.
     */
    private static ModbusResponse run(ModbusTransaction transaction, ModbusRequest request) throws ModbusException {
        transaction.setRequest(request);
        try {
            transaction.execute();
        } catch (ModbusSlaveException e) {
            if (!(transaction.getResponse() instanceof ExceptionResponse)) {
                throw e;
            }
        }
        return transaction.getResponse();
    }

    /**
     * The error code of a failed request. The library reports every transport failure as a
     * {@link ModbusIOException} with a message and no cause, so the message tells them apart.
     */
    private static String classify(Throwable e) {
        if (e instanceof Rejected) {
            return "exception";
        }
        if (e instanceof InvalidResponse) {
            return "invalid-response";
        }
        if (e instanceof ConnectionLost) {
            return "connection-lost";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (e instanceof ModbusIOException) {
            String message = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("timeout") || message.contains("timed out")) {
                return "timeout";
            }
            if (message.contains("premature end of stream") || message.contains("connection reset")
                    || message.contains("broken pipe") || message.contains("socket closed")
                    || message.contains("failed to write") || message.contains("end of file")) {
                return "connection-lost";
            }
            // A transaction identifier that does not match, or a decoder that ran out of bytes.
            return "invalid-response";
        }
        if (e instanceof ModbusException || e instanceof RuntimeException) {
            // The UDP transaction's unit-identifier and function-code check, or a decoder that
            // met a malformed response.
            return "invalid-response";
        }
        return "failed";
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // ---- the operations -------------------------------------------------------------------------

    private static Operation operation(String operation, Args args) throws Args.UsageException {
        return switch (operation) {
            case "read-coils", "read-discrete-inputs" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 2000);
                boolean coils = operation.equals("read-coils");
                yield session -> {
                    BitVector bits = coils
                            ? session.send(new ReadCoilsRequest(address, quantity), ReadCoilsResponse.class).getCoils()
                            : session.send(new ReadInputDiscretesRequest(address, quantity),
                                    ReadInputDiscretesResponse.class).getDiscretes();
                    if (bits.byteSize() != (quantity + 7) / 8) {
                        throw new InvalidResponse(bits.byteSize() + " data bytes for " + quantity + " bits");
                    }
                    JsonArray values = new JsonArray();
                    for (int i = 0; i < quantity; i++) {
                        values.add(bits.getBit(i));
                    }
                    JsonObject response = response(coils ? 1 : 2);
                    response.add("bits", values);
                    return response;
                };
            }
            case "read-holding-registers" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 125);
                yield session -> {
                    ReadMultipleRegistersResponse r = session.send(
                            new ReadMultipleRegistersRequest(address, quantity), ReadMultipleRegistersResponse.class);
                    count(r.getByteCount(), quantity);
                    JsonArray values = new JsonArray();
                    for (int i = 0; i < quantity; i++) {
                        values.add(r.getRegisterValue(i));
                    }
                    JsonObject response = response(3);
                    response.add("registers", values);
                    return response;
                };
            }
            case "read-input-registers" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 125);
                yield session -> {
                    ReadInputRegistersResponse r = session.send(
                            new ReadInputRegistersRequest(address, quantity), ReadInputRegistersResponse.class);
                    count(r.getByteCount(), quantity);
                    JsonArray values = new JsonArray();
                    for (int i = 0; i < quantity; i++) {
                        values.add(r.getRegisterValue(i));
                    }
                    JsonObject response = response(4);
                    response.add("registers", values);
                    return response;
                };
            }
            case "write-single-coil" -> {
                int address = args.number("address", 0, 65535);
                boolean value = args.bool("value");
                yield session -> {
                    WriteCoilResponse r = session.send(new WriteCoilRequest(address, value), WriteCoilResponse.class);
                    // The library decodes the echoed value to on (first byte 0xFF) or off.
                    echo("address", address, r.getReference());
                    echo("value", value ? 1 : 0, r.getCoil() ? 1 : 0);
                    JsonObject response = response(5);
                    response.addProperty("address", r.getReference());
                    response.addProperty("value", r.getCoil());
                    return response;
                };
            }
            case "write-single-register" -> {
                int address = args.number("address", 0, 65535);
                int value = args.number("value", 0, 65535);
                yield session -> {
                    WriteSingleRegisterResponse r = session.send(
                            new WriteSingleRegisterRequest(address, new SimpleRegister(value)),
                            WriteSingleRegisterResponse.class);
                    echo("address", address, r.getReference());
                    echo("value", value, r.getRegisterValue());
                    JsonObject response = response(6);
                    response.addProperty("address", r.getReference());
                    response.addProperty("value", r.getRegisterValue());
                    return response;
                };
            }
            case "write-multiple-coils" -> {
                int address = args.number("address", 0, 65535);
                boolean[] values = args.bools("values", 1, 1968);
                BitVector bits = new BitVector(values.length);
                for (int i = 0; i < values.length; i++) {
                    bits.setBit(i, values[i]);
                }
                yield session -> {
                    WriteMultipleCoilsResponse r = session.send(
                            new WriteMultipleCoilsRequest(address, bits), WriteMultipleCoilsResponse.class);
                    echo("address", address, r.getReference());
                    echo("quantity", values.length, r.getBitCount());
                    JsonObject response = response(15);
                    response.addProperty("address", r.getReference());
                    response.addProperty("quantity", r.getBitCount());
                    return response;
                };
            }
            case "write-multiple-registers" -> {
                int address = args.number("address", 0, 65535);
                int[] values = args.numbers("values", 1, 123, 0, 65535);
                yield session -> {
                    WriteMultipleRegistersResponse r = session.send(
                            new WriteMultipleRegistersRequest(address, registers(values)),
                            WriteMultipleRegistersResponse.class);
                    echo("address", address, r.getReference());
                    echo("quantity", values.length, r.getWordCount());
                    JsonObject response = response(16);
                    response.addProperty("address", r.getReference());
                    response.addProperty("quantity", r.getWordCount());
                    return response;
                };
            }
            case "mask-write-register" -> {
                int address = args.number("address", 0, 65535);
                int andMask = args.number("and-mask", 0, 65535);
                int orMask = args.number("or-mask", 0, 65535);
                yield session -> {
                    MaskWriteRegisterResponse r = session.send(
                            new MaskWriteRegisterRequest(address, andMask, orMask), MaskWriteRegisterResponse.class);
                    echo("address", address, r.getReference());
                    echo("and mask", andMask, r.getAndMask());
                    echo("or mask", orMask, r.getOrMask());
                    JsonObject response = response(22);
                    response.addProperty("address", r.getReference());
                    response.addProperty("andMask", r.getAndMask());
                    response.addProperty("orMask", r.getOrMask());
                    return response;
                };
            }
            case "read-write-multiple-registers" -> {
                int readAddress = args.number("read-address", 0, 65535);
                int readQuantity = args.number("read-quantity", 1, 125);
                int writeAddress = args.number("write-address", 0, 65535);
                int[] values = args.numbers("values", 1, 121, 0, 65535);
                yield session -> {
                    ReadWriteMultipleRequest request = new ReadWriteMultipleRequest(
                            session.unitId, readAddress, readQuantity, writeAddress, values.length);
                    request.setRegisters(registers(values));
                    ReadWriteMultipleResponse r = session.send(request, ReadWriteMultipleResponse.class);
                    count(r.getByteCount(), readQuantity);
                    JsonArray read = new JsonArray();
                    for (int i = 0; i < readQuantity; i++) {
                        read.add(r.getRegisterValue(i));
                    }
                    JsonObject response = response(23);
                    response.add("registers", read);
                    return response;
                };
            }
            case "read-device-identification" -> {
                int category = switch (args.string("category", "basic")) {
                    case "basic" -> 1;
                    case "regular" -> 2;
                    case "extended" -> 3;
                    default -> throw new Args.UsageException("--category: basic, regular or extended");
                };
                yield session -> readDeviceIdentification(session, category);
            }
            case "diagnostics" -> {
                int subFunction = args.number("sub-function", 0, 65535);
                String hex = args.required("data");
                // The library's FC08 request carries exactly one data word.
                if (!hex.matches("[0-9a-fA-F]{4}")) {
                    throw new Args.UsageException(
                            "--data: j2mod's diagnostics request carries exactly one data word (4 hexadecimal digits)");
                }
                int data = Integer.parseInt(hex, 16);
                yield session -> {
                    ReadSerialDiagnosticsRequest request = new ReadSerialDiagnosticsRequest();
                    request.setFunction(subFunction);
                    request.setData(data);
                    ReadSerialDiagnosticsResponse r = session.send(request, ReadSerialDiagnosticsResponse.class);
                    JsonObject response = response(8);
                    response.addProperty("subFunction", r.getFunction());
                    response.addProperty("data", "%04x".formatted(r.getData() & 0xFFFF));
                    return response;
                };
            }
            case "report-server-id" -> session -> {
                ReportSlaveIDResponse r = session.send(new ReportSlaveIDRequest(), ReportSlaveIDResponse.class);
                // The library decodes the data as a one-byte server ID, the run indicator and the
                // additional data, and has a getter for the run indicator as a boolean only. Its
                // own encoding of what it decoded (getMessage: byte count, server ID, run
                // indicator, additional data) gives the two bytes as they arrived.
                byte[] encoded = r.getMessage();
                byte[] additional = r.getData();
                byte[] data = new byte[2 + additional.length];
                data[0] = encoded[1];
                data[1] = encoded[2];
                System.arraycopy(additional, 0, data, 2, additional.length);
                JsonObject response = response(17);
                response.addProperty("data", HexFormat.of().formatHex(data));
                return response;
            };
            case "read-file-record" -> {
                int file = args.number("file", 1, 65535);
                int record = args.number("record", 0, 9999);
                int length = args.number("length", 1, 121);
                yield session -> {
                    ReadFileRecordRequest request = new ReadFileRecordRequest();
                    request.addRequest(new ReadFileRecordRequest.RecordRequest(file, record, length));
                    ReadFileRecordResponse r = session.send(request, ReadFileRecordResponse.class);
                    if (r.getRecordCount() != 1) {
                        throw new InvalidResponse(r.getRecordCount() + " sub-responses for one sub-request");
                    }
                    ReadFileRecordResponse.RecordResponse sub = r.getRecord(0);
                    if (sub.getWordCount() != length) {
                        throw new InvalidResponse(sub.getWordCount() + " registers for a record length of " + length);
                    }
                    JsonArray values = new JsonArray();
                    for (int i = 0; i < length; i++) {
                        values.add(sub.getRegister(i).getValue());
                    }
                    JsonObject response = response(20);
                    response.add("registers", values);
                    return response;
                };
            }
            case "write-file-record" -> {
                int file = args.number("file", 1, 65535);
                int record = args.number("record", 0, 9999);
                int[] values = args.numbers("values", 1, 122, 0, 65535);
                short[] words = new short[values.length];
                for (int i = 0; i < values.length; i++) {
                    words[i] = (short) values[i];
                }
                yield session -> {
                    WriteFileRecordRequest request = new WriteFileRecordRequest();
                    request.addRequest(new WriteFileRecordRequest.RecordRequest(file, record, words));
                    WriteFileRecordResponse r = session.send(request, WriteFileRecordResponse.class);
                    // The normal response is the echo of the request; the library decodes it and
                    // leaves the comparison to the caller.
                    if (r.getRequestCount() != 1) {
                        throw new InvalidResponse(r.getRequestCount() + " sub-responses for one sub-request");
                    }
                    WriteFileRecordResponse.RecordResponse sub = r.getRecord(0);
                    echo("file", file, sub.getFileNumber());
                    echo("record", record, sub.getRecordNumber());
                    echo("record length", values.length, sub.getWordCount());
                    for (int i = 0; i < values.length; i++) {
                        echo("register " + i, values[i], sub.getRegister(i).getValue());
                    }
                    JsonObject response = response(21);
                    response.addProperty("file", sub.getFileNumber());
                    response.addProperty("record", sub.getRecordNumber());
                    response.addProperty("quantity", sub.getWordCount());
                    return response;
                };
            }
            case "read-fifo-queue" -> {
                int address = args.number("address", 0, 65535);
                yield session -> {
                    ReadFIFOQueueRequest request = new ReadFIFOQueueRequest();
                    request.setReference(address);
                    ReadFIFOQueueResponse r = session.send(request, ReadFIFOQueueResponse.class);
                    JsonArray values = new JsonArray();
                    for (int value : r.getRegisters()) {
                        values.add(value);
                    }
                    JsonObject response = response(24);
                    response.add("registers", values);
                    return response;
                };
            }
            default -> throw new Args.UsageException("unknown operation: " + operation);
        };
    }

    /**
     * FC43/14, stream access from object 0, following "more follows" until the category is
     * complete. {@link ReadMEIResponse} has getters for the objects but none for the conformity
     * level, "more follows" and the next object id; its own encoding of what it decoded
     * ({@code getMessage}: MEI type, read device id code, conformity level, more follows, next
     * object id, number of objects, the objects) has them.
     */
    private static JsonObject readDeviceIdentification(Session session, int category) throws Exception {
        TreeMap<Integer, String> objects = new TreeMap<>();
        int conformity = -1;
        int next = 0;
        for (int i = 0; ; i++) {
            if (i == MAX_DEVICE_ID_REQUESTS) {
                throw new InvalidResponse("more follows after " + MAX_DEVICE_ID_REQUESTS + " requests");
            }
            ReadMEIResponse r = session.send(new ReadMEIRequest(category, next), ReadMEIResponse.class);
            byte[] header = r.getMessage();
            if (header.length < 6 || (header[1] & 0xFF) != category) {
                throw new InvalidResponse("response for read device id code " + (header.length < 2 ? -1 : header[1] & 0xFF));
            }
            conformity = header[2] & 0xFF;
            boolean more = (header[3] & 0xFF) == 0xFF;
            int count = header[5] & 0xFF;
            for (int k = 0; k < count; k++) {
                objects.put(r.getFieldId(k), r.getField(k));
            }
            if (!more) {
                break;
            }
            int following = header[4] & 0xFF;
            if (following <= next && i > 0) {
                throw new InvalidResponse("more follows with next object id " + following + " again");
            }
            next = following;
        }
        JsonArray list = new JsonArray();
        for (var object : objects.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", object.getKey());
            item.addProperty("value", object.getValue());
            list.add(item);
        }
        JsonObject response = response(43);
        response.addProperty("conformityLevel", conformity);
        response.add("objects", list);
        return response;
    }

    private static JsonObject response(int function) {
        JsonObject response = new JsonObject();
        response.addProperty("function", function);
        return response;
    }

    /** The library decodes a write response without comparing it with the request. */
    private static void echo(String what, int sent, int received) throws InvalidResponse {
        if (sent != received) {
            throw new InvalidResponse("the response echoes " + what + " " + received + ", the request had " + sent);
        }
    }

    /** The library sizes a register response by its byte count, which must match the request. */
    private static void count(int byteCount, int quantity) throws InvalidResponse {
        if (byteCount != quantity * 2) {
            throw new InvalidResponse(byteCount + " data bytes for " + quantity + " registers");
        }
    }

    private static Register[] registers(int[] values) {
        Register[] registers = new Register[values.length];
        for (int i = 0; i < values.length; i++) {
            registers[i] = new SimpleRegister(values[i]);
        }
        return registers;
    }
}
