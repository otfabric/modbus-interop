/* SPDX-License-Identifier: MIT */
/*
 * modbus-interop adapter for modbus-esp8266: command dispatch,
 * print-capabilities, print-fixture and version. The contract is
 * docs/CONTAINER_CONTRACT.md.
 */
#include "modbusesp.h"

#include <stdio.h>
#include <string.h>

/* MODBUSIP_TIMEOUT and MODBUSRTU_TIMEOUT (settings_overlay.h). */
uint32_t modbusesp_timeout_ms = 1000;

int parse_transport(const char *text, transport_t *out)
{
    if (!strcmp(text, "tcp")) {
        *out = TRANSPORT_TCP;
        return 0;
    }
    if (!strcmp(text, "rtu-over-tcp")) {
        *out = TRANSPORT_RTU_OVER_TCP;
        return 0;
    }
    return -1;
}

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

static int print_capabilities()
{
    static const struct {
        const char *name;
        int value;
    } features[] = {
        /* Unit identifier, quantities and address ranges: the device rules,
           applied in the library's onRequest()/onRaw()/onFile() callbacks
           (server.cpp). */
        {"serverUnitIdFiltering", 1},
        {"serverQuantityCheck", 1},
        {"serverMaskWrite", 1},
        {"serverReadWriteMultiple", 1},
        /* The library has no FC43 in either role. */
        {"serverDeviceIdentification", 0},
        /* The library's callbacks do not tell what the response was (the
           exceptions it decides itself are not reported back), nor who is
           connected: onDisconnect() passes no address at all. */
        {"requestEvents", 0},
        {"connectionEvents", 0},
        {"clientMaskWrite", 1},
        {"clientReadWriteMultiple", 1},
        {"clientDeviceIdentification", 0},
        {"clientRaw", 1},
        {"clientRepeat", 1},
        /* ModbusTCPTemplate matches a response by transaction identifier
           and function code only. */
        {"clientUnitIdCheck", 0},
        /* FC08, FC17 and FC24 the library does not implement. */
        {"serverDiagnostics", 0},
        {"serverReportServerId", 0},
        /* The library's FC21 is right, but its FC20 response carries a byte
           count 2 too large (README, finding 2). */
        {"serverFileRecord", 0},
        {"serverFifoQueue", 0},
        {"clientDiagnostics", 0},
        {"clientReportServerId", 0},
        /* The library's client refuses FC20 responses with the right byte
           count (README, finding 3). */
        {"clientReadFileRecord", 0},
        {"clientWriteFileRecord", 1},
        {"clientFifoQueue", 0},
        /* The library frames a Modbus TCP response by its MBAP length and
           hands any of them to onRaw(). */
        {"clientRawAnyResponse", 1},
        /* Contract 1.2. slavePDU answers an unknown function with
           exception 1 itself. */
        {"serverIllegalFunction", 1},
        /* The adapter's onRequest() refuses a write that leaves the block
           before the library writes anything (the library itself checks
           every address of FC15/FC16 before it writes, too). */
        {"serverRejectedWriteUnchanged", 1},
        /* writeHreg() with 123 registers and readWriteHreg() with 121
           written: checked against this server and PyModbus. */
        {"clientLargestRegisterWrites", 1},
        /* addReg() never creates a register at 65535 (Modbus.cpp:65, an
           off-by-one), so a read that reaches it is exception 2 (README,
           finding 1). */
        {"serverHighestAddress", 0},
        {"serverLargestRegisterWrites", 1},
    };
    static const int functions[] = {1, 2, 3, 4, 5, 6, 15, 16, 20, 21, 22, 23};
    static const char *const operations[] = {
        "read-coils", "read-discrete-inputs", "read-holding-registers", "read-input-registers",
        "write-single-coil", "write-single-register", "write-multiple-coils", "write-multiple-registers",
        "mask-write-register", "read-write-multiple-registers", "write-file-record", "raw"};
    static const char *const transports[] = {"tcp", "rtu-over-tcp"};
    cJSON *doc = cJSON_CreateObject();
    cJSON *upstream, *roles, *list, *t;

    cJSON_AddStringToObject(doc, "schemaVersion", SCHEMA_VERSION);
    cJSON_AddStringToObject(doc, "adapter", ADAPTER_NAME);
    cJSON_AddStringToObject(doc, "adapterVersion", ADAPTER_VERSION);
    cJSON_AddStringToObject(doc, "protocol", "modbus-tcp");
    upstream = cJSON_AddObjectToObject(doc, "upstream");
    cJSON_AddStringToObject(upstream, "name", "modbus-esp8266");
    cJSON_AddStringToObject(upstream, "version", UPSTREAM_VERSION);
    cJSON_AddStringToObject(upstream, "revision", UPSTREAM_REVISION);
    cJSON_AddStringToObject(upstream, "license", "BSD-3-Clause");
    cJSON_AddStringToObject(upstream, "language", "C++");
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
    t = cJSON_AddObjectToObject(doc, "transports");
    cJSON_AddItemToObject(t, "server", cJSON_CreateStringArray(transports, 2));
    cJSON_AddItemToObject(t, "client", cJSON_CreateStringArray(transports, 2));
    emit_json(doc);
    return EXIT_OK;
}

/* Byte for byte: no parsing, no re-encoding. */
static int print_fixture(const char *name)
{
    char path[512];
    static char buffer[65536];
    size_t n, len = strlen(name);
    FILE *f;

    if (len == 0 || len > 200 || name[0] == '-' ||
        strspn(name, "abcdefghijklmnopqrstuvwxyz0123456789-") != len) {
        fprintf(stderr, "modbus-interop: no fixture named \"%s\"\n", name);
        return EXIT_FAILED;
    }
    snprintf(path, sizeof(path), FIXTURES_DIR "/%s/fixture.json", name);
    f = fopen(path, "rb");
    if (f == nullptr) {
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
        return usage("missing command", nullptr);
    command = argv[1];
    if (!strcmp(command, "server"))
        return server_main(argc - 2, argv + 2);
    if (!strcmp(command, "client"))
        return client_main(argc - 2, argv + 2);
    if (!strcmp(command, "print-capabilities")) {
        if (argc != 2)
            return usage("print-capabilities takes no argument", nullptr);
        return print_capabilities();
    }
    if (!strcmp(command, "print-fixture")) {
        if (argc > 3)
            return usage("print-fixture takes one name", nullptr);
        return print_fixture(argc == 3 ? argv[2] : "baseline");
    }
    if (!strcmp(command, "version")) {
        if (argc != 2)
            return usage("version takes no argument", nullptr);
        printf("modbus-interop %s %s (modbus-esp8266 %s, commit %s)\n", ADAPTER_NAME, ADAPTER_VERSION,
               UPSTREAM_VERSION, UPSTREAM_REVISION);
        return EXIT_OK;
    }
    return usage("unknown command ", command);
}
