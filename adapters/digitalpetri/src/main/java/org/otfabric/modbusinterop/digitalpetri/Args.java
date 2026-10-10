// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command line flags of the form {@code --name value}. Anything else is a usage error. */
final class Args {

    /** A command line the contract does not allow: exit 2, nothing is sent. */
    static final class UsageException extends Exception {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    private final Map<String, String> values = new LinkedHashMap<>();

    /** Parses {@code tokens}; every flag must be in {@code allowed} and carry a value. */
    Args(List<String> tokens, Set<String> allowed) throws UsageException {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--") || token.length() == 2) {
                throw new UsageException("unexpected argument: " + token);
            }
            String name = token.substring(2);
            if (!allowed.contains(name)) {
                throw new UsageException("unknown flag: " + token);
            }
            if (i + 1 >= tokens.size()) {
                throw new UsageException("flag " + token + " needs a value");
            }
            values.put(name, tokens.get(++i));
        }
    }

    boolean has(String name) {
        return values.containsKey(name);
    }

    String string(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    String required(String name) throws UsageException {
        String value = values.get(name);
        if (value == null) {
            throw new UsageException("missing flag: --" + name);
        }
        return value;
    }

    /** A required number within {@code min..max}. */
    int number(String name, int min, int max) throws UsageException {
        return parseNumber("--" + name, required(name), min, max);
    }

    /** An optional number within {@code min..max}. */
    int number(String name, int fallback, int min, int max) throws UsageException {
        return has(name) ? number(name, min, max) : fallback;
    }

    /** A required comma-separated list of numbers, each within {@code min..max}. */
    int[] numbers(String name, int minCount, int maxCount, int min, int max) throws UsageException {
        List<String> parts = list(name, minCount, maxCount);
        int[] out = new int[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parseNumber("--" + name, parts.get(i), min, max);
        }
        return out;
    }

    /** A required comma-separated list of coil values. */
    boolean[] bools(String name, int minCount, int maxCount) throws UsageException {
        List<String> parts = list(name, minCount, maxCount);
        boolean[] out = new boolean[parts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = parseBool("--" + name, parts.get(i));
        }
        return out;
    }

    boolean bool(String name) throws UsageException {
        return parseBool("--" + name, required(name));
    }

    private List<String> list(String name, int minCount, int maxCount) throws UsageException {
        String raw = required(name);
        List<String> parts = new ArrayList<>(List.of(raw.split(",", -1)));
        if (raw.isEmpty() || parts.size() < minCount || parts.size() > maxCount) {
            throw new UsageException(
                    "--" + name + " takes " + minCount + ".." + maxCount + " comma-separated values");
        }
        return parts;
    }

    /** Decimal, or hexadecimal with a 0x prefix. */
    static int parseNumber(String what, String text, int min, int max) throws UsageException {
        long value;
        try {
            if (text.startsWith("0x") || text.startsWith("0X")) {
                String digits = text.substring(2);
                if (digits.isEmpty() || digits.length() > 8 || !digits.matches("[0-9a-fA-F]+")) {
                    throw new NumberFormatException();
                }
                value = Long.parseLong(digits, 16);
            } else {
                if (text.isEmpty() || text.length() > 10 || !text.matches("[0-9]+")) {
                    throw new NumberFormatException();
                }
                value = Long.parseLong(text);
            }
        } catch (NumberFormatException e) {
            throw new UsageException(what + ": not a number: '" + text + "'");
        }
        if (value < min || value > max) {
            throw new UsageException(what + ": " + text + " is outside " + min + ".." + max);
        }
        return (int) value;
    }

    static boolean parseBool(String what, String text) throws UsageException {
        return switch (text) {
            case "true", "1", "on" -> true;
            case "false", "0", "off" -> false;
            default -> throw new UsageException(what + ": not a coil value (true/false, 1/0, on/off): '" + text + "'");
        };
    }
}
