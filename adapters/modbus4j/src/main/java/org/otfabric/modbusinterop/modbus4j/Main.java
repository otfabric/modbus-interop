// SPDX-License-Identifier: GPL-3.0-or-later
package org.otfabric.modbusinterop.modbus4j;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.Manifest;

/** modbus-interop adapter for modbus4j: the commands of docs/CONTAINER_CONTRACT.md. */
public final class Main {

    static final String ADAPTER = "modbus4j";
    static final String FIXTURES = "/fixtures";

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_CONNECT = 3;
    static final int EXIT_FIXTURE = 4;

    /** The function codes the library's slave decodes and answers (ModbusRequest.createModbusRequest). */
    static final int[] SERVER_FUNCTIONS = {1, 2, 3, 4, 5, 6, 7, 15, 16, 17};

    /**
     * The library discards a response with another unit identifier on every transport (TCP and
     * rtu-over-tcp: the key of its waiting room has the unit identifier; UDP: validateResponse);
     * the adapter reports the discard, which the library passes to its exception handler, as
     * invalid-response (README.md). Verified against a scripted peer.
     */
    static final boolean CLIENT_UNIT_ID_CHECK = true;

    private Main() {}

    public static void main(String[] argv) {
        int code;
        try {
            code = run(Arrays.asList(argv));
        } catch (Args.UsageException e) {
            System.err.println("modbus-interop: " + e.getMessage());
            System.err.println("usage: modbus-interop server [flags] | client <operation> --host HOST [flags]"
                    + " | print-capabilities | print-fixture [NAME] | version");
            code = EXIT_USAGE;
        }
        Out.raw().flush();
        System.err.flush();
        // The slave's thread pools are not daemons: exit explicitly, nothing may keep the JVM alive.
        System.exit(code);
    }

    private static int run(List<String> argv) throws Args.UsageException {
        if (argv.isEmpty()) {
            throw new Args.UsageException("missing command");
        }
        List<String> rest = argv.subList(1, argv.size());
        return switch (argv.get(0)) {
            case "server" -> ServerCommand.run(rest);
            case "client" -> ClientCommand.run(rest);
            case "print-capabilities" -> {
                noArguments(rest);
                new Out().line(capabilities());
                yield EXIT_OK;
            }
            case "print-fixture" -> printFixture(rest);
            case "version" -> {
                noArguments(rest);
                Out.raw().println("modbus-interop-" + ADAPTER + " " + adapterVersion()
                        + " (modbus4j " + upstreamVersion() + ")");
                yield EXIT_OK;
            }
            default -> throw new Args.UsageException("unknown command: " + argv.get(0));
        };
    }

    private static void noArguments(List<String> rest) throws Args.UsageException {
        if (!rest.isEmpty()) {
            throw new Args.UsageException("unexpected argument: " + rest.get(0));
        }
    }

    /** Writes the baked fixture to stdout, byte for byte. */
    private static int printFixture(List<String> rest) throws Args.UsageException {
        if (rest.size() > 1 || (rest.size() == 1 && rest.get(0).startsWith("--"))) {
            throw new Args.UsageException("usage: print-fixture [NAME]");
        }
        String name = rest.isEmpty() ? "baseline" : rest.get(0);
        if (!name.matches("[a-z0-9][a-z0-9-]*")) {
            System.err.println("modbus-interop: no fixture named '" + name + "'");
            return EXIT_FAILED;
        }
        try {
            byte[] bytes = Files.readAllBytes(Path.of(FIXTURES, name, "fixture.json"));
            Out.raw().write(bytes, 0, bytes.length);
            Out.raw().flush();
            return EXIT_OK;
        } catch (IOException e) {
            System.err.println("modbus-interop: no fixture named '" + name + "': " + e);
            return EXIT_FAILED;
        }
    }

    static String adapterVersion() {
        String version = System.getenv("ADAPTER_VERSION");
        return version == null || version.isBlank() ? "dev" : version;
    }

    /** The upstream version the jar was built against, recorded in its manifest by the build. */
    static String upstreamVersion() {
        return manifest("Upstream-Version");
    }

    /** The upstream commit the library was built from, recorded in the manifest by the build. */
    static String upstreamRevision() {
        return manifest("Upstream-Revision");
    }

    private static String manifest(String attribute) {
        try {
            Enumeration<URL> manifests = Main.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (InputStream in = manifests.nextElement().openStream()) {
                    String version = new Manifest(in).getMainAttributes().getValue(attribute);
                    if (version != null) {
                        return version;
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("modbus-interop: cannot read the manifest: " + e);
        }
        return "unknown";
    }

    private static JsonObject capabilities() {
        JsonObject upstream = new JsonObject();
        upstream.addProperty("name", "modbus4j");
        upstream.addProperty("version", upstreamVersion());
        upstream.addProperty("revision", upstreamRevision());
        upstream.addProperty("license", "GPL-3.0-or-later");
        upstream.addProperty("language", "Java");
        upstream.addProperty("independentEngine", true);

        JsonObject roles = new JsonObject();
        roles.addProperty("server", true);
        roles.addProperty("client", true);

        JsonObject features = new JsonObject();
        // The slave answers a request for a unit identifier it has no process image for with
        // nothing at all, and a quantity it refuses (ModbusRequest.validate) with nothing either.
        features.addProperty("serverUnitIdFiltering", false);
        features.addProperty("serverQuantityCheck", false);
        // The slave's request parser has FC22 commented out: exception 1. No FC23, FC43.
        features.addProperty("serverMaskWrite", false);
        features.addProperty("serverReadWriteMultiple", false);
        features.addProperty("serverDeviceIdentification", false);
        // TcpSlave and UdpSlave show neither requests nor connections to the application.
        features.addProperty("requestEvents", false);
        features.addProperty("connectionEvents", false);
        features.addProperty("clientMaskWrite", true);
        features.addProperty("clientReadWriteMultiple", false);
        features.addProperty("clientDeviceIdentification", false);
        // There is no way to send a PDU the library has no message class for.
        features.addProperty("clientRaw", false);
        features.addProperty("clientRepeat", true);
        features.addProperty("clientUnitIdCheck", CLIENT_UNIT_ID_CHECK);
        features.addProperty("serverDiagnostics", false);
        // ReportSlaveIdRequest: byte count and ProcessImage.getReportSlaveIdData().
        features.addProperty("serverReportServerId", true);
        features.addProperty("serverFileRecord", false);
        features.addProperty("serverFifoQueue", false);
        features.addProperty("clientDiagnostics", false);
        features.addProperty("clientReportServerId", true);
        features.addProperty("clientReadFileRecord", false);
        features.addProperty("clientWriteFileRecord", false);
        features.addProperty("clientFifoQueue", false);
        features.addProperty("clientRawAnyResponse", false);
        // Over rtu-over-tcp the RTU slave does not answer an unknown function
        // code (over tcp and udp it answers exception 1): README.
        features.addProperty("serverIllegalFunction", false);
        features.addProperty("serverRejectedWriteUnchanged", false);
        features.addProperty("clientLargestRegisterWrites", true);
        features.addProperty("serverHighestAddress", true);
        features.addProperty("serverLargestRegisterWrites", true);

        JsonArray functions = new JsonArray();
        for (int function : SERVER_FUNCTIONS) {
            functions.add(function);
        }
        JsonArray operations = new JsonArray();
        for (String operation : ClientCommand.OPERATIONS) {
            operations.add(operation);
        }

        JsonObject doc = new JsonObject();
        doc.addProperty("schemaVersion", "1.0");
        doc.addProperty("adapter", ADAPTER);
        doc.addProperty("adapterVersion", adapterVersion());
        doc.addProperty("protocol", "modbus-tcp");
        doc.add("upstream", upstream);
        doc.add("roles", roles);
        doc.add("features", features);
        doc.add("serverFunctions", functions);
        doc.add("clientOperations", operations);
        // Contract 1.2: TcpSlave/TcpMaster with and without "encapsulated", UdpSlave/UdpMaster.
        JsonObject transports = new JsonObject();
        JsonArray list = new JsonArray();
        for (String transport : Args.TRANSPORTS) {
            list.add(transport);
        }
        transports.add("server", list);
        transports.add("client", list.deepCopy());
        doc.add("transports", transports);
        return doc;
    }
}
