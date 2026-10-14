/* SPDX-License-Identifier: MIT */
/*
 * modbus-interop adapter for libmodbus: command dispatch, print-capabilities,
 * print-fixture and version. The contract is docs/CONTAINER_CONTRACT.md.
 */
#include "adapter.h"

#include <stdio.h>
#include <string.h>

#include <modbus.h>

static int usage(const char *message, const char *detail)
{
    fprintf(stderr, "modbus-interop: %s%s\n", message, detail ? detail : "");
    fprintf(stderr, "usage: modbus-interop server [flags]\n"
                    "       modbus-interop client <operation> --host HOST [flags]\n"
                    "       modbus-interop print-capabilities\n"
                    "       modbus-interop print-fixture [NAME]\n"
                    "       modbus-interop version\n");
    return EXIT_USAGE;
}

static int print_capabilities(void)
{
    static const struct {
        const char *name;
        int value;
    } features[] = {
        {"serverUnitIdFiltering", 1},
        {"serverQuantityCheck", 1},
        {"serverMaskWrite", 1},
        {"serverReadWriteMultiple", 1},
        /* libmodbus has no Read Device Identification, in either role. */
        {"serverDeviceIdentification", 0},
        {"requestEvents", 1},
        {"connectionEvents", 1},
        {"clientMaskWrite", 1},
        {"clientReadWriteMultiple", 1},
        {"clientDeviceIdentification", 0},
        {"clientRaw", 1},
        {"clientRepeat", 1},
        /* The TCP backend of libmodbus does not compare the unit identifier
           of a response with that of the request. */
        {"clientUnitIdCheck", 0},
        /* The FC17 answer of libmodbus is its own fixed identity, not that of
           the fixture, so the server answers FC17 with exception 1. FC08,
           FC20, FC21 and FC24 it does not implement. */
        {"serverDiagnostics", 0},
        {"serverReportServerId", 0},
        {"serverFileRecord", 0},
        {"serverFifoQueue", 0},
        /* Of these the client library has modbus_report_slave_id() only. */
        {"clientDiagnostics", 0},
        {"clientReportServerId", 1},
        {"clientReadFileRecord", 0},
        {"clientWriteFileRecord", 0},
        {"clientFifoQueue", 0},
        /* libmodbus frames a response by its function code: raw cannot
           receive the normal response of a function it does not know. */
        {"clientRawAnyResponse", 0},
    };
    static const int functions[] = {1, 2, 3, 4, 5, 6, 15, 16, 22, 23};
    static const char *const operations[] = {
        "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
        "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
        "mask-write-register", "read-write-multiple-registers", "report-server-id", "raw"};
    cJSON *doc = cJSON_CreateObject();
    cJSON *upstream, *roles, *list;

    cJSON_AddStringToObject(doc, "schemaVersion", SCHEMA_VERSION);
    cJSON_AddStringToObject(doc, "adapter", ADAPTER_NAME);
    cJSON_AddStringToObject(doc, "adapterVersion", ADAPTER_VERSION);
    cJSON_AddStringToObject(doc, "protocol", "modbus-tcp");
    upstream = cJSON_AddObjectToObject(doc, "upstream");
    cJSON_AddStringToObject(upstream, "name", "libmodbus");
    cJSON_AddStringToObject(upstream, "version", UPSTREAM_VERSION);
    cJSON_AddStringToObject(upstream, "revision", UPSTREAM_REVISION);
    cJSON_AddStringToObject(upstream, "license", "LGPL-2.1-or-later");
    cJSON_AddStringToObject(upstream, "language", "C");
    cJSON_AddBoolToObject(upstream, "independentEngine", 1);
    roles = cJSON_AddObjectToObject(doc, "roles");
    cJSON_AddBoolToObject(roles, "server", 1);
    cJSON_AddBoolToObject(roles, "client", 1);
    list = cJSON_AddObjectToObject(doc, "features");
    for (size_t i = 0; i < sizeof(features) / sizeof(features[0]); i++)
        cJSON_AddBoolToObject(list, features[i].name, features[i].value);
    cJSON_AddItemToObject(doc, "serverFunctions",
                          cJSON_CreateIntArray(functions, (int) (sizeof(functions) / sizeof(functions[0]))));
    cJSON_AddItemToObject(doc, "clientOperations",
                          cJSON_CreateStringArray(operations, (int) (sizeof(operations) / sizeof(operations[0]))));
    emit_json(doc);
    return EXIT_OK;
}

/* Byte for byte: no parsing, no re-encoding. */
static int print_fixture(const char *name)
{
    char path[512];
    char buffer[65536];
    size_t n, len = strlen(name);
    FILE *f;

    /* A fixture name, not a path. */
    if (len == 0 || len > 200 || name[0] == '-' ||
        strspn(name, "abcdefghijklmnopqrstuvwxyz0123456789-") != len) {
        fprintf(stderr, "modbus-interop: no fixture named \"%s\"\n", name);
        return EXIT_FAILED;
    }
    snprintf(path, sizeof(path), FIXTURES_DIR "/%s/fixture.json", name);
    f = fopen(path, "rb");
    if (f == NULL) {
        fprintf(stderr, "modbus-interop: no fixture named \"%s\" (%s)\n", name, path);
        return EXIT_FAILED;
    }
    while ((n = fread(buffer, 1, sizeof(buffer), f)) > 0) {
        if (fwrite(buffer, 1, n, stdout) != n) {
            fclose(f);
            return EXIT_FAILED;
        }
    }
    if (ferror(f) || fflush(stdout) != 0) {
        fclose(f);
        return EXIT_FAILED;
    }
    fclose(f);
    return EXIT_OK;
}

int main(int argc, char **argv)
{
    const char *command;

    if (argc < 2)
        return usage("missing command", NULL);
    command = argv[1];
    if (!strcmp(command, "server"))
        return server_main(argc - 2, argv + 2);
    if (!strcmp(command, "client"))
        return client_main(argc - 2, argv + 2);
    if (!strcmp(command, "print-capabilities")) {
        if (argc != 2)
            return usage("print-capabilities takes no argument", NULL);
        return print_capabilities();
    }
    if (!strcmp(command, "print-fixture")) {
        if (argc > 3)
            return usage("print-fixture takes one name", NULL);
        return print_fixture(argc == 3 ? argv[2] : "baseline");
    }
    if (!strcmp(command, "version")) {
        if (argc != 2)
            return usage("version takes no argument", NULL);
        /* The version the adapter was built against, and the one of the
           shared library that is loaded now. */
        printf("modbus-interop %s %s (libmodbus %s, commit %s; loaded libmodbus %u.%u.%u)\n", ADAPTER_NAME,
               ADAPTER_VERSION, UPSTREAM_VERSION, UPSTREAM_REVISION, libmodbus_version_major,
               libmodbus_version_minor, libmodbus_version_micro);
        return EXIT_OK;
    }
    return usage("unknown command ", command);
}
