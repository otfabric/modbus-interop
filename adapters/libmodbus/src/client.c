/* SPDX-License-Identifier: MIT */
/*
 * One bounded client operation with libmodbus, and its result document.
 *
 * Every operation is the library call an application would make
 * (modbus_read_bits, modbus_write_registers, ...). The library reports a
 * failure through errno only, so the result is built from the request and
 * from errno: an exception response is EMBXILFUN..EMBXGTAR.
 *
 * FC17 is modbus_report_slave_id(). libmodbus has no call for FC08, FC20,
 * FC21 and FC24, and cannot even receive their normal responses (it takes
 * the length of a response from its function code), so those operations
 * are refused before anything is sent.
 *
 * "raw" uses modbus_send_raw_request() and modbus_receive_confirmation().
 * The latter does not compare the response with the request, so this file
 * does: transaction, protocol and unit identifier, length and function code.
 */
#include "adapter.h"

#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <modbus.h>

#define MAX_VALUES 1968
/* A PDU is at most 253 bytes: the function code and 252 bytes of data. */
#define MAX_RAW_DATA (MODBUS_MAX_PDU_LENGTH - 1)

typedef enum {
    OP_READ_COILS,
    OP_READ_DISCRETE_INPUTS,
    OP_READ_HOLDING_REGISTERS,
    OP_READ_INPUT_REGISTERS,
    OP_WRITE_SINGLE_COIL,
    OP_WRITE_SINGLE_REGISTER,
    OP_WRITE_MULTIPLE_COILS,
    OP_WRITE_MULTIPLE_REGISTERS,
    OP_MASK_WRITE_REGISTER,
    OP_READ_WRITE_MULTIPLE_REGISTERS,
    OP_REPORT_SERVER_ID,
    OP_RAW
} op_id_t;

typedef struct {
    const char *name;
    op_id_t id;
    int function;
    const char *flags; /* space separated, each required */
} op_def_t;

static const op_def_t OPERATIONS[] = {
    {"read-coils", OP_READ_COILS, MODBUS_FC_READ_COILS, "address quantity"},
    {"read-discrete-inputs", OP_READ_DISCRETE_INPUTS, MODBUS_FC_READ_DISCRETE_INPUTS, "address quantity"},
    {"read-holding-registers", OP_READ_HOLDING_REGISTERS, MODBUS_FC_READ_HOLDING_REGISTERS, "address quantity"},
    {"read-input-registers", OP_READ_INPUT_REGISTERS, MODBUS_FC_READ_INPUT_REGISTERS, "address quantity"},
    {"write-single-coil", OP_WRITE_SINGLE_COIL, MODBUS_FC_WRITE_SINGLE_COIL, "address value"},
    {"write-single-register", OP_WRITE_SINGLE_REGISTER, MODBUS_FC_WRITE_SINGLE_REGISTER, "address value"},
    {"write-multiple-coils", OP_WRITE_MULTIPLE_COILS, MODBUS_FC_WRITE_MULTIPLE_COILS, "address values"},
    {"write-multiple-registers", OP_WRITE_MULTIPLE_REGISTERS, MODBUS_FC_WRITE_MULTIPLE_REGISTERS, "address values"},
    {"mask-write-register", OP_MASK_WRITE_REGISTER, MODBUS_FC_MASK_WRITE_REGISTER, "address and-mask or-mask"},
    {"read-write-multiple-registers", OP_READ_WRITE_MULTIPLE_REGISTERS, MODBUS_FC_WRITE_AND_READ_REGISTERS,
     "read-address read-quantity write-address values"},
    {"report-server-id", OP_REPORT_SERVER_ID, MODBUS_FC_REPORT_SLAVE_ID, ""},
    {"raw", OP_RAW, 0, "function data"},
};

/* Operations of the contract that libmodbus has no call for. */
static const struct {
    const char *name;
    const char *what;
} UNSUPPORTED[] = {
    {"read-device-identification", "Read Device Identification (FC43/14)"},
    {"diagnostics", "Diagnostics (FC08)"},
    {"read-file-record", "Read File Record (FC20)"},
    {"write-file-record", "Write File Record (FC21)"},
    {"read-fifo-queue", "Read FIFO Queue (FC24)"},
};

static const char COMMON_FLAGS[] = "host port unit-id connect-timeout-ms timeout-ms repeat";

typedef struct {
    const op_def_t *op;
    const char *host;
    long port, unit_id, connect_timeout_ms, timeout_ms, repeat;
    long address, quantity; /* the read part of FC23 */
    long write_address;
    long value;             /* FC05: 0/1, FC06: the register */
    long and_mask, or_mask;
    int value_count;
    uint16_t values[MAX_VALUES]; /* 0/1 for coils */
    long function;          /* raw */
    int data_length;
    uint8_t data[MAX_RAW_DATA];
} request_t;

/* What one exchange produced. */
typedef struct {
    const char *error;  /* NULL: a normal response */
    char message[200];
    int exception_function, exception_code; /* when error is "exception" */
    cJSON *response;
} outcome_t;

static int has_word(const char *list, const char *word)
{
    size_t len = strlen(word);

    for (const char *p = list; *p != '\0';) {
        const char *end = strchr(p, ' ');
        size_t n = end ? (size_t) (end - p) : strlen(p);

        if (n == len && !strncmp(p, word, n))
            return 1;
        p += n;
        while (*p == ' ')
            p++;
    }
    return 0;
}

static int usage(const char *format, const char *detail)
{
    fprintf(stderr, "modbus-interop client: ");
    fprintf(stderr, format, detail ? detail : "");
    fprintf(stderr, "\nusage: modbus-interop client <operation> --host HOST [--port N] [--unit-id N]\n"
                    "         [--connect-timeout-ms N] [--timeout-ms N] [--repeat N] <operation flags>\n"
                    "operations:");
    for (size_t i = 0; i < sizeof(OPERATIONS) / sizeof(OPERATIONS[0]); i++)
        fprintf(stderr, " %s", OPERATIONS[i].name);
    fprintf(stderr, "\n");
    return EXIT_USAGE;
}

/* The values of the flags given on the command line, by name. */
typedef struct {
    const char *name[32];
    const char *value[32];
    int count;
} flags_t;

static const char *flag(const flags_t *flags, const char *name)
{
    for (int i = 0; i < flags->count; i++) {
        if (!strcmp(flags->name[i], name))
            return flags->value[i];
    }
    return NULL;
}

static int number_flag(const flags_t *flags, const char *name, long min, long max, long *out)
{
    const char *text = flag(flags, name);

    if (text == NULL)
        return 0; /* keeps the default; required flags were checked before */
    if (parse_number(text, min, max, out) != 0) {
        fprintf(stderr, "modbus-interop client: --%s %s: must be a number %ld..%ld\n", name, text, min, max);
        return -1;
    }
    return 0;
}

static int parse_values(const char *text, int coils, int max, request_t *rq)
{
    char *copy = strdup(text);
    char *cursor = copy;
    int rc = 0;

    rq->value_count = 0;
    if (copy == NULL || text[0] == '\0')
        rc = -1;
    while (rc == 0 && cursor != NULL) {
        char *comma = strchr(cursor, ',');
        long number = 0;
        int boolean = 0;

        if (comma != NULL)
            *comma = '\0';
        if (rq->value_count >= max || rq->value_count >= MAX_VALUES)
            rc = -1;
        else if (coils ? parse_bool(cursor, &boolean) : parse_number(cursor, 0, 65535, &number))
            rc = -1;
        else
            rq->values[rq->value_count++] = (uint16_t) (coils ? boolean : number);
        cursor = comma ? comma + 1 : NULL;
    }
    free(copy);
    if (rc != 0)
        fprintf(stderr, "modbus-interop client: --values %s: must be 1..%d %s separated by commas\n", text, max,
                coils ? "coil values (true/false, 1/0, on/off)" : "register values 0..65535");
    return rc;
}

static int parse_hex(const char *text, request_t *rq)
{
    size_t len = strlen(text);

    if (len % 2 != 0 || len / 2 > MAX_RAW_DATA || strspn(text, "0123456789abcdefABCDEF") != len) {
        fprintf(stderr, "modbus-interop client: --data %s: must be at most %d bytes of hexadecimal\n", text,
                MAX_RAW_DATA);
        return -1;
    }
    for (size_t i = 0; i < len / 2; i++) {
        char byte[3] = {text[2 * i], text[2 * i + 1], '\0'};

        rq->data[i] = (uint8_t) strtol(byte, NULL, 16);
    }
    rq->data_length = (int) (len / 2);
    return 0;
}

/* Everything that can be wrong with the command line is found here, before
   any network access. Returns 0 or EXIT_USAGE. */
static int parse_arguments(int argc, char **argv, request_t *rq)
{
    flags_t flags = {.count = 0};
    const op_def_t *op = NULL;
    const char *text;
    int boolean;

    if (argc < 1)
        return usage("missing operation%s", NULL);
    for (size_t i = 0; i < sizeof(UNSUPPORTED) / sizeof(UNSUPPORTED[0]); i++) {
        if (!strcmp(argv[0], UNSUPPORTED[i].name)) {
            fprintf(stderr, "modbus-interop client: %s is not supported: libmodbus has no %s\n", argv[0],
                    UNSUPPORTED[i].what);
            return EXIT_USAGE;
        }
    }
    for (size_t i = 0; i < sizeof(OPERATIONS) / sizeof(OPERATIONS[0]); i++) {
        if (!strcmp(argv[0], OPERATIONS[i].name))
            op = &OPERATIONS[i];
    }
    if (op == NULL)
        return usage("unknown operation %s", argv[0]);

    for (int i = 1; i < argc; i += 2) {
        const char *name = argv[i];

        if (strncmp(name, "--", 2) != 0 || (!has_word(COMMON_FLAGS, name + 2) && !has_word(op->flags, name + 2)))
            return usage("unknown flag %s", name);
        if (i + 1 >= argc)
            return usage("missing value of %s", name);
        if (flag(&flags, name + 2) != NULL)
            return usage("%s is given twice", name);
        flags.name[flags.count] = name + 2;
        flags.value[flags.count] = argv[i + 1];
        flags.count++;
    }

    memset(rq, 0, sizeof(*rq));
    rq->op = op;
    rq->port = DEFAULT_PORT;
    rq->unit_id = 1;
    rq->connect_timeout_ms = 5000;
    rq->timeout_ms = 5000;
    rq->repeat = 1;

    rq->host = flag(&flags, "host");
    if (rq->host == NULL || rq->host[0] == '\0')
        return usage("--host is required%s", NULL);
    for (const char *p = op->flags; *p != '\0';) {
        char name[32];
        size_t n = strcspn(p, " ");

        snprintf(name, sizeof(name), "%.*s", (int) n, p);
        if (flag(&flags, name) == NULL)
            return usage("--%s is required", name);
        p += n;
        while (*p == ' ')
            p++;
    }

    if (number_flag(&flags, "port", 1, 65535, &rq->port) != 0 ||
        number_flag(&flags, "unit-id", 0, 255, &rq->unit_id) != 0 ||
        number_flag(&flags, "connect-timeout-ms", 1, 3600000, &rq->connect_timeout_ms) != 0 ||
        number_flag(&flags, "timeout-ms", 1, 3600000, &rq->timeout_ms) != 0 ||
        number_flag(&flags, "repeat", 1, 1000, &rq->repeat) != 0 ||
        number_flag(&flags, "address", 0, 65535, &rq->address) != 0 ||
        number_flag(&flags, "read-address", 0, 65535, &rq->address) != 0 ||
        number_flag(&flags, "write-address", 0, 65535, &rq->write_address) != 0 ||
        number_flag(&flags, "and-mask", 0, 65535, &rq->and_mask) != 0 ||
        number_flag(&flags, "or-mask", 0, 65535, &rq->or_mask) != 0 ||
        number_flag(&flags, "function", 1, 127, &rq->function) != 0)
        return EXIT_USAGE;

    switch (op->id) {
    case OP_READ_COILS:
    case OP_READ_DISCRETE_INPUTS:
        if (number_flag(&flags, "quantity", 1, MODBUS_MAX_READ_BITS, &rq->quantity) != 0)
            return EXIT_USAGE;
        break;
    case OP_READ_HOLDING_REGISTERS:
    case OP_READ_INPUT_REGISTERS:
        if (number_flag(&flags, "quantity", 1, MODBUS_MAX_READ_REGISTERS, &rq->quantity) != 0)
            return EXIT_USAGE;
        break;
    case OP_WRITE_SINGLE_COIL:
        text = flag(&flags, "value");
        if (parse_bool(text, &boolean) != 0)
            return usage("--value %s: must be true/false, 1/0 or on/off", text);
        rq->value = boolean;
        break;
    case OP_WRITE_SINGLE_REGISTER:
        if (number_flag(&flags, "value", 0, 65535, &rq->value) != 0)
            return EXIT_USAGE;
        break;
    case OP_WRITE_MULTIPLE_COILS:
        if (parse_values(flag(&flags, "values"), 1, MODBUS_MAX_WRITE_BITS, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_WRITE_MULTIPLE_REGISTERS:
        if (parse_values(flag(&flags, "values"), 0, MODBUS_MAX_WRITE_REGISTERS, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_MASK_WRITE_REGISTER:
    case OP_REPORT_SERVER_ID:
        break;
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        if (number_flag(&flags, "read-quantity", 1, MODBUS_MAX_WR_READ_REGISTERS, &rq->quantity) != 0 ||
            parse_values(flag(&flags, "values"), 0, MODBUS_MAX_WR_WRITE_REGISTERS, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_RAW:
        if (parse_hex(flag(&flags, "data"), rq) != 0)
            return EXIT_USAGE;
        break;
    }
    return 0;
}

/* The error code of the contract for a failed libmodbus call. */
static void outcome_from_errno(outcome_t *out, int function, int error)
{
    snprintf(out->message, sizeof(out->message), "%s", modbus_strerror(error));
    if (error > MODBUS_ENOBASE && error < MODBUS_ENOBASE + MODBUS_EXCEPTION_MAX) {
        out->error = "exception";
        out->exception_function = function + 0x80;
        out->exception_code = error - MODBUS_ENOBASE;
    } else if (error == ETIMEDOUT) {
        out->error = "timeout";
    } else if (error == ECONNRESET || error == EPIPE || error == ENOTCONN || error == ECONNABORTED ||
               error == EBADF) {
        out->error = "connection-lost";
    } else if (error == EMBBADDATA || error == EMBBADEXC || error == EMBUNKEXC || error == EMBBADCRC ||
               error == EMBBADSLAVE || error == EMBMDATA) {
        /* EMBBADEXC is also what libmodbus reports for an exception code it
           does not know (12 and above): the code itself is not handed out. */
        out->error = "invalid-response";
    } else {
        out->error = "failed";
    }
}

static void outcome_invalid(outcome_t *out, const char *message)
{
    out->error = "invalid-response";
    snprintf(out->message, sizeof(out->message), "%s", message);
}

static cJSON *response_object(int function)
{
    cJSON *o = cJSON_CreateObject();

    cJSON_AddNumberToObject(o, "function", function);
    return o;
}

static void exchange_raw(modbus_t *ctx, const request_t *rq, outcome_t *out)
{
    uint8_t request[MAX_RAW_DATA + 2];
    uint8_t rsp[MODBUS_TCP_MAX_ADU_LENGTH];
    const int header = modbus_get_header_length(ctx);
    const int function = (int) rq->function;
    char hex[2 * MODBUS_TCP_MAX_ADU_LENGTH + 1];
    int length, announced;

    request[0] = (uint8_t) rq->unit_id;
    request[1] = (uint8_t) function;
    memcpy(request + 2, rq->data, (size_t) rq->data_length);
    /* modbus_send_raw_request() uses transaction identifier 0. */
    if (modbus_send_raw_request(ctx, request, rq->data_length + 2) == -1 ||
        (length = modbus_receive_confirmation(ctx, rsp)) == -1) {
        outcome_from_errno(out, function, errno);
        /* Outside the typed calls an EMBX errno is not an exception response. */
        if (!strcmp(out->error, "exception"))
            out->error = "failed";
        return;
    }
    if (length < header + 1) {
        outcome_invalid(out, "response shorter than an MBAP header and a function code");
        return;
    }
    if (rsp[0] != 0 || rsp[1] != 0) {
        outcome_invalid(out, "transaction identifier of the response is not that of the request");
        return;
    }
    if (rsp[2] != 0 || rsp[3] != 0) {
        outcome_invalid(out, "protocol identifier of the response is not 0");
        return;
    }
    if (rsp[header - 1] != (uint8_t) rq->unit_id) {
        outcome_invalid(out, "unit identifier of the response is not that of the request");
        return;
    }
    announced = ((rsp[4] << 8) | rsp[5]) + 6;
    if (rsp[header] == (function | 0x80)) {
        if (length != header + 2 || announced != length || rsp[header + 1] == 0) {
            outcome_invalid(out, "exception response of the wrong length or with exception code 0");
            return;
        }
        out->error = "exception";
        out->exception_function = rsp[header];
        out->exception_code = rsp[header + 1];
        snprintf(out->message, sizeof(out->message), "exception %d", out->exception_code);
        return;
    }
    if (rsp[header] != function) {
        outcome_invalid(out, "function code of the response is not that of the request");
        return;
    }
    if (announced > length) {
        /* libmodbus takes the length of a response from the function code,
           not from the MBAP header: of a function it does not know it reads
           one byte of data and leaves the rest on the socket. Drop the rest,
           so that nothing of it can be taken for a later response (the
           repeat loop also stops at this failure). */
        modbus_flush(ctx);
        out->error = "failed";
        snprintf(out->message, sizeof(out->message),
                 "libmodbus cannot receive the response of function %d: it read %d of %d bytes", function,
                 length, announced);
        return;
    }
    if (announced < length) {
        outcome_invalid(out, "response longer than its MBAP header announces");
        return;
    }
    for (int i = header + 1; i < length; i++)
        snprintf(hex + 2 * (i - header - 1), 3, "%02x", rsp[i]);
    hex[2 * (length - header - 1)] = '\0';
    out->response = response_object(function);
    cJSON_AddStringToObject(out->response, "data", hex);
}

/* One request and its response. */
static void exchange(modbus_t *ctx, const request_t *rq, outcome_t *out)
{
    static uint8_t bits[MODBUS_MAX_READ_BITS];
    uint8_t id[MODBUS_MAX_PDU_LENGTH];
    char hex[2 * MODBUS_MAX_PDU_LENGTH + 1];
    uint16_t registers[MODBUS_MAX_READ_REGISTERS];
    const int function = rq->op->function;
    const int address = (int) rq->address;
    const int quantity = (int) rq->quantity;
    cJSON *array;
    int rc = -1;

    memset(out, 0, sizeof(*out));
    errno = 0;
    switch (rq->op->id) {
    case OP_RAW:
        exchange_raw(ctx, rq, out);
        return;
    case OP_READ_COILS:
        rc = modbus_read_bits(ctx, address, quantity, bits);
        break;
    case OP_READ_DISCRETE_INPUTS:
        rc = modbus_read_input_bits(ctx, address, quantity, bits);
        break;
    case OP_READ_HOLDING_REGISTERS:
        rc = modbus_read_registers(ctx, address, quantity, registers);
        break;
    case OP_READ_INPUT_REGISTERS:
        rc = modbus_read_input_registers(ctx, address, quantity, registers);
        break;
    case OP_WRITE_SINGLE_COIL:
        rc = modbus_write_bit(ctx, address, (int) rq->value);
        break;
    case OP_WRITE_SINGLE_REGISTER:
        rc = modbus_write_register(ctx, address, (uint16_t) rq->value);
        break;
    case OP_WRITE_MULTIPLE_COILS:
        for (int i = 0; i < rq->value_count; i++)
            bits[i] = (uint8_t) rq->values[i];
        rc = modbus_write_bits(ctx, address, rq->value_count, bits);
        break;
    case OP_WRITE_MULTIPLE_REGISTERS:
        rc = modbus_write_registers(ctx, address, rq->value_count, rq->values);
        break;
    case OP_MASK_WRITE_REGISTER:
        rc = modbus_mask_write_register(ctx, address, (uint16_t) rq->and_mask, (uint16_t) rq->or_mask);
        break;
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        rc = modbus_write_and_read_registers(ctx, (int) rq->write_address, rq->value_count, rq->values, address,
                                             quantity, registers);
        break;
    case OP_REPORT_SERVER_ID:
        /* Returns the byte count and copies at most sizeof(id) of the
           bytes after it. */
        rc = modbus_report_slave_id(ctx, (int) sizeof(id), id);
        break;
    }
    if (rc == -1) {
        outcome_from_errno(out, function, errno);
        return;
    }

    /* libmodbus checks the response against the request and hands out the
       values of a read only. For a write the fields below are those of the
       request, which is what the library confirmed. */
    out->response = response_object(function);
    switch (rq->op->id) {
    case OP_READ_COILS:
    case OP_READ_DISCRETE_INPUTS:
        array = cJSON_AddArrayToObject(out->response, "bits");
        for (int i = 0; i < quantity; i++)
            cJSON_AddItemToArray(array, cJSON_CreateBool(bits[i]));
        break;
    case OP_READ_HOLDING_REGISTERS:
    case OP_READ_INPUT_REGISTERS:
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        array = cJSON_AddArrayToObject(out->response, "registers");
        for (int i = 0; i < rc && i < quantity; i++)
            cJSON_AddItemToArray(array, cJSON_CreateNumber(registers[i]));
        break;
    case OP_WRITE_SINGLE_COIL:
        cJSON_AddNumberToObject(out->response, "address", address);
        cJSON_AddBoolToObject(out->response, "value", rq->value != 0);
        break;
    case OP_WRITE_SINGLE_REGISTER:
        cJSON_AddNumberToObject(out->response, "address", address);
        cJSON_AddNumberToObject(out->response, "value", (double) rq->value);
        break;
    case OP_WRITE_MULTIPLE_COILS:
    case OP_WRITE_MULTIPLE_REGISTERS:
        cJSON_AddNumberToObject(out->response, "address", address);
        cJSON_AddNumberToObject(out->response, "quantity", rq->value_count);
        break;
    case OP_MASK_WRITE_REGISTER:
        cJSON_AddNumberToObject(out->response, "address", address);
        cJSON_AddNumberToObject(out->response, "andMask", (double) rq->and_mask);
        cJSON_AddNumberToObject(out->response, "orMask", (double) rq->or_mask);
        break;
    case OP_REPORT_SERVER_ID:
        for (int i = 0; i < rc && i < (int) sizeof(id); i++)
            snprintf(hex + 2 * i, 3, "%02x", id[i]);
        hex[2 * (rc < (int) sizeof(id) ? rc : (int) sizeof(id))] = '\0';
        cJSON_AddStringToObject(out->response, "data", hex);
        break;
    case OP_RAW:
        break;
    }
}

static void set_timeout(modbus_t *ctx, long ms)
{
    modbus_set_response_timeout(ctx, (uint32_t) (ms / 1000), (uint32_t) (ms % 1000) * 1000);
}

int client_main(int argc, char **argv)
{
    static request_t rq;
    outcome_t out;
    modbus_t *ctx;
    cJSON *doc, *response = NULL;
    char *first = NULL;
    char service[16], resolver[64];
    const char *error = NULL;
    char message[200] = "";
    int connected = 0, requests = 0, responses = 0;
    int exception_function = 0, exception_code = 0;
    double started;
    int rc = parse_arguments(argc, argv, &rq);

    if (rc != 0)
        return rc;

    started = monotonic_ms();
    snprintf(service, sizeof(service), "%ld", rq.port);
    /* The name lookup inside modbus_connect() is the C library's and knows
       nothing of libmodbus timeouts: bound it as well. */
    snprintf(resolver, sizeof(resolver), "timeout:%ld attempts:1", (rq.connect_timeout_ms + 999) / 1000);
    setenv("RES_OPTIONS", resolver, 0);

    ctx = modbus_new_tcp_pi(rq.host, service);
    if (ctx == NULL) {
        error = "failed";
        snprintf(message, sizeof(message), "modbus_new_tcp_pi: %s", modbus_strerror(errno));
    } else {
        /* Without this quirk libmodbus refuses the unit identifiers 248..254. */
        modbus_enable_quirks(ctx, MODBUS_QUIRK_MAX_SLAVE);
        if (modbus_set_slave(ctx, (int) rq.unit_id) == -1) {
            error = "failed";
            snprintf(message, sizeof(message), "modbus_set_slave: %s", modbus_strerror(errno));
        }
    }

    if (error == NULL) {
        /* libmodbus has no connect timeout of its own: modbus_connect() waits
           for the response timeout. */
        set_timeout(ctx, rq.connect_timeout_ms);
        if (modbus_connect(ctx) == -1) {
            error = "connect-failed";
            snprintf(message, sizeof(message), "%s", modbus_strerror(errno));
        } else {
            connected = 1;
            /* The response timeout bounds the whole response once the byte
               timeout is off (with it, each further read would get 500 ms). */
            set_timeout(ctx, rq.timeout_ms);
            modbus_set_byte_timeout(ctx, 0, 0);
        }
    }

    for (long i = 0; error == NULL && i < rq.repeat; i++) {
        char *text;

        requests++;
        exchange(ctx, &rq, &out);
        if (out.error != NULL) {
            error = out.error;
            snprintf(message, sizeof(message), "%s", out.message);
            exception_function = out.exception_function;
            exception_code = out.exception_code;
            break;
        }
        responses++;
        text = cJSON_PrintUnformatted(out.response);
        if (first == NULL) {
            first = text;
        } else {
            if (text == NULL || strcmp(first, text) != 0) {
                error = "failed";
                snprintf(message, sizeof(message), "response %d differs from the first", responses);
            }
            free(text);
        }
        cJSON_Delete(response);
        response = out.response;
    }
    free(first);
    if (ctx != NULL) {
        modbus_close(ctx);
        modbus_free(ctx);
    }

    doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "schemaVersion", SCHEMA_VERSION);
    cJSON_AddStringToObject(doc, "adapter", ADAPTER_NAME);
    cJSON_AddStringToObject(doc, "operation", rq.op->name);
    cJSON_AddBoolToObject(doc, "ok", error == NULL);
    if (error == NULL) {
        cJSON_AddNullToObject(doc, "error");
    } else {
        cJSON *e = cJSON_AddObjectToObject(doc, "error");

        cJSON_AddStringToObject(e, "code", error);
        cJSON_AddStringToObject(e, "message", message);
    }
    cJSON_AddBoolToObject(doc, "connected", connected);
    cJSON_AddNumberToObject(doc, "unitId", (double) rq.unit_id);
    cJSON_AddNumberToObject(doc, "requests", requests);
    cJSON_AddNumberToObject(doc, "responses", responses);
    if (response != NULL)
        cJSON_AddItemToObject(doc, "response", response);
    else
        cJSON_AddNullToObject(doc, "response");
    if (error != NULL && !strcmp(error, "exception")) {
        cJSON *x = cJSON_AddObjectToObject(doc, "exception");

        cJSON_AddNumberToObject(x, "function", exception_function);
        cJSON_AddNumberToObject(x, "code", exception_code);
    } else {
        cJSON_AddNullToObject(doc, "exception");
    }
    cJSON_AddNumberToObject(doc, "elapsedMs", round((monotonic_ms() - started) * 1000.0) / 1000.0);
    emit_json(doc);

    if (error == NULL)
        return EXIT_OK;
    return strcmp(error, "connect-failed") == 0 ? EXIT_CONNECT : EXIT_FAILED;
}
