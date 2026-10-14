// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.exceptions.ModbusException;
import com.digitalpetri.modbus.exceptions.ModbusResponseException;
import com.digitalpetri.modbus.pdu.MaskWriteRegisterRequest;
import com.digitalpetri.modbus.pdu.MaskWriteRegisterResponse;
import com.digitalpetri.modbus.pdu.ModbusRequestPdu;
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
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** {@code client}: one bounded operation, one result document. */
final class ClientCommand {

    /**
     * The operations of the contract the library has no PDU for, with their function code: they
     * are usage errors and nothing is sent (REQUIREMENTS.md R2.4, no codecs in the adapter).
     */
    private static final Map<String, Integer> MISSING = Map.of(
            "read-device-identification", 43,
            "diagnostics", 8,
            "report-server-id", 17,
            "read-file-record", 20,
            "write-file-record", 21,
            "read-fifo-queue", 24);

    /** The operations the library has; FC08, FC17, FC20, FC21, FC24 and FC43 are absent. */
    static final List<String> OPERATIONS = List.of(
            "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
            "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
            "mask-write-register", "read-write-multiple-registers", "raw");

    private static final Set<String> COMMON =
            Set.of("host", "port", "unit-id", "connect-timeout-ms", "timeout-ms", "repeat");

    /** Extra time on top of a timeout the library enforces itself, in case it does not. */
    private static final long GRACE_MS = 2000;

    private ClientCommand() {}

    /** A response that arrived but is not a valid answer to the request. */
    private static final class InvalidResponse extends Exception {
        private static final long serialVersionUID = 1L;

        InvalidResponse(String message) {
            super(message);
        }
    }

    /** Completes the "connection lost" future the wait for a response races against. */
    private static final class ConnectionLost extends Exception {
        private static final long serialVersionUID = 1L;

        ConnectionLost() {
            super("the connection closed before the response arrived");
        }
    }

    /** One request: how to send it with the library and how to report its normal response. */
    private interface Call {
        CompletionStage<?> send(ModbusTcpClient client, int unitId);

        JsonObject response(Object answer) throws InvalidResponse, ModbusResponseException;
    }

    static int run(List<String> tokens) throws Args.UsageException {
        if (tokens.isEmpty() || tokens.get(0).startsWith("--")) {
            throw new Args.UsageException("client needs an operation: " + String.join(", ", OPERATIONS));
        }
        String operation = tokens.get(0);
        Integer missing = MISSING.get(operation);
        if (missing != null) {
            throw new Args.UsageException(
                    operation + " is not supported: digitalpetri/modbus has no function " + missing);
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
            case "raw" -> Set.of("function", "data");
            default -> throw new Args.UsageException("unknown operation: " + operation);
        });
        Args args = new Args(tokens.subList(1, tokens.size()), flags);

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

        ModbusTcpClient client = null;
        try {
            // One connection attempt and no reconnecting behind the operation's back: the
            // defaults of the transport are a persistent, eagerly reconnecting connection.
            NettyTcpClientTransport transport = NettyTcpClientTransport.create(config -> {
                config.setHostname(host);
                config.setPort(port);
                config.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
                config.setConnectPersistent(false);
                config.setReconnectLazy(true);
            });
            // The library does not fail a pending request when the connection closes: it lets
            // the request time out. Its connection listener tells the two apart.
            CompletableFuture<Object> lost = new CompletableFuture<>();
            transport.addConnectionListener(new NettyTcpClientTransport.ConnectionListener() {
                @Override
                public void onConnection() {}

                @Override
                public void onConnectionLost() {
                    lost.completeExceptionally(new ConnectionLost());
                }
            });
            ModbusTcpClient created = ModbusTcpClient.create(
                    transport, config -> config.setRequestTimeout(Duration.ofMillis(timeoutMs)));
            client = created;

            try {
                created.connectAsync().toCompletableFuture().get(connectTimeoutMs + GRACE_MS, TimeUnit.MILLISECONDS);
                connected = true;
            } catch (ExecutionException | TimeoutException e) {
                errorCode = "connect-failed";
                errorMessage = "cannot connect to " + host + ":" + port + ": " + describe(unwrap(e));
            }

            for (int i = 0; connected && errorCode == null && i < repeat; i++) {
                requests++;
                try {
                    CompletableFuture<?> pending = call.send(created, unitId).toCompletableFuture();
                    Object answer = CompletableFuture.anyOf(pending, lost)
                            .get(timeoutMs + GRACE_MS, TimeUnit.MILLISECONDS);
                    JsonObject current = call.response(answer);
                    responses++;
                    if (response != null && !response.equals(current)) {
                        response = current;
                        errorCode = "failed";
                        errorMessage = "response " + (i + 1) + " differs from the one before";
                    } else {
                        response = current;
                    }
                } catch (ExecutionException | TimeoutException | InvalidResponse | ModbusResponseException e) {
                    Throwable cause = unwrap(e);
                    errorMessage = describe(cause);
                    if (cause instanceof ModbusResponseException rejected) {
                        errorCode = "exception";
                        exception = new JsonObject();
                        exception.addProperty("function", (rejected.getFunctionCode() | 0x80) & 0xFF);
                        exception.addProperty("code", rejected.getExceptionCode());
                    } else if (cause instanceof TimeoutException) {
                        errorCode = "timeout";
                    } else if (cause instanceof ConnectionLost || cause instanceof IOException) {
                        errorCode = "connection-lost";
                    } else if (cause instanceof InvalidResponse
                            || cause instanceof ModbusException
                            || cause instanceof RuntimeException) {
                        // ModbusException: the library saw an empty PDU or another function code.
                        // RuntimeException: its decoder ran out of bytes or met a wrong type.
                        errorCode = "invalid-response";
                    } else {
                        errorCode = "failed";
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errorCode = "failed";
            errorMessage = "interrupted";
        } catch (RuntimeException e) {
            errorCode = connected ? "failed" : "connect-failed";
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

        if (client != null && connected) {
            try {
                client.disconnectAsync().toCompletableFuture().get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                System.err.println("modbus-interop: closing the connection: " + e);
            }
        }
        if (ok) {
            return Main.EXIT_OK;
        }
        return "connect-failed".equals(errorCode) ? Main.EXIT_CONNECT : Main.EXIT_FAILED;
    }

    private static Throwable unwrap(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // ---- the operations -------------------------------------------------------------------------

    private static Call call(String operation, Args args) throws Args.UsageException {
        return switch (operation) {
            case "read-coils", "read-discrete-inputs" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 2000);
                boolean coils = operation.equals("read-coils");
                ModbusRequestPdu request = coils
                        ? new ReadCoilsRequest(address, quantity)
                        : new ReadDiscreteInputsRequest(address, quantity);
                yield typed(request, answer -> {
                    byte[] packed = switch (answer) {
                        case ReadCoilsResponse r when coils -> r.coils();
                        case ReadDiscreteInputsResponse r when !coils -> r.inputs();
                        default -> throw unexpected(answer);
                    };
                    if (packed.length != (quantity + 7) / 8) {
                        throw new InvalidResponse(packed.length + " data bytes for " + quantity + " bits");
                    }
                    JsonArray bits = new JsonArray();
                    for (int i = 0; i < quantity; i++) {
                        bits.add((packed[i / 8] >> (i % 8) & 1) == 1);
                    }
                    JsonObject response = response(coils ? 1 : 2);
                    response.add("bits", bits);
                    return response;
                });
            }
            case "read-holding-registers", "read-input-registers" -> {
                int address = args.number("address", 0, 65535);
                int quantity = args.number("quantity", 1, 125);
                boolean holding = operation.equals("read-holding-registers");
                ModbusRequestPdu request = holding
                        ? new ReadHoldingRegistersRequest(address, quantity)
                        : new ReadInputRegistersRequest(address, quantity);
                yield typed(request, answer -> {
                    byte[] data = switch (answer) {
                        case ReadHoldingRegistersResponse r when holding -> r.registers();
                        case ReadInputRegistersResponse r when !holding -> r.registers();
                        default -> throw unexpected(answer);
                    };
                    JsonObject response = response(holding ? 3 : 4);
                    response.add("registers", registers(data, quantity));
                    return response;
                });
            }
            case "write-single-coil" -> {
                int address = args.number("address", 0, 65535);
                boolean value = args.bool("value");
                yield typed(new WriteSingleCoilRequest(address, value), answer -> {
                    if (!(answer instanceof WriteSingleCoilResponse r)) {
                        throw unexpected(answer);
                    }
                    if (r.value() != 0xFF00 && r.value() != 0x0000) {
                        throw new InvalidResponse("coil value 0x%04x in the response".formatted(r.value()));
                    }
                    echo("address", address, r.address());
                    echo("value", value ? 0xFF00 : 0x0000, r.value());
                    JsonObject response = response(5);
                    response.addProperty("address", r.address());
                    response.addProperty("value", r.value() == 0xFF00);
                    return response;
                });
            }
            case "write-single-register" -> {
                int address = args.number("address", 0, 65535);
                int value = args.number("value", 0, 65535);
                yield typed(new WriteSingleRegisterRequest(address, value), answer -> {
                    if (!(answer instanceof WriteSingleRegisterResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.address());
                    echo("value", value, r.value());
                    JsonObject response = response(6);
                    response.addProperty("address", r.address());
                    response.addProperty("value", r.value());
                    return response;
                });
            }
            case "write-multiple-coils" -> {
                int address = args.number("address", 0, 65535);
                boolean[] values = args.bools("values", 1, 1968);
                byte[] packed = new byte[(values.length + 7) / 8];
                for (int i = 0; i < values.length; i++) {
                    if (values[i]) {
                        packed[i / 8] |= (byte) (1 << (i % 8));
                    }
                }
                yield typed(new WriteMultipleCoilsRequest(address, values.length, packed), answer -> {
                    if (!(answer instanceof WriteMultipleCoilsResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.address());
                    echo("quantity", values.length, r.quantity());
                    JsonObject response = response(15);
                    response.addProperty("address", r.address());
                    response.addProperty("quantity", r.quantity());
                    return response;
                });
            }
            case "write-multiple-registers" -> {
                int address = args.number("address", 0, 65535);
                int[] values = args.numbers("values", 1, 123, 0, 65535);
                yield typed(new WriteMultipleRegistersRequest(address, values.length, bytes(values)), answer -> {
                    if (!(answer instanceof WriteMultipleRegistersResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.address());
                    echo("quantity", values.length, r.quantity());
                    JsonObject response = response(16);
                    response.addProperty("address", r.address());
                    response.addProperty("quantity", r.quantity());
                    return response;
                });
            }
            case "mask-write-register" -> {
                int address = args.number("address", 0, 65535);
                int andMask = args.number("and-mask", 0, 65535);
                int orMask = args.number("or-mask", 0, 65535);
                yield typed(new MaskWriteRegisterRequest(address, andMask, orMask), answer -> {
                    if (!(answer instanceof MaskWriteRegisterResponse r)) {
                        throw unexpected(answer);
                    }
                    echo("address", address, r.address());
                    echo("and mask", andMask, r.andMask());
                    echo("or mask", orMask, r.orMask());
                    JsonObject response = response(22);
                    response.addProperty("address", r.address());
                    response.addProperty("andMask", r.andMask());
                    response.addProperty("orMask", r.orMask());
                    return response;
                });
            }
            case "read-write-multiple-registers" -> {
                int readAddress = args.number("read-address", 0, 65535);
                int readQuantity = args.number("read-quantity", 1, 125);
                int writeAddress = args.number("write-address", 0, 65535);
                int[] values = args.numbers("values", 1, 121, 0, 65535);
                ModbusRequestPdu request = new ReadWriteMultipleRegistersRequest(
                        readAddress, readQuantity, writeAddress, values.length, bytes(values));
                yield typed(request, answer -> {
                    if (!(answer instanceof ReadWriteMultipleRegistersResponse r)) {
                        throw unexpected(answer);
                    }
                    JsonObject response = response(23);
                    response.add("registers", registers(r.registers(), readQuantity));
                    return response;
                });
            }
            case "raw" -> {
                int function = args.number("function", 1, 127);
                String hex = args.required("data");
                if (hex.length() % 2 != 0 || !hex.matches("[0-9a-fA-F]*") || hex.length() > 2 * 252) {
                    throw new Args.UsageException("--data: up to 252 bytes as hexadecimal digits");
                }
                byte[] data = HexFormat.of().parseHex(hex);
                byte[] pdu = new byte[1 + data.length];
                pdu[0] = (byte) function;
                System.arraycopy(data, 0, pdu, 1, data.length);
                yield new Call() {
                    // sendRaw hands back the response PDU as it arrived, exception responses
                    // included: telling them apart is left to the caller.
                    @Override
                    public CompletionStage<?> send(ModbusTcpClient client, int unitId) {
                        return client.sendRawAsync(unitId, pdu);
                    }

                    @Override
                    public JsonObject response(Object answer) throws InvalidResponse, ModbusResponseException {
                        if (!(answer instanceof byte[] received) || received.length == 0) {
                            throw new InvalidResponse("empty response PDU");
                        }
                        int got = received[0] & 0xFF;
                        if (got == (function | 0x80)) {
                            if (received.length != 2) {
                                throw new InvalidResponse("exception response of " + received.length + " bytes");
                            }
                            throw new ModbusResponseException(function, received[1] & 0xFF);
                        }
                        if (got != function) {
                            throw new InvalidResponse("response with function code " + got);
                        }
                        JsonObject response = ClientCommand.response(function);
                        response.addProperty("data", HexFormat.of().formatHex(received, 1, received.length));
                        return response;
                    }
                };
            }
            default -> throw new Args.UsageException("unknown operation: " + operation);
        };
    }

    private interface Reader {
        JsonObject read(Object answer) throws InvalidResponse;
    }

    /** A request the library has a PDU class for. */
    private static Call typed(ModbusRequestPdu request, Reader reader) {
        return new Call() {
            @Override
            public CompletionStage<?> send(ModbusTcpClient client, int unitId) {
                return client.sendAsync(unitId, request);
            }

            @Override
            public JsonObject response(Object answer) throws InvalidResponse {
                return reader.read(answer);
            }
        };
    }

    private static JsonObject response(int function) {
        JsonObject response = new JsonObject();
        response.addProperty("function", function);
        return response;
    }

    private static InvalidResponse unexpected(Object answer) {
        return new InvalidResponse("unexpected response: " + (answer == null ? "none" : answer.getClass().getSimpleName()));
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

    private static byte[] bytes(int[] values) {
        byte[] encoded = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            encoded[2 * i] = (byte) (values[i] >> 8);
            encoded[2 * i + 1] = (byte) values[i];
        }
        return encoded;
    }
}
