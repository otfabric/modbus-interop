// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.j2mod;

import com.ghgande.j2mod.modbus.ModbusIOException;
import com.ghgande.j2mod.modbus.io.AbstractModbusTransport;
import com.ghgande.j2mod.modbus.io.ModbusUDPTransport;
import com.ghgande.j2mod.modbus.msg.ModbusRequest;
import com.ghgande.j2mod.modbus.msg.ModbusResponse;
import com.ghgande.j2mod.modbus.net.AbstractUDPTerminal;
import com.ghgande.j2mod.modbus.net.TCPSlaveConnection;
import com.google.gson.JsonObject;
import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * {@code server}: a Modbus device that serves one fixture until SIGTERM or SIGINT.
 *
 * <p>The adapter does not use the library's {@code ModbusSlave}: its listeners answer a request
 * for a unit identifier without a process image with exception 2 and check no quantity, and they
 * report neither requests nor connections. Instead the adapter runs the loop of the library's
 * own connection handler ({@code TCPConnectionHandler}, {@code ModbusUDPListener}) with the
 * library's public pieces: the transport reads a request ({@code readRequest}), {@link Device}
 * answers it ({@code createResponse} after the fixture rules) and the transport writes the
 * response ({@code writeResponse}).
 */
final class ServerCommand {

    static final List<String> TRANSPORTS = List.of("tcp", "udp", "rtu-over-tcp");

    private ServerCommand() {}

    /** The listener of one transport: opened before "ready", served after it. */
    private interface Listener extends Closeable {
        void serve();
    }

    static int run(List<String> tokens) throws Args.UsageException {
        Args args = new Args(tokens, Set.of("fixture", "bind-address", "port", "ready-file", "transport"));
        Path fixturePath = Path.of(args.string("fixture", Main.FIXTURES + "/baseline/fixture.json"));
        String bindAddress = args.string("bind-address", "0.0.0.0");
        int port = args.number("port", 1502, 1, 65535);
        Path readyFile = Path.of(args.string("ready-file", "/run/modbus-interop/ready"));
        String transport = args.transport(TRANSPORTS);
        InetAddress bind;
        try {
            bind = InetAddress.getByName(bindAddress);
        } catch (IOException e) {
            throw new Args.UsageException("--bind-address " + bindAddress + ": " + e.getMessage());
        }

        Fixture fixture;
        try {
            fixture = Fixture.load(fixturePath);
        } catch (Fixture.FixtureException e) {
            System.err.println("modbus-interop: invalid fixture: " + e.getMessage());
            return Main.EXIT_FIXTURE;
        }

        Out out = new Out();
        Device device = new Device(fixture, out);
        Listener listener;
        try {
            listener = transport.equals("udp")
                    ? new UdpListener(device, bind, port)
                    : new TcpListener(device, out, bind, port, transport.equals("rtu-over-tcp"));
        } catch (Exception e) {
            System.err.println("modbus-interop: cannot listen on " + bindAddress + ":" + port + ": " + e);
            return Main.EXIT_FAILED;
        }

        // Holding the output lock until "ready" is printed keeps it the first line even when a
        // client connects the moment the listener is served.
        synchronized (out) {
            try {
                Files.write(readyFile, "ready\n".getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                System.err.println("modbus-interop: cannot write the readiness file " + readyFile + ": " + e);
                close(listener);
                return Main.EXIT_FAILED;
            }
            Thread serving = new Thread(listener::serve, "modbus-interop-" + transport);
            serving.setDaemon(true);
            serving.start();
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
            close(listener);
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

    private static void close(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException | RuntimeException e) {
            System.err.println("modbus-interop: stopping the listener: " + e);
        }
    }

    /** True when the exception is the end of the peer's stream or of our own socket. */
    private static boolean ended(Exception e) {
        return e instanceof ModbusIOException io && io.isEOF();
    }

    // ---- TCP and RTU over TCP -----------------------------------------------------------------

    /**
     * Modbus TCP, or RTU frames on TCP: the library's {@link TCPSlaveConnection} with its
     * {@code useRtuOverTcp} switch, one thread per connection.
     */
    private static final class TcpListener implements Listener {
        private final Device device;
        private final Out out;
        private final boolean rtu;
        private final ServerSocket server;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Set<Thread> handlers = ConcurrentHashMap.newKeySet();

        TcpListener(Device device, Out out, InetAddress bind, int port, boolean rtu) throws IOException {
            this.device = device;
            this.out = out;
            this.rtu = rtu;
            server = new ServerSocket();
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(bind, port), 100);
        }

        @Override
        public void serve() {
            while (!server.isClosed()) {
                Socket socket;
                try {
                    socket = server.accept();
                } catch (IOException e) {
                    if (!server.isClosed()) {
                        System.err.println("modbus-interop: accept: " + e);
                    }
                    continue;
                }
                sockets.add(socket);
                Thread handler = new Thread(() -> handle(socket), "modbus-interop-connection");
                handler.setDaemon(true);
                handlers.add(handler);
                handler.start();
            }
        }

        /** The loop of the library's TCPConnectionHandler, with the device in front. */
        private void handle(Socket socket) {
            String peer = peer(socket.getRemoteSocketAddress());
            out.line(connectionEvent("connection-opened", peer));
            TCPSlaveConnection connection = null;
            try {
                connection = new TCPSlaveConnection(socket, rtu);
                // As ModbusSlave does (listener.setTimeout(0)): wait for the next request
                // indefinitely. The transport's own default is 3 s, after which its handler would
                // close an idle connection.
                connection.setTimeout(0);
                AbstractModbusTransport transport = connection.getModbusTransport();
                while (true) {
                    ModbusRequest request = transport.readRequest(device);
                    ModbusResponse response = device.answer(request, rtu);
                    if (response != null) {
                        transport.writeResponse(response);
                    }
                }
            } catch (ModbusIOException | RuntimeException e) {
                // As in the library's handler: a request it cannot read or answer ends the
                // connection.
                if (!ended(e) && !socket.isClosed()) {
                    System.err.println("modbus-interop: " + peer + ": " + e + (e.getCause() == null ? "" : ": " + e.getCause()));
                }
            } finally {
                if (connection != null) {
                    connection.close();
                }
                try {
                    socket.close();
                } catch (IOException e) {
                    // closed
                }
                sockets.remove(socket);
                out.line(connectionEvent("connection-closed", peer));
                handlers.remove(Thread.currentThread());
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // closed
                }
            }
            // Closing a socket ends its handler; wait for them, so that their
            // connection-closed events precede "stopped".
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            for (Thread handler : handlers) {
                long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (left <= 0) {
                    break;
                }
                try {
                    handler.join(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    // ---- UDP ----------------------------------------------------------------------------------

    /**
     * MBAP in UDP datagrams: the library's {@link ModbusUDPTransport} on a terminal. The
     * library's own slave terminal ({@code UDPSlaveTerminal}) is package-private and is only run
     * by its {@code ModbusUDPListener}, which has the shortcomings of the other listeners; the
     * terminal is the public extension point the transport reads and writes datagrams through.
     */
    private static final class UdpListener implements Listener {
        private final Device device;
        private final Terminal terminal;

        UdpListener(Device device, InetAddress bind, int port) throws Exception {
            this.device = device;
            terminal = new Terminal(bind, port);
            terminal.activate();
        }

        @Override
        public void serve() {
            ModbusUDPTransport transport = terminal.getTransport();
            while (terminal.isActive()) {
                ModbusRequest request;
                try {
                    request = transport.readRequest(device);
                } catch (ModbusIOException e) {
                    if (terminal.isActive()) {
                        // A datagram the library cannot read is not answered.
                        System.err.println("modbus-interop: udp: " + e + (e.getCause() == null ? "" : ": " + e.getCause()));
                    }
                    continue;
                }
                try {
                    ModbusResponse response = device.answer(request, false);
                    if (response != null) {
                        transport.writeResponse(response);
                    }
                } catch (ModbusIOException | RuntimeException e) {
                    System.err.println("modbus-interop: udp: " + e);
                }
            }
        }

        @Override
        public void close() {
            terminal.deactivate();
        }
    }

    /**
     * Datagrams in and out for {@link ModbusUDPTransport}. Like the library's slave terminal it
     * hands the transport a zero-padded buffer of fixed size and answers the address a request
     * came from; requests are answered one at a time, so that is the last sender. The buffer is
     * 260 bytes, the longest MBAP frame (7 + 253); the library's own terminal has 256, which cuts
     * off the longest requests.
     */
    private static final class Terminal extends AbstractUDPTerminal {
        private static final int LONGEST_FRAME = 260;
        private volatile SocketAddress sender;

        Terminal(InetAddress bind, int port) {
            address = bind;
            this.port = port;
        }

        @Override
        public synchronized void activate() throws Exception {
            if (!active) {
                socket = new DatagramSocket(new InetSocketAddress(address, port));
                transport = new ModbusUDPTransport(this);
                active = true;
            }
        }

        @Override
        public synchronized void deactivate() {
            active = false;
            if (socket != null) {
                socket.close();
            }
        }

        @Override
        public void sendMessage(byte[] message) throws Exception {
            socket.send(new DatagramPacket(message, message.length, sender));
        }

        @Override
        public byte[] receiveMessage() throws Exception {
            byte[] buffer = new byte[LONGEST_FRAME];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            sender = packet.getSocketAddress();
            return buffer;
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
