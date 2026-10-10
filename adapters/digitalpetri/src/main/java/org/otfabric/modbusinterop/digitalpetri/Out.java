// SPDX-License-Identifier: MIT
package org.otfabric.modbusinterop.digitalpetri;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** stdout: one JSON object per line, written whole and in order. */
final class Out {

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final PrintStream STDOUT =
            new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);

    private boolean closed;

    /** Prints one document on one line. */
    synchronized void line(JsonObject document) {
        if (closed) {
            return;
        }
        STDOUT.print(GSON.toJson(document) + "\n");
        STDOUT.flush();
    }

    /** Prints the last document; whatever comes after it is dropped. */
    synchronized void last(JsonObject document) {
        line(document);
        closed = true;
    }

    static PrintStream raw() {
        return STDOUT;
    }
}
