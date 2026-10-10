// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import com.digitalpetri.modbus.server.ModbusTcpServer;
import com.digitalpetri.modbus.tcp.server.NettyTcpServerTransport;
import com.google.gson.JsonObject;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/** {@code server}: a Modbus TCP device that serves one fixture until SIGTERM or SIGINT. */
final class ServerCommand {

    private ServerCommand() {}

    static int run(List<String> tokens) throws Args.UsageException {
        Args args = new Args(tokens, Set.of("fixture", "bind-address", "port", "ready-file"));
        Path fixturePath = Path.of(args.string("fixture", Main.FIXTURES + "/baseline/fixture.json"));
        String bindAddress = args.string("bind-address", "0.0.0.0");
        int port = args.number("port", 1502, 1, 65535);
        Path readyFile = Path.of(args.string("ready-file", "/run/modbus-interop/ready"));

        Fixture fixture;
        try {
            fixture = Fixture.load(fixturePath);
        } catch (Fixture.FixtureException e) {
            System.err.println("modbus-interop: invalid fixture: " + e.getMessage());
            return Main.EXIT_FIXTURE;
        }

        Out out = new Out();
        Device device = new Device(fixture, out);
        Set<Channel> connections = ConcurrentHashMap.newKeySet();

        // The transport calls the pipeline customizer once for every accepted connection: the
        // only place where the library shows a connection rather than a request.
        NettyTcpServerTransport transport = NettyTcpServerTransport.create(config -> {
            config.setBindAddress(bindAddress);
            config.setPort(port);
            config.setPipelineCustomizer((ChannelPipeline pipeline) -> {
                Channel channel = pipeline.channel();
                String peer = peer(channel.remoteAddress());
                connections.add(channel);
                out.line(connectionEvent("connection-opened", peer));
                channel.closeFuture().addListener((ChannelFutureListener) closed -> {
                    out.line(connectionEvent("connection-closed", peer));
                    connections.remove(channel);
                });
            });
        });
        ModbusTcpServer server = ModbusTcpServer.create(transport, device);

        // Holding the output lock until "ready" is printed keeps it the first line even when a
        // client connects the moment the port opens.
        synchronized (out) {
            try {
                server.start();
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
                System.err.println("modbus-interop: cannot listen on " + bindAddress + ":" + port + ": " + cause);
                return Main.EXIT_FAILED;
            }
            try {
                Files.write(readyFile, "ready\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                System.err.println("modbus-interop: cannot write the readiness file " + readyFile + ": " + e);
                stop(server);
                return Main.EXIT_FAILED;
            }
            JsonObject ready = new JsonObject();
            ready.addProperty("event", "ready");
            ready.addProperty("adapter", Main.ADAPTER);
            ready.addProperty("address", bindAddress + ":" + port);
            ready.addProperty("fixture", fixture.name);
            ready.addProperty("unitId", fixture.unitId);
            out.line(ready);
        }

        // A JVM that ends through a signal exits with 128+signal after its shutdown hooks; the
        // contract asks for 0 after a clean stop, so the hook ends the process itself.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(readyFile);
            } catch (IOException | RuntimeException e) {
                System.err.println("modbus-interop: cannot remove " + readyFile + ": " + e);
            }
            stop(server);
            // unbind() starts closing the connections; wait until they are closed so that
            // their events precede "stopped".
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!connections.isEmpty() && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    break;
                }
            }
            JsonObject stopped = new JsonObject();
            stopped.addProperty("event", "stopped");
            out.last(stopped);
            System.err.flush();
            Runtime.getRuntime().halt(Main.EXIT_OK);
        }, "modbus-interop-stop"));

        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Main.EXIT_FAILED;
    }

    private static void stop(ModbusTcpServer server) {
        try {
            server.getTransport().unbind().toCompletableFuture().get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("modbus-interop: stopping the listener: " + e);
        }
    }

    private static String peer(SocketAddress address) {
        if (address instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress() + ":" + inet.getPort();
        }
        return String.valueOf(address);
    }

    private static JsonObject connectionEvent(String name, String peer) {
        JsonObject event = new JsonObject();
        event.addProperty("event", name);
        event.addProperty("peer", peer);
        return event;
    }
}
