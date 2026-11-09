// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.j2mod;

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

/** modbus-interop adapter for j2mod: the commands of docs/CONTAINER_CONTRACT.md. */
public final class Main {

    static final String ADAPTER = "j2mod";
    static final String FIXTURES = "/fixtures";

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_USAGE = 2;
    static final int EXIT_CONNECT = 3;
    static final int EXIT_FIXTURE = 4;

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
        // Exit explicitly: nothing may keep the JVM alive.
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
                        + " (j2mod " + upstreamVersion() + ")");
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
        try {
            Enumeration<URL> manifests = Main.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (manifests.hasMoreElements()) {
                try (InputStream in = manifests.nextElement().openStream()) {
                    String version = new Manifest(in).getMainAttributes().getValue("Upstream-Version");
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
        upstream.addProperty("name", "j2mod");
        upstream.addProperty("version", upstreamVersion());
        upstream.addProperty("revision", "com.ghgande:j2mod:" + upstreamVersion());
        upstream.addProperty("license", "Apache-2.0");
        upstream.addProperty("language", "Java");
        upstream.addProperty("independentEngine", true);

        JsonObject roles = new JsonObject();
        roles.addProperty("server", true);
        roles.addProperty("client", true);

        JsonObject features = new JsonObject();
        // The unit identifier, quantity and FC23/FC21 range rules are adapter checks in front of
        // the library (Device): its listeners answer another unit identifier with exception 2 and
        // check no quantity.
        features.addProperty("serverUnitIdFiltering", true);
        features.addProperty("serverQuantityCheck", true);
        features.addProperty("serverMaskWrite", true);
        features.addProperty("serverReadWriteMultiple", true);
        // The library's FC43 request class answers exception 1 on the server side.
        features.addProperty("serverDeviceIdentification", false);
        features.addProperty("requestEvents", true);
        features.addProperty("connectionEvents", true);
        features.addProperty("clientMaskWrite", true);
        features.addProperty("clientReadWriteMultiple", true);
        features.addProperty("clientDeviceIdentification", true);
        // No request class for an arbitrary PDU, and a normal response of a function the library
        // does not know is decoded as an exception response.
        features.addProperty("clientRaw", false);
        features.addProperty("clientRepeat", true);
        // ModbusTCPTransaction matches a response by transaction identifier only (its UDP
        // transaction does compare the unit identifier).
        features.addProperty("clientUnitIdCheck", false);
        // FC08 and FC17 request classes answer exception 1 on the server side.
        features.addProperty("serverDiagnostics", false);
        features.addProperty("serverReportServerId", false);
        // FC20 with two sub-requests answers the second with the wrong
        // records (README, finding 5; FIXTURES.md rule 10). FC20 and FC21
        // are still answered by the library (serverFunctions).
        features.addProperty("serverFileRecord", false);
        features.addProperty("serverFifoQueue", true);
        features.addProperty("clientDiagnostics", true);
        features.addProperty("clientReportServerId", true);
        features.addProperty("clientReadFileRecord", true);
        features.addProperty("clientWriteFileRecord", true);
        features.addProperty("clientFifoQueue", true);
        features.addProperty("clientRawAnyResponse", false);
        features.addProperty("serverIllegalFunction", false);
        features.addProperty("serverRejectedWriteUnchanged", true);
        features.addProperty("clientLargestRegisterWrites", true);
        features.addProperty("serverHighestAddress", true);
        features.addProperty("serverLargestRegisterWrites", true);

        JsonArray functions = new JsonArray();
        for (int function : Device.FUNCTIONS) {
            functions.add(function);
        }
        JsonArray operations = new JsonArray();
        for (String operation : ClientCommand.OPERATIONS) {
            operations.add(operation);
        }
        JsonObject transports = new JsonObject();
        JsonArray server = new JsonArray();
        ServerCommand.TRANSPORTS.forEach(server::add);
        JsonArray client = new JsonArray();
        ClientCommand.TRANSPORTS.forEach(client::add);
        transports.add("server", server);
        transports.add("client", client);

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
        doc.add("transports", transports);
        return doc;
    }
}
