// SPDX-License-Identifier: GPL-3.0-or-later
package org.otfabric.modbusinterop.modbus4j;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.serotonin.modbus4j.ModbusMaster;
import com.serotonin.modbus4j.exception.IllegalFunctionException;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.exception.ModbusTransportException;
import com.serotonin.modbus4j.exception.SlaveIdNotEqual;
import com.serotonin.modbus4j.ip.IpParameters;
import com.serotonin.modbus4j.ip.tcp.TcpMaster;
import com.serotonin.modbus4j.ip.udp.UdpMaster;
import com.serotonin.modbus4j.msg.ModbusRequest;
import com.serotonin.modbus4j.msg.ModbusResponse;
import com.serotonin.modbus4j.msg.ReadCoilsRequest;
import com.serotonin.modbus4j.msg.ReadCoilsResponse;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsRequest;
import com.serotonin.modbus4j.msg.ReadDiscreteInputsResponse;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersRequest;
import com.serotonin.modbus4j.msg.ReadHoldingRegistersResponse;
import com.serotonin.modbus4j.msg.ReadInputRegistersRequest;
import com.serotonin.modbus4j.msg.ReadInputRegistersResponse;
import com.serotonin.modbus4j.msg.ReadResponse;
import com.serotonin.modbus4j.msg.ReportSlaveIdRequest;
import com.serotonin.modbus4j.msg.ReportSlaveIdResponse;
import com.serotonin.modbus4j.msg.WriteCoilRequest;
import com.serotonin.modbus4j.msg.WriteCoilResponse;
import com.serotonin.modbus4j.msg.WriteCoilsRequest;
import com.serotonin.modbus4j.msg.WriteCoilsResponse;
import com.serotonin.modbus4j.msg.WriteMaskRegisterRequest;
import com.serotonin.modbus4j.msg.WriteMaskRegisterResponse;
import com.serotonin.modbus4j.msg.WriteRegisterRequest;
import com.serotonin.modbus4j.msg.WriteRegisterResponse;
import com.serotonin.modbus4j.msg.WriteRegistersRequest;
import com.serotonin.modbus4j.msg.WriteRegistersResponse;
import com.serotonin.modbus4j.sero.log.BaseIOLog;
import com.serotonin.modbus4j.sero.messaging.TimeoutException;
import com.serotonin.modbus4j.sero.messaging.WaitingRoomException;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code client}: one bounded operation, one result document. tcp is {@code TcpMaster} (MBAP),
 * rtu-over-tcp {@code TcpMaster} with {@code IpParameters.setEncapsulated(true)}, udp
 * {@code UdpMaster}. Requests go out with {@code ModbusMaster.send(ModbusRequest)}.
 */
final class ClientCommand {

    /**
     * The operations of the contract the library has no message for, with their function code: they
     * are usage errors and nothing is sent (REQUIREMENTS.md R2.4, no codecs in the adapter).
     */
    private static final Map<String, Integer> MISSING = Map.of(
            "read-write-multiple-registers", 23,
            "read-device-identification", 43,
            "diagnostics", 8,
            "read-file-record", 20,
            "write-file-record", 21,
            "read-fifo-queue", 24,
            "raw", -1);

    /** The operations the library has. */
    static final List<String> OPERATIONS = List.of(
            "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
            "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
            "mask-write-register", "report-server-id");

    private static final Set<String> COMMON =
            Set.of("host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat", "transport");

    /** Extra time on top of the timeouts the library enforces itself, in case it does not. */
    private static final long GRACE_MS = 2000;

    private ClientCommand() {}

    /** A response that arrived but is not a valid answer to the request. */
    private static final class InvalidResponse extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidResponse(String message) {
            super(message);
        }
    }

    /** One request: the library's message and how to report its normal response. */
    private interface Call {
        ModbusRequest request(int unitId) throws ModbusTransportException;

        int function();

        JsonObject response(ModbusResponse answer) throws InvalidResponse;
    }

    /**
     * The library's I/O log, the one place where a TcpMaster shows what it writes and reads: it
     * counts the requests that went out (the library may send one again on its own, see the
     * README) and records nothing else. UdpMaster does not use it.
     */
    private static final class Wire extends BaseIOLog {
        final AtomicInteger outputs = new AtomicInteger();

        Wire() {
            super(new File("/dev/null"));
        }

        @Override
        public void log(boolean input, byte[] b, int pos, int len) {
            if (!input) {
                outputs.incrementAndGet();
            }
        }

        @Override
        protected void sizeCheck() {}
    }

    static int run(List<String> tokens) throws Args.UsageException {
        if (tokens.isEmpty() || tokens.get(0).startsWith("--")) {
            throw new Args.UsageException("client needs an operation: " + String.join(", ", OPERATIONS));
        }
        String operation = tokens.get(0);
        Integer missing = MISSING.get(operation);
        if (missing != null) {
            throw new Args.UsageException(operation + " is not supported: modbus4j has "
                    + (missing < 0 ? "no way to send an arbitrary PDU" : "no function " + missing));
        }
        Set<String> flags = new HashSet<>(COMMON);
        flags.addAll(switch (operation) {
            case "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers" ->
                    Set.of("address", "quantity");
            case "write-single-coil", "write-single-register" -> Set.of("address", "value");
            case "write-multiple-coils", "write-multiple-registers" -> Set.of("address", "values");
            case "mask-write-register" -> Set.of("address", "and-mask", "or-mask");
            case "report-server-id" -> Set.of();
            default -> throw new Args.UsageException("unknown operation: " + operation);
        });
        Args args = new Args(tokens.subList(1, tokens.size()), flags);

        String transport = Args.transport(args);
        String host = args.required("host");
        if (host.isEmpty()) {
            throw new Args.UsageException("--host is empty");
        }
        int port = args.number("port", 1502, 1, 65535);
        int unitId = args.number("unit-id", 1, 0, 255);
        int connectTimeoutMs = args.number("connect-timeout-ms", 5000, 1, 3_600_000);
        int timeoutMs = args.number("timeout-ms", 5000, 1, 3_600_000);
        int repeat = args.number("repeat", 1, 1, 1000);
        Call call = call(operation, args);
        boolean udp = transport.equals("udp");

        // ---- from here on there is exactly one result document ----
        long start = System.nanoTime();
        JsonObject doc = new JsonObject();
        doc.addProperty("schemaVersion", "1.0");
        doc.addProperty("adapter", Main.ADAPTER);
        doc.addProperty("operation", operation);
        boolean connected = false;
        int requests = 0;
        int responses = 0;
        JsonObject response = null;
        JsonObject exception = null;
        String errorCode = null;
        String errorMessage = null;

        IpParameters params = new IpParameters();
        params.setHost(host);
        params.setPort(port);
        params.setEncapsulated(transport.equals("rtu-over-tcp"));
        // keepAlive: one connection for all the requests of --repeat. Without it TcpMaster opens a
        // connection for every request and closes it after the response, which for one request
        // is exactly "connect, send, wait, close". With it, the library sends a request that
        // failed once more on a new connection (README.md, findings), so it is used only where
        // the contract needs the connection to stay: --repeat.
        boolean keepAlive = repeat > 1;
        // validateResponse (the unit identifier of the response): UdpMaster honours it, TcpMaster
        // takes the argument and drops it (README.md, findings).
        ModbusMaster master = udp ? new UdpMaster(params, true) : new TcpMaster(params, keepAlive, true, true);
        // One timeout, used by the library for the TCP connect and for the wait for each response.
        master.setTimeout(timeoutMs);
        // retries counts the attempts after the first: 0 is one attempt.
        master.setRetries(0);
        // The library's default for FC16 is 120 registers; the protocol allows 123 (V1.1b3, 6.12).
        master.setMaxWriteRegisterCount(123);
        Wire wire = new Wire();
        master.setIoLog(wire);
        // A response the library throws away (wrong transaction or unit identifier, wrong function,
        // bad CRC) is reported here and nowhere else; the request then times out.
        AtomicInteger rejected = new AtomicInteger();
        String[] lastRejected = {null};
        master.setExceptionHandler(e -> {
            if (e instanceof ModbusTransportException || e instanceof WaitingRoomException) {
                rejected.incrementAndGet();
                lastRejected[0] = String.valueOf(e.getMessage());
            }
            System.err.println("modbus-interop: modbus4j: " + e);
        });
        ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "modbus-interop-request");
            t.setDaemon(true);
            return t;
        });

        try {
            ModbusRequest request = call.request(unitId);
            // The library validates a request before it sends it; done here first, so that a request
            // it refuses is reported before any connection is made.
            try {
                request.validate(master);
            } catch (ModbusTransportException e) {
                throw new IllegalArgumentException("modbus4j refuses the request: " + e.getMessage(), e);
            }

            // ---- connect ----
            // UdpMaster.init opens the socket; TcpMaster.init connects only with keepAlive (otherwise
            // send() connects). Both are bounded by --connect-timeout-ms here and by the library's
            // timeout inside.
            Future<?> init = worker.submit(() -> {
                master.init();
                return null;
            });
            try {
                init.get(connectTimeoutMs + GRACE_MS, TimeUnit.MILLISECONDS);
                connected = udp || keepAlive;
            } catch (ExecutionException e) {
                errorCode = "connect-failed";
                errorMessage = "cannot connect to " + host + ":" + port + ": " + describe(root(e));
            } catch (java.util.concurrent.TimeoutException e) {
                errorCode = "connect-failed";
                errorMessage = "no connection to " + host + ":" + port + " after " + connectTimeoutMs + " ms";
            }

            for (int i = 0; errorCode == null && i < repeat; i++) {
                int sentBefore = wire.outputs.get();
                rejected.set(0);
                Future<ModbusResponse> pending = worker.submit(() -> master.send(request));
                ModbusResponse answer = null;
                Throwable failure = null;
                long begin = System.nanoTime();
                boolean gaveUpConnecting = false;
                try {
                    if (!udp && !keepAlive) {
                        // The connect happens inside send(): it is over when the request is written.
                        while (!pending.isDone() && wire.outputs.get() == sentBefore) {
                            if (System.nanoTime() - begin > TimeUnit.MILLISECONDS.toNanos(connectTimeoutMs + GRACE_MS)) {
                                gaveUpConnecting = true;
                                break;
                            }
                            Thread.sleep(1);
                        }
                    }
                    if (!gaveUpConnecting) {
                        // keepAlive: a failed request is sent once more on a new connection.
                        long bound = (keepAlive ? 2L : 1L) * timeoutMs + connectTimeoutMs + GRACE_MS;
                        answer = pending.get(bound, TimeUnit.MILLISECONDS);
                    }
                } catch (ExecutionException e) {
                    failure = root(e);
                } catch (java.util.concurrent.TimeoutException e) {
                    failure = e;
                }
                int sent = udp ? 1 : wire.outputs.get() - sentBefore;
                if (udp && failure != null && hasCause(failure, UnknownHostException.class)) {
                    sent = 0;
                }
                requests += sent;
                if (!udp && !keepAlive && sent > 0) {
                    connected = true;
                }

                if (gaveUpConnecting || (failure != null && sent == 0)) {
                    errorCode = "connect-failed";
                    errorMessage = gaveUpConnecting
                            ? "no connection to " + host + ":" + port + " after " + connectTimeoutMs + " ms"
                            : "cannot connect to " + host + ":" + port + ": " + describe(failure);
                    break;
                }
                if (failure != null) {
                    errorMessage = describe(failure);
                    if (failure instanceof java.util.concurrent.TimeoutException
                            || hasCause(failure, TimeoutException.class)
                            || hasCause(failure, SocketTimeoutException.class)) {
                        if (rejected.get() > 0) {
                            errorCode = "invalid-response";
                            errorMessage = "modbus4j discarded the response: " + lastRejected[0];
                        } else {
                            errorCode = "timeout";
                        }
                    } else if (failure instanceof SlaveIdNotEqual || failure instanceof IllegalFunctionException
                            || (failure instanceof ModbusTransportException && failure.getCause() == null)) {
                        // SlaveIdNotEqual: UdpMaster's unit identifier check. IllegalFunctionException:
                        // a function code the response parser does not know. Without a cause: "Invalid
                        // response received" (UdpMaster, a datagram too short to parse).
                        errorCode = "invalid-response";
                    } else if (hasCause(failure, IOException.class)) {
                        errorCode = "connection-lost";
                    } else if (failure instanceof ModbusTransportException) {
                        // the parser threw on a malformed datagram (UdpMaster)
                        errorCode = "invalid-response";
                    } else {
                        errorCode = "failed";
                    }
                    break;
                }
                if (answer == null) {
                    // Unit identifier 0: the library sends a broadcast and does not wait for an answer.
                    errorCode = "failed";
                    errorMessage = "modbus4j sent a broadcast (unit identifier 0) and waits for no response";
                    break;
                }
                if (answer.isException()) {
                    errorCode = "exception";
                    errorMessage = "exception " + (answer.getExceptionCode() & 0xFF) + ": " + answer.getExceptionMessage();
                    exception = new JsonObject();
                    exception.addProperty("function", call.function() | 0x80);
                    exception.addProperty("code", answer.getExceptionCode() & 0xFF);
                    break;
                }
                JsonObject current;
                try {
                    current = call.response(answer);
                } catch (InvalidResponse e) {
                    errorCode = "invalid-response";
                    errorMessage = e.getMessage();
                    break;
                }
                responses++;
                if (response != null && !response.equals(current)) {
                    response = current;
                    errorCode = "failed";
                    errorMessage = "response " + (i + 1) + " differs from the one before";
                } else {
                    response = current;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCode = "failed";
            errorMessage = "interrupted";
        } catch (ModbusTransportException | IllegalArgumentException e) {
            errorCode = "failed";
            errorMessage = describe(e);
        } catch (RuntimeException e) {
            errorCode = "failed";
            errorMessage = describe(e);
        }

        boolean ok = errorCode == null;
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
        doc.addProperty("requests", requests);
        doc.addProperty("responses", responses);
        doc.add("response", response == null ? JsonNull.INSTANCE : response);
        doc.add("exception", exception == null ? JsonNull.INSTANCE : exception);
        doc.addProperty("elapsedMs", Math.round((System.nanoTime() - start) / 1000.0) / 1000.0);
        new Out().line(doc);

        Future<?> closing = worker.submit(master::destroy);
        try {
            closing.get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("modbus-interop: closing: " + e);
        }
        worker.shutdownNow();
        if (ok) {
            return Main.EXIT_OK;
        }
        return "connect-failed".equals(errorCode) ? Main.EXIT_CONNECT : Main.EXIT_FAILED;
    }

    private static Throwable root(Throwable e) {
        Throwable cause = e;
        while (cause instanceof ExecutionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ModbusInitException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String text = message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
        return cause == e ? text : describeTop(e) + ": " + text;
    }

    private static String describeTop(Throwable e) {
        return e.getClass().getSimpleName();
    }

    // ---- the operations -------------------------------------------------------------------------

    private interface Builder {
        ModbusRequest build(int unitId) throws ModbusTransportException;
    }

    private interface Reader {
        JsonObject read(ModbusResponse answer) throws InvalidResponse;
    }

    private static Call typed(int function, Builder builder, Reader reader) {
        return new Call() {
            @Override
            public ModbusRequest request(int unitId) throws ModbusTransportException {
                return builder.build(unitId);
            }

            @Override
            public int function() {
                return function;
            }

            @Override
            public JsonObject response(ModbusResponse answer) throws InvalidResponse {
                return reader.read(answer);
            }
        };
    }

    private static Call call(String operation, Args args) throws Args.UsageException {
        return switch (operation) {
            case "read-coils", "read-discrete-inputs" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 2000);
                boolean coils = operation.equals("read-coils");
                int function = coils ? 1 : 2;
                yield typed(function,
                        unit -> coils ? new ReadCoilsRequest(unit, address, quantity)
                                : new ReadDiscreteInputsRequest(unit, address, quantity),
                        answer -> {
                            boolean right = coils ? answer instanceof ReadCoilsResponse
                                    : answer instanceof ReadDiscreteInputsResponse;
                            if (!right) {
                                throw unexpected(answer);
                            }
                            ReadResponse r = (ReadResponse) answer;
                            if (r.getData().length != (quantity + 7) / 8) {
                                throw new InvalidResponse(r.getData().length + " data bytes for " + quantity + " bits");
                            }
                            boolean[] all = r.getBooleanData();
                            JsonArray bits = new JsonArray();
                            for (int i = 0; i < quantity; i++) {
                                bits.add(all[i]);
                            }
                            JsonObject response = response(function);
                            response.add("bits", bits);
                            return response;
                        });
            }
            case "read-holding-registers", "read-input-registers" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 125);
                boolean holding = operation.equals("read-holding-registers");
                int function = holding ? 3 : 4;
                yield typed(function,
                        unit -> holding ? new ReadHoldingRegistersRequest(unit, address, quantity)
                                : new ReadInputRegistersRequest(unit, address, quantity),
                        answer -> {
                            boolean right = holding ? answer instanceof ReadHoldingRegistersResponse
                                    : answer instanceof ReadInputRegistersResponse;
                            if (!right) {
                                throw unexpected(answer);
                            }
                            JsonObject response = response(function);
                            response.add("registers", registers(((ReadResponse) answer).getData(), quantity));
                            return response;
                        });
            }
            case "write-single-coil" -> {
                int address = args.number("address", 0, 65535);
                boolean value = args.bool("value");
                yield typed(5, unit -> new WriteCoilRequest(unit, address, value), answer -> {
                    if (!(answer instanceof WriteCoilResponse r)) {
                        throw unexpected(answer);
                    }
                    // The library reads the echoed value as "0xFF00 or not": a value other than
                    // 0xFF00 and 0x0000 cannot be told from 0x0000 here.
                    echo("address", address, r.getWriteOffset());
                    echo("value", value ? 1 : 0, r.isWriteValue() ? 1 : 0);
                    JsonObject response = response(5);
                    response.addProperty("address", r.getWriteOffset());
                    response.addProperty("value", r.isWriteValue());
                    return response;
                });
            }
            case "write-single-register" -> {
                int address = args.number("address", 0, 65535);
                int value = args.number("value", 0, 65535);
                yield typed(6, unit -> new WriteRegisterRequest(unit, address, value), answer -> {
                    if (!(answer instanceof WriteRegisterResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.getWriteOffset());
                    echo("value", value, r.getWriteValue());
                    JsonObject response = response(6);
                    response.addProperty("address", r.getWriteOffset());
                    response.addProperty("value", r.getWriteValue());
                    return response;
                });
            }
            case "write-multiple-coils" -> {
                int address = args.number("address", 0, 65535);
                boolean[] values = args.bools("values", 1, 1968);
                yield typed(15, unit -> new WriteCoilsRequest(unit, address, values), answer -> {
                    if (!(answer instanceof WriteCoilsResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.getStartOffset());
                    echo("quantity", values.length, r.getNumberOfBits());
                    JsonObject response = response(15);
                    response.addProperty("address", r.getStartOffset());
                    response.addProperty("quantity", r.getNumberOfBits());
                    return response;
                });
            }
            case "write-multiple-registers" -> {
                int address = args.number("address", 0, 65535);
                int[] values = args.numbers("values", 1, 123, 0, 65535);
                short[] words = new short[values.length];
                for (int i = 0; i < values.length; i++) {
                    words[i] = (short) values[i];
                }
                yield typed(16, unit -> new WriteRegistersRequest(unit, address, words), answer -> {
                    if (!(answer instanceof WriteRegistersResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.getStartOffset());
                    echo("quantity", values.length, r.getNumberOfRegisters());
                    JsonObject response = response(16);
                    response.addProperty("address", r.getStartOffset());
                    response.addProperty("quantity", r.getNumberOfRegisters());
                    return response;
                });
            }
            case "mask-write-register" -> {
                int address = args.number("address", 0, 65535);
                int andMask = args.number("and-mask", 0, 65535);
                int orMask = args.number("or-mask", 0, 65535);
                yield typed(22, unit -> new WriteMaskRegisterRequest(unit, address, andMask, orMask), answer -> {
                    if (!(answer instanceof WriteMaskRegisterResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.getWriteOffset());
                    echo("and mask", andMask, r.getAndMask());
                    echo("or mask", orMask, r.getOrMask());
                    JsonObject response = response(22);
                    response.addProperty("address", r.getWriteOffset());
                    response.addProperty("andMask", r.getAndMask());
                    response.addProperty("orMask", r.getOrMask());
                    return response;
                });
            }
            case "report-server-id" -> typed(17, ReportSlaveIdRequest::new, answer -> {
                if (!(answer instanceof ReportSlaveIdResponse r)) {
                    throw unexpected(answer);
                }
                JsonObject response = response(17);
                response.addProperty("data", HexFormat.of().formatHex(r.getData()));
                return response;
            });
            default -> throw new Args.UsageException("unknown operation: " + operation);
        };
    }

    private static JsonObject response(int function) {
        JsonObject response = new JsonObject();
        response.addProperty("function", function);
        return response;
    }

    private static InvalidResponse unexpected(ModbusResponse answer) {
        return new InvalidResponse("unexpected response: " + answer.getClass().getSimpleName());
    }

    /**
     * The library decodes a write response and hands it over without comparing it with the
     * request, so the comparison is made here: an echo that differs is not the normal response.
     */
    private static void echo(String what, int sent, int received) throws InvalidResponse {
        if (sent != received) {
            throw new InvalidResponse("the response echoes " + what + " " + received + ", the request had " + sent);
        }
    }

    private static JsonArray registers(byte[] data, int quantity) throws InvalidResponse {
        if (data.length != quantity * 2) {
            throw new InvalidResponse(data.length + " data bytes for " + quantity + " registers");
        }
        JsonArray registers = new JsonArray();
        for (int i = 0; i < quantity; i++) {
            registers.add((data[2 * i] & 0xFF) << 8 | (data[2 * i + 1] & 0xFF));
        }
        return registers;
    }
}
