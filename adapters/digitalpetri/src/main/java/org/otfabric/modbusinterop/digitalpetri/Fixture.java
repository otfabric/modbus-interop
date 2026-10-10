// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A fixture document (docs/FIXTURES.md), validated with the rules of
 * fixtures/schema/modbus-fixture.schema.json and scripts/validate-fixtures.py.
 */
final class Fixture {

    /** The fixture is missing, unreadable or invalid: the server exits 4. */
    static final class FixtureException extends Exception {
        private static final long serialVersionUID = 1L;

        FixtureException(String message) {
            super(message);
        }
    }

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final Pattern PRINTABLE = Pattern.compile("[\\x20-\\x7e]*");
    private static final Set<String> ROOT_KEYS = Set.of(
            "schemaVersion", "name", "description", "unitId",
            "coils", "discreteInputs", "holdingRegisters", "inputRegisters", "deviceIdentification");

    final String name;
    final int unitId;
    /** First address of each table; the values are {@code null} for a table the device lacks. */
    final int coilsAddress;
    final boolean[] coils;
    final int discreteInputsAddress;
    final boolean[] discreteInputs;
    final int holdingRegistersAddress;
    final int[] holdingRegisters;
    final int inputRegistersAddress;
    final int[] inputRegisters;

    private Fixture(JsonObject root, String directory) throws FixtureException {
        for (String key : root.keySet()) {
            if (!ROOT_KEYS.contains(key)) {
                throw new FixtureException("unknown property '" + key + "'");
            }
        }
        JsonElement version = root.get("schemaVersion");
        if (version == null || !isString(version) || !version.getAsString().equals("1.0")) {
            throw new FixtureException("schemaVersion must be \"1.0\"");
        }
        JsonElement nameElement = root.get("name");
        if (nameElement == null || !isString(nameElement) || !NAME.matcher(nameElement.getAsString()).matches()) {
            throw new FixtureException("name must match ^[a-z0-9][a-z0-9-]*$");
        }
        name = nameElement.getAsString();
        if (root.has("description") && !isString(root.get("description"))) {
            throw new FixtureException("description must be a string");
        }
        if (!root.has("unitId")) {
            throw new FixtureException("unitId is required");
        }
        unitId = integer(root.get("unitId"), "unitId", 1, 247);

        coilsAddress = blockAddress(root, "coils");
        coils = bits(root, "coils");
        discreteInputsAddress = blockAddress(root, "discreteInputs");
        discreteInputs = bits(root, "discreteInputs");
        holdingRegistersAddress = blockAddress(root, "holdingRegisters");
        holdingRegisters = registers(root, "holdingRegisters");
        inputRegistersAddress = blockAddress(root, "inputRegisters");
        inputRegisters = registers(root, "inputRegisters");
        if (root.has("deviceIdentification")) {
            checkDeviceIdentification(root.get("deviceIdentification"));
        }

        // The cross-field rules of scripts/validate-fixtures.py.
        if (!name.equals(directory)) {
            throw new FixtureException("name '" + name + "' is not the directory name '" + directory + "'");
        }
        checkEnd("coils", coilsAddress, coils == null ? 0 : coils.length);
        checkEnd("discreteInputs", discreteInputsAddress, discreteInputs == null ? 0 : discreteInputs.length);
        checkEnd("holdingRegisters", holdingRegistersAddress, holdingRegisters == null ? 0 : holdingRegisters.length);
        checkEnd("inputRegisters", inputRegistersAddress, inputRegisters == null ? 0 : inputRegisters.length);
    }

    static Fixture load(Path path) throws FixtureException {
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            json.setStrictness(Strictness.STRICT);
            root = JsonParser.parseReader(json);
            if (json.peek() != JsonToken.END_DOCUMENT) {
                throw new FixtureException(path + ": data after the JSON document");
            }
        } catch (IOException e) {
            throw new FixtureException(path + ": cannot read: " + e);
        } catch (JsonParseException | IllegalStateException e) {
            throw new FixtureException(path + ": not JSON: " + e.getMessage());
        }
        if (!root.isJsonObject()) {
            throw new FixtureException(path + ": the fixture must be a JSON object");
        }
        Path parent = path.toAbsolutePath().normalize().getParent();
        Path directory = parent == null ? null : parent.getFileName();
        try {
            return new Fixture(root.getAsJsonObject(), directory == null ? "" : directory.toString());
        } catch (FixtureException e) {
            throw new FixtureException(path + ": " + e.getMessage());
        }
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static int integer(JsonElement e, String what, int min, int max) throws FixtureException {
        if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            try {
                BigDecimal value = new BigDecimal(e.getAsString());
                if (value.stripTrailingZeros().scale() <= 0
                        && value.compareTo(BigDecimal.valueOf(min)) >= 0
                        && value.compareTo(BigDecimal.valueOf(max)) <= 0) {
                    return value.intValueExact();
                }
            } catch (ArithmeticException | NumberFormatException ignored) {
                // reported below
            }
        }
        throw new FixtureException(what + " must be an integer in " + min + ".." + max);
    }

    private static JsonObject block(JsonObject root, String table) throws FixtureException {
        JsonElement e = root.get(table);
        if (e == null) {
            return null;
        }
        if (!e.isJsonObject()) {
            throw new FixtureException(table + " must be an object");
        }
        JsonObject block = e.getAsJsonObject();
        for (String key : block.keySet()) {
            if (!key.equals("address") && !key.equals("values")) {
                throw new FixtureException(table + ": unknown property '" + key + "'");
            }
        }
        if (!block.has("address") || !block.has("values")) {
            throw new FixtureException(table + " needs address and values");
        }
        return block;
    }

    private static int blockAddress(JsonObject root, String table) throws FixtureException {
        JsonObject block = block(root, table);
        return block == null ? 0 : integer(block.get("address"), table + ".address", 0, 65535);
    }

    private static JsonArray values(JsonObject root, String table) throws FixtureException {
        JsonObject block = block(root, table);
        if (block == null) {
            return null;
        }
        JsonElement e = block.get("values");
        if (!e.isJsonArray() || e.getAsJsonArray().isEmpty() || e.getAsJsonArray().size() > 65536) {
            throw new FixtureException(table + ".values must be an array of 1..65536 items");
        }
        return e.getAsJsonArray();
    }

    private static boolean[] bits(JsonObject root, String table) throws FixtureException {
        JsonArray values = values(root, table);
        if (values == null) {
            return null;
        }
        boolean[] out = new boolean[values.size()];
        for (int i = 0; i < out.length; i++) {
            JsonElement e = values.get(i);
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
                throw new FixtureException(table + ".values[" + i + "] must be a boolean");
            }
            out[i] = e.getAsBoolean();
        }
        return out;
    }

    private static int[] registers(JsonObject root, String table) throws FixtureException {
        JsonArray values = values(root, table);
        if (values == null) {
            return null;
        }
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = integer(values.get(i), table + ".values[" + i + "]", 0, 65535);
        }
        return out;
    }

    private static void checkEnd(String table, int address, int count) throws FixtureException {
        int end = address + count - 1;
        if (end > 65535) {
            throw new FixtureException(table + ": block ends at " + end + ", past address 65535");
        }
    }

    private static void checkDeviceIdentification(JsonElement e) throws FixtureException {
        String what = "deviceIdentification";
        if (!e.isJsonObject()) {
            throw new FixtureException(what + " must be an object");
        }
        JsonObject ident = e.getAsJsonObject();
        for (String key : ident.keySet()) {
            if (!key.equals("objects")) {
                throw new FixtureException(what + ": unknown property '" + key + "'");
            }
        }
        JsonElement objects = ident.get("objects");
        if (objects == null || !objects.isJsonArray() || objects.getAsJsonArray().size() < 3) {
            throw new FixtureException(what + ".objects must be an array of at least 3 items");
        }
        List<Integer> ids = new ArrayList<>();
        for (JsonElement item : objects.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new FixtureException(what + ".objects items must be objects");
            }
            JsonObject object = item.getAsJsonObject();
            for (String key : object.keySet()) {
                if (!key.equals("id") && !key.equals("value")) {
                    throw new FixtureException(what + ".objects: unknown property '" + key + "'");
                }
            }
            if (!object.has("id")) {
                throw new FixtureException(what + ".objects: id is required");
            }
            int id = integer(object.get("id"), what + ".objects.id", 0, 255);
            JsonElement value = object.get("value");
            if (value == null || !isString(value) || value.getAsString().isEmpty()
                    || value.getAsString().length() > 240 || !PRINTABLE.matcher(value.getAsString()).matches()) {
                throw new FixtureException(what + ": object " + id + " needs a value of 1..240 printable ASCII characters");
            }
            if (!ids.isEmpty() && id <= ids.get(ids.size() - 1)) {
                throw new FixtureException(what + ": object ids must be unique and ascending");
            }
            if (id >= 7 && id <= 127) {
                throw new FixtureException(what + ": object id " + id + " is reserved");
            }
            ids.add(id);
        }
        for (int basic = 0; basic <= 2; basic++) {
            if (!ids.contains(basic)) {
                throw new FixtureException(what + ": basic object " + basic + " is missing");
            }
        }
    }
}
