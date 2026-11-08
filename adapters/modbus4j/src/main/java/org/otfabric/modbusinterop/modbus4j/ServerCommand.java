// SPDX-License-Identifier: GPL-3.0-or-later
package org.otfabric.modbusinterop.modbus4j;

import com.google.gson.JsonObject;
import com.serotonin.modbus4j.ModbusSlaveSet;
import com.serotonin.modbus4j.exception.ModbusInitException;
import com.serotonin.modbus4j.ip.tcp.TcpSlave;
import com.serotonin.modbus4j.ip.udp.UdpSlave;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code server}: a modbus4j slave that serves one fixture until SIGTERM or SIGINT. tcp is
 * {@code TcpSlave(port, false)} (MBAP), rtu-over-tcp {@code TcpSlave(port, true)} (the library's
 * "encapsulated" RTU frames on TCP), udp {@code UdpSlave(port, false)}.
 */
final class ServerCommand {

    /** How long the listener may take to appear. */
    private static final long LISTEN_TIMEOUT_MS = 15_000;

    private ServerCommand() {}

    static int run(List<String> tokens) throws Args.UsageException {
        Args args = new Args(tokens, Set.of("fixture", "bind-address", "port", "ready-file", "transport"));
        String transport = Args.transport(args);
        Path fixturePath = Path.of(args.string("fixture", Main.FIXTURES + "/baseline/fixture.json"));
        String bindAddress = args.string("bind-address", "0.0.0.0");
        int port = args.number("port", 1502, 1, 65535);
        Path readyFile = Path.of(args.string("ready-file", "/run/modbus-interop/ready"));
        boolean udp = transport.equals("udp");

        Fixture fixture;
        try {
            fixture = Fixture.load(fixturePath);
        } catch (Fixture.FixtureException e) {
            System.err.println("modbus-interop: invalid fixture: " + e.getMessage());
            return Main.EXIT_FIXTURE;
        }

        // TcpSlave and UdpSlave take a port only and listen on every address.
        if (!bindAddress.equals("0.0.0.0") && !bindAddress.equals("::")) {
            System.err.println("modbus-interop: cannot listen on " + bindAddress
                    + ": modbus4j's slaves listen on all addresses (use 0.0.0.0)");
            return Main.EXIT_FAILED;
        }

        // The library binds inside start(), which then blocks in accept() or receive() and says
        // nothing about having bound. A port that is taken is found here first, so that the
        // listener found below is this process's.
        try {
            if (udp) {
                new DatagramSocket(port).close();
            } else {
                try (ServerSocket probe = new ServerSocket()) {
                    probe.bind(new InetSocketAddress(port));
                }
            }
        } catch (IOException e) {
            System.err.println("modbus-interop: cannot listen on " + bindAddress + ":" + port + ": " + e);
            return Main.EXIT_FAILED;
        }

        Out out = new Out();
        ModbusSlaveSet slave = udp ? new UdpSlave(port, false) : new TcpSlave(port, transport.equals("rtu-over-tcp"));
        slave.addProcessImage(new Device(fixture));
        // The library's default for FC16 is 120 registers; the protocol allows 123 (V1.1b3, 6.12).
        slave.setMaxWriteRegisterCount(123);
        // What the library cannot answer it reports here, and does not answer: to stderr. The
        // IOExceptions are the polling of closed sockets, repeated until the connection's thread
        // notices.
        slave.setExceptionHandler(e -> {
            if (!(e instanceof IOException)) {
                System.err.println("modbus-interop: modbus4j: " + e);
            }
        });

        AtomicReference<Exception> failed = new AtomicReference<>();
        CountDownLatch ended = new CountDownLatch(1);
        Object stopping = new Object();
        boolean[] stopped = {false};
        Thread listener = new Thread(() -> {
            try {
                slave.start();
            } catch (ModbusInitException | RuntimeException e) {
                synchronized (stopping) {
                    if (!stopped[0]) {
                        failed.set(e);
                    }
                }
            } finally {
                ended.countDown();
            }
        }, "modbus4j-slave");
        listener.setDaemon(true);
        listener.start();

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LISTEN_TIMEOUT_MS);
        while (!Listening.on(port, udp)) {
            if (ended.getCount() == 0 || System.nanoTime() > deadline) {
                Exception e = failed.get();
                System.err.println("modbus-interop: cannot listen on " + bindAddress + ":" + port + ": "
                        + (e == null ? "the listener did not appear" : e.getCause() != null ? e.getCause() : e));
                return Main.EXIT_FAILED;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Main.EXIT_FAILED;
            }
        }

        try {
            Files.write(readyFile, "ready\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            System.err.println("modbus-interop: cannot write the readiness file " + readyFile + ": " + e);
            return Main.EXIT_FAILED;
        }
        JsonObject ready = new JsonObject();
        ready.addProperty("event", "ready");
        ready.addProperty("adapter", Main.ADAPTER);
        ready.addProperty("address", bindAddress + ":" + port);
        ready.addProperty("fixture", fixture.name);
        ready.addProperty("unitId", fixture.unitId);
        out.line(ready);

        // A JVM that ends through a signal exits with 128+signal after its shutdown hooks; the
        // contract asks for 0 after a clean stop, so the hook ends the process itself.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(readyFile);
            } catch (IOException | RuntimeException e) {
                System.err.println("modbus-interop: cannot remove " + readyFile + ": " + e);
            }
            synchronized (stopping) {
                stopped[0] = true;
            }
            try {
                // Closes the listener and every connection, and waits up to 3 s for their threads.
                slave.stop();
            } catch (RuntimeException e) {
                System.err.println("modbus-interop: stopping the slave: " + e);
            }
            JsonObject done = new JsonObject();
            done.addProperty("event", "stopped");
            out.last(done);
            System.err.flush();
            Runtime.getRuntime().halt(Main.EXIT_OK);
        }, "modbus-interop-stop"));

        try {
            ended.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Exception e = failed.get();
        if (e != null) {
            System.err.println("modbus-interop: the slave stopped: " + e);
        }
        return Main.EXIT_FAILED;
    }

    /** Whether a socket of this process's network namespace listens on the port. */
    static final class Listening {
        private Listening() {}

        static boolean on(int port, boolean udp) {
            String hex = String.format(Locale.ROOT, ":%04X", port);
            // TCP_LISTEN is 0A; a bound UDP socket is 07 (TCP_CLOSE, "unconnected").
            String state = udp ? "07" : "0A";
            for (String file : udp ? new String[] {"/proc/net/udp", "/proc/net/udp6"}
                    : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
                try {
                    for (String line : Files.readAllLines(Path.of(file), StandardCharsets.US_ASCII)) {
                        String[] f = line.trim().split("\\s+");
                        if (f.length > 3 && f[1].endsWith(hex) && f[3].equals(state)) {
                            return true;
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    // the file is missing (no IPv6): try the next one
                }
            }
            return false;
        }
    }
}
