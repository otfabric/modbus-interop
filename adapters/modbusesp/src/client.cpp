/* SPDX-License-Identifier: MIT */
/*
 * One bounded client operation with modbus-esp8266, and its result document.
 *
 * The library's client API is asynchronous, as on a board: a call such as
 * readHreg(ip, offset, values, count, callback, unit) builds and sends the
 * request and returns a transaction identifier; the application then calls
 * task() in its loop until the library calls the callback with a result code
 * (Modbus::ResultCode). This file is that loop.
 *
 * Modbus TCP is a ModbusPosix object (connect(), then the call);
 * rtu-over-tcp is a ModbusRTU object bound to a TCP connection as its
 * Stream, the slave id being the unit identifier.
 *
 * The result code alone is ambiguous: an exception response is reported as
 * its exception code (Modbus::masterPDU), and the library uses the same
 * numbers for its own checks (EX_ILLEGAL_VALUE for a FC20 response it does
 * not accept). The library's onRaw() callback is handed every response frame
 * before it is processed; the adapter notes its function code there, so that
 * a code is reported as an exception only when the device sent an exception
 * response. onRaw() is also how "raw" receives its response: the library's
 * rawRequest() sends any PDU, and the response PDU is taken from onRaw() as
 * the library framed it.
 *
 * The library has no FC08, FC17, FC24 or FC43 in its client API: those
 * operations are refused before anything is sent.
 */
#include "modbusesp.h"

#include <math.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <string>
#include <vector>

#define MAX_VALUES 1968
#define MAX_RAW_DATA 252

enum op_id_t {
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
    OP_READ_FILE_RECORD,
    OP_WRITE_FILE_RECORD,
    OP_RAW
};

struct op_def_t {
    const char *name;
    op_id_t id;
    int function;
    const char *flags; /* space separated, each required */
};

static const op_def_t OPERATIONS[] = {
    {"read-coils", OP_READ_COILS, 1, "address quantity"},
    {"read-discrete-inputs", OP_READ_DISCRETE_INPUTS, 2, "address quantity"},
    {"read-holding-registers", OP_READ_HOLDING_REGISTERS, 3, "address quantity"},
    {"read-input-registers", OP_READ_INPUT_REGISTERS, 4, "address quantity"},
    {"write-single-coil", OP_WRITE_SINGLE_COIL, 5, "address value"},
    {"write-single-register", OP_WRITE_SINGLE_REGISTER, 6, "address value"},
    {"write-multiple-coils", OP_WRITE_MULTIPLE_COILS, 15, "address values"},
    {"write-multiple-registers", OP_WRITE_MULTIPLE_REGISTERS, 16, "address values"},
    {"mask-write-register", OP_MASK_WRITE_REGISTER, 22, "address and-mask or-mask"},
    {"read-write-multiple-registers", OP_READ_WRITE_MULTIPLE_REGISTERS, 23,
     "read-address read-quantity write-address values"},
    {"read-file-record", OP_READ_FILE_RECORD, 20, "file record length"},
    {"write-file-record", OP_WRITE_FILE_RECORD, 21, "file record values"},
    {"raw", OP_RAW, 0, "function data"},
};

static const struct {
    const char *name;
    const char *what;
} UNSUPPORTED[] = {
    {"read-device-identification", "Read Device Identification (FC43/14)"},
    {"diagnostics", "Diagnostics (FC08)"},
    {"report-server-id", "Report Server ID (FC17)"},
    {"read-fifo-queue", "Read FIFO Queue (FC24)"},
    /* readFileRec() exists, but refuses every correct response (README,
       finding 3): the operation is not offered. */
    {"read-file-record", "working Read File Record (FC20) client"},
};

static const char COMMON_FLAGS[] = "host port unit-id connect-timeout-ms timeout-ms repeat transport";

struct request_t {
    const op_def_t *op;
    const char *host;
    transport_t transport;
    long port, unit_id, connect_timeout_ms, timeout_ms, repeat;
    long address, quantity; /* the read part of FC23; FC20: record, length */
    long write_address;
    long value;
    long and_mask, or_mask;
    long file;
    int value_count;
    uint16_t values[MAX_VALUES];
    long function;
    int data_length;
    uint8_t data[MAX_RAW_DATA];
};

struct outcome_t {
    const char *error; /* nullptr: a normal response */
    char message[200];
    int exception_function, exception_code;
    cJSON *response;
};

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
                    "         [--connect-timeout-ms N] [--timeout-ms N] [--repeat N]\n"
                    "         [--transport tcp|rtu-over-tcp] <operation flags>\n"
                    "operations:");
    for (const op_def_t &op : OPERATIONS)
        fprintf(stderr, " %s", op.name);
    fprintf(stderr, "\n");
    return EXIT_USAGE;
}

struct flags_t {
    const char *name[32];
    const char *value[32];
    int count;
};

static const char *flag(const flags_t *flags, const char *name)
{
    for (int i = 0; i < flags->count; i++) {
        if (!strcmp(flags->name[i], name))
            return flags->value[i];
    }
    return nullptr;
}

static int number_flag(const flags_t *flags, const char *name, long min, long max, long *out)
{
    const char *text = flag(flags, name);

    if (text == nullptr)
        return 0;
    if (parse_number(text, min, max, out) != 0) {
        fprintf(stderr, "modbus-interop client: --%s %s: must be a number %ld..%ld\n", name, text, min, max);
        return -1;
    }
    return 0;
}

static int parse_values(const char *text, int coils, int max, request_t *rq)
{
    std::string copy(text);
    size_t start = 0;

    rq->value_count = 0;
    if (copy.empty())
        goto bad;
    for (;;) {
        size_t comma = copy.find(',', start);
        std::string item = copy.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
        long number = 0;
        int boolean = 0;

        if (rq->value_count >= max || rq->value_count >= MAX_VALUES)
            goto bad;
        if (coils ? parse_bool(item.c_str(), &boolean) : parse_number(item.c_str(), 0, 65535, &number))
            goto bad;
        rq->values[rq->value_count++] = (uint16_t) (coils ? boolean : number);
        if (comma == std::string::npos)
            return 0;
        start = comma + 1;
    }
bad:
    fprintf(stderr, "modbus-interop client: --values %s: must be 1..%d %s separated by commas\n", text, max,
            coils ? "coil values (true/false, 1/0, on/off)" : "register values 0..65535");
    return -1;
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
        rq->data[i] = (uint8_t) strtol(byte, nullptr, 16);
    }
    rq->data_length = (int) (len / 2);
    return 0;
}

static int parse_arguments(int argc, char **argv, request_t *rq)
{
    flags_t flags;
    const op_def_t *op = nullptr;
    const char *text;
    int boolean;

    flags.count = 0;
    if (argc < 1)
        return usage("missing operation%s", nullptr);
    for (const auto &u : UNSUPPORTED) {
        if (!strcmp(argv[0], u.name)) {
            fprintf(stderr, "modbus-interop client: %s is not supported: modbus-esp8266 has no %s\n", argv[0],
                    u.what);
            return EXIT_USAGE;
        }
    }
    for (const op_def_t &o : OPERATIONS) {
        if (!strcmp(argv[0], o.name))
            op = &o;
    }
    if (op == nullptr)
        return usage("unknown operation %s", argv[0]);

    for (int i = 1; i < argc; i += 2) {
        const char *name = argv[i];

        if (strncmp(name, "--", 2) != 0 || (!has_word(COMMON_FLAGS, name + 2) && !has_word(op->flags, name + 2)))
            return usage("unknown flag %s", name);
        if (i + 1 >= argc)
            return usage("missing value of %s", name);
        if (flag(&flags, name + 2) != nullptr)
            return usage("%s is given twice", name);
        if (flags.count == 32)
            return usage("too many flags%s", nullptr);
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
    rq->transport = TRANSPORT_TCP;

    text = flag(&flags, "transport");
    if (text != nullptr && parse_transport(text, &rq->transport) != 0)
        return usage("--transport %s: must be tcp or rtu-over-tcp (the library has no Modbus over UDP)", text);

    rq->host = flag(&flags, "host");
    if (rq->host == nullptr || rq->host[0] == '\0')
        return usage("--host is required%s", nullptr);
    for (const char *p = op->flags; *p != '\0';) {
        char name[32];
        size_t n = strcspn(p, " ");

        snprintf(name, sizeof(name), "%.*s", (int) n, p);
        if (flag(&flags, name) == nullptr)
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
        number_flag(&flags, "file", 1, 65535, &rq->file) != 0 ||
        number_flag(&flags, "record", 0, 9999, &rq->address) != 0 ||
        number_flag(&flags, "function", 1, 127, &rq->function) != 0)
        return EXIT_USAGE;

    switch (op->id) {
    case OP_READ_COILS:
    case OP_READ_DISCRETE_INPUTS:
        if (number_flag(&flags, "quantity", 1, 2000, &rq->quantity) != 0)
            return EXIT_USAGE;
        break;
    case OP_READ_HOLDING_REGISTERS:
    case OP_READ_INPUT_REGISTERS:
        if (number_flag(&flags, "quantity", 1, 125, &rq->quantity) != 0)
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
        if (parse_values(flag(&flags, "values"), 1, 1968, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_WRITE_MULTIPLE_REGISTERS:
        if (parse_values(flag(&flags, "values"), 0, 123, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_MASK_WRITE_REGISTER:
        break;
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        if (number_flag(&flags, "read-quantity", 1, 125, &rq->quantity) != 0 ||
            parse_values(flag(&flags, "values"), 0, 121, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_READ_FILE_RECORD:
        if (number_flag(&flags, "length", 1, 121, &rq->quantity) != 0)
            return EXIT_USAGE;
        break;
    case OP_WRITE_FILE_RECORD:
        if (parse_values(flag(&flags, "values"), 0, 122, rq) != 0)
            return EXIT_USAGE;
        break;
    case OP_RAW:
        if (parse_hex(flag(&flags, "data"), rq) != 0)
            return EXIT_USAGE;
        break;
    }
    return 0;
}

// ---------------------------------------------------------------------------
// The exchange
// ---------------------------------------------------------------------------

/* What the library's callbacks report for the transaction in progress. */
static struct {
    bool done;
    Modbus::ResultCode result;
    bool raw;                 /* the operation is "raw" */
    uint16_t transaction;     /* TCP: the transaction identifier of the request */
    bool seen;                /* a response frame was handed to onRaw() */
    uint8_t function;         /* its function code */
    std::vector<uint8_t> pdu; /* raw: the response PDU */
} tx;

static bool on_result(Modbus::ResultCode event, uint16_t, void *)
{
    tx.done = true;
    tx.result = event;
    return true;
}

/* Every frame the library receives, before it processes it. */
static Modbus::ResultCode on_raw(uint8_t *data, uint8_t length, void *custom)
{
    const Modbus::frame_arg_t *arg = (const Modbus::frame_arg_t *) custom;

    if (arg->to_server || length < 1)
        return Modbus::EX_PASSTHROUGH;
    tx.seen = true;
    tx.function = data[0];
    if (!tx.raw)
        return Modbus::EX_PASSTHROUGH;
    tx.pdu.assign(data, data + length);
    /* Anything but EX_PASSTHROUGH: the library does not process the frame
       further (its typed decoding does not apply to a raw request) and
       reports this code to the transaction callback. */
    return Modbus::EX_SUCCESS;
}

static void hex_of(const uint8_t *data, size_t length, char *out)
{
    for (size_t i = 0; i < length; i++)
        snprintf(out + 2 * i, 3, "%02x", data[i]);
    out[2 * length] = '\0';
}

static cJSON *response_object(int function)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddNumberToObject(o, "function", function);
    return o;
}

/* The buffers the library writes a response into. */
static struct {
    bool bits[2000];
    uint16_t registers[125];
    uint8_t file[2 * 121 + 16]; /* the library copies one byte more than the records (see README) */
    bool coils[MAX_VALUES];
    uint8_t file_data[2 * 122];
} buf;

/* Sends the request; returns what the library's call returned (0: the
   library did not send it). ID is an IPAddress (TCP) or a slave id (RTU). */
template <class M, class ID> static uint16_t issue(M &mb, ID id, const request_t *rq)
{
    const uint8_t unit = (uint8_t) rq->unit_id;
    const uint16_t address = (uint16_t) rq->address;
    const uint16_t quantity = (uint16_t) rq->quantity;
    uint8_t pdu[MAX_RAW_DATA + 1];

    switch (rq->op->id) {
    case OP_READ_COILS:
        return mb.readCoil(id, address, buf.bits, quantity, on_result, unit);
    case OP_READ_DISCRETE_INPUTS:
        return mb.readIsts(id, address, buf.bits, quantity, on_result, unit);
    case OP_READ_HOLDING_REGISTERS:
        return mb.readHreg(id, address, buf.registers, quantity, on_result, unit);
    case OP_READ_INPUT_REGISTERS:
        return mb.readIreg(id, address, buf.registers, quantity, on_result, unit);
    case OP_WRITE_SINGLE_COIL:
        return mb.writeCoil(id, address, rq->value != 0, on_result, unit);
    case OP_WRITE_SINGLE_REGISTER:
        return mb.writeHreg(id, address, (uint16_t) rq->value, on_result, unit);
    case OP_WRITE_MULTIPLE_COILS:
        for (int i = 0; i < rq->value_count; i++)
            buf.coils[i] = rq->values[i] != 0;
        return mb.writeCoil(id, address, buf.coils, (uint16_t) rq->value_count, on_result, unit);
    case OP_WRITE_MULTIPLE_REGISTERS:
        return mb.writeHreg(id, address, (uint16_t *) rq->values, (uint16_t) rq->value_count, on_result, unit);
    case OP_MASK_WRITE_REGISTER:
        return mb.maskHreg(id, address, (uint16_t) rq->and_mask, (uint16_t) rq->or_mask, on_result, unit);
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        return mb.readWriteHreg(id, address, buf.registers, quantity, (uint16_t) rq->write_address,
                                (uint16_t *) rq->values, (uint16_t) rq->value_count, on_result, unit);
    case OP_READ_FILE_RECORD:
        return mb.readFileRec(id, (uint16_t) rq->file, address, quantity, buf.file, on_result, unit);
    case OP_WRITE_FILE_RECORD:
        /* The library copies the record data as it is: big-endian bytes. */
        for (int i = 0; i < rq->value_count; i++) {
            buf.file_data[2 * i] = (uint8_t) (rq->values[i] >> 8);
            buf.file_data[2 * i + 1] = (uint8_t) rq->values[i];
        }
        return mb.writeFileRec(id, (uint16_t) rq->file, address, (uint16_t) rq->value_count, buf.file_data,
                               on_result, unit);
    case OP_RAW:
        pdu[0] = (uint8_t) rq->function;
        memcpy(pdu + 1, rq->data, (size_t) rq->data_length);
        return mb.rawRequest(id, pdu, (uint16_t) (rq->data_length + 1), on_result, unit);
    }
    return 0;
}

/* The response document of a normal response. */
static cJSON *describe(const request_t *rq)
{
    const int function = rq->op->function;
    cJSON *o, *array;
    char hex[2 * 256 + 1];

    if (rq->op->id == OP_RAW) {
        o = response_object((int) rq->function);
        hex_of(tx.pdu.data() + 1, tx.pdu.size() - 1, hex);
        cJSON_AddStringToObject(o, "data", hex);
        return o;
    }
    o = response_object(function);
    switch (rq->op->id) {
    case OP_READ_COILS:
    case OP_READ_DISCRETE_INPUTS:
        array = cJSON_AddArrayToObject(o, "bits");
        for (long i = 0; i < rq->quantity; i++)
            cJSON_AddItemToArray(array, cJSON_CreateBool(buf.bits[i]));
        break;
    case OP_READ_HOLDING_REGISTERS:
    case OP_READ_INPUT_REGISTERS:
    case OP_READ_WRITE_MULTIPLE_REGISTERS:
        array = cJSON_AddArrayToObject(o, "registers");
        for (long i = 0; i < rq->quantity; i++)
            cJSON_AddItemToArray(array, cJSON_CreateNumber(buf.registers[i]));
        break;
    case OP_READ_FILE_RECORD:
        array = cJSON_AddArrayToObject(o, "registers");
        for (long i = 0; i < rq->quantity; i++)
            cJSON_AddItemToArray(array, cJSON_CreateNumber((buf.file[2 * i] << 8) | buf.file[2 * i + 1]));
        break;
    /* The library does not hand out the echo of a write (it checks only
       the function code of the response): the fields are those of the
       request. */
    case OP_WRITE_SINGLE_COIL:
        cJSON_AddNumberToObject(o, "address", (double) rq->address);
        cJSON_AddBoolToObject(o, "value", rq->value != 0);
        break;
    case OP_WRITE_SINGLE_REGISTER:
        cJSON_AddNumberToObject(o, "address", (double) rq->address);
        cJSON_AddNumberToObject(o, "value", (double) rq->value);
        break;
    case OP_WRITE_MULTIPLE_COILS:
    case OP_WRITE_MULTIPLE_REGISTERS:
        cJSON_AddNumberToObject(o, "address", (double) rq->address);
        cJSON_AddNumberToObject(o, "quantity", rq->value_count);
        break;
    case OP_MASK_WRITE_REGISTER:
        cJSON_AddNumberToObject(o, "address", (double) rq->address);
        cJSON_AddNumberToObject(o, "andMask", (double) rq->and_mask);
        cJSON_AddNumberToObject(o, "orMask", (double) rq->or_mask);
        break;
    case OP_WRITE_FILE_RECORD:
        cJSON_AddNumberToObject(o, "file", (double) rq->file);
        cJSON_AddNumberToObject(o, "record", (double) rq->address);
        cJSON_AddNumberToObject(o, "quantity", rq->value_count);
        break;
    case OP_RAW:
        break;
    }
    return o;
}

static void set_error(outcome_t *out, const char *code, const char *message)
{
    out->error = code;
    snprintf(out->message, sizeof(out->message), "%s", message);
}

/* Maps the result code of the transaction to the outcome. */
static void classify(const request_t *rq, outcome_t *out)
{
    const Modbus::ResultCode r = tx.result;
    const bool exception_response = tx.seen && (tx.function & 0x80) != 0;

    if (rq->op->id == OP_RAW && r == Modbus::EX_SUCCESS) {
        if (tx.pdu.size() >= 1 && tx.pdu[0] == (uint8_t) (rq->function | 0x80)) {
            if (tx.pdu.size() != 2 || tx.pdu[1] == 0) {
                set_error(out, "invalid-response", "exception response of the wrong length or with code 0");
                return;
            }
            out->error = "exception";
            out->exception_function = tx.pdu[0];
            out->exception_code = tx.pdu[1];
            snprintf(out->message, sizeof(out->message), "exception %d", tx.pdu[1]);
            return;
        }
        out->response = describe(rq);
        return;
    }
    if (exception_response) {
        if (r == Modbus::EX_SUCCESS || r == Modbus::EX_UNEXPECTED_RESPONSE) {
            set_error(out, "invalid-response", "exception response with code 0 or for another function");
            return;
        }
        out->error = "exception";
        out->exception_function = tx.function;
        out->exception_code = (int) r;
        snprintf(out->message, sizeof(out->message), "exception %d", (int) r);
        return;
    }
    switch (r) {
    case Modbus::EX_SUCCESS:
        out->response = describe(rq);
        return;
    case Modbus::EX_TIMEOUT:
        set_error(out, "timeout", "no response within the timeout (the library's EX_TIMEOUT)");
        return;
    case Modbus::EX_CONNECTION_LOST:
        set_error(out, "connection-lost", "the library reported EX_CONNECTION_LOST");
        return;
    case Modbus::EX_UNEXPECTED_RESPONSE:
        set_error(out, "invalid-response", "the function code of the response is not that of the request");
        return;
    case Modbus::EX_DATA_MISMACH:
        set_error(out, "invalid-response", "the length of the response does not match the request");
        return;
    case Modbus::EX_GENERAL_FAILURE:
        set_error(out, "invalid-response", "the library could not decode the response (EX_GENERAL_FAILURE)");
        return;
    default:
        if (tx.seen) {
            /* A normal response the library refused with one of its own
               checks (Modbus::masterPDU). */
            snprintf(out->message, sizeof(out->message), "the library refused the response (result 0x%02x)",
                     (unsigned) r);
            out->error = "invalid-response";
        } else {
            snprintf(out->message, sizeof(out->message), "result 0x%02x", (unsigned) r);
            out->error = "failed";
        }
        return;
    }
}

/* One request and its response: the application loop around task(). */
template <class M, class ID, class LOST>
static void exchange(M &mb, ID id, LOST lost, const request_t *rq, outcome_t *out)
{
    double started;

    memset(out, 0, sizeof(*out));
    tx.done = false;
    tx.seen = false;
    tx.function = 0;
    tx.pdu.clear();
    tx.raw = rq->op->id == OP_RAW;
    tx.transaction = issue(mb, id, rq);
    if (tx.transaction == 0) {
        set_error(out, lost() ? "connection-lost" : "failed", "the library did not send the request");
        return;
    }
    started = monotonic_ms();
    for (;;) {
        mb.task();
        if (tx.done)
            break;
        if (lost()) {
            /* The library keeps the transaction until its timeout. */
            mb.task();
            if (tx.done)
                break;
            set_error(out, "connection-lost", "the connection closed before the response");
            return;
        }
        /* The library's timeout (MODBUSIP_TIMEOUT/MODBUSRTU_TIMEOUT) is
           --timeout-ms; this bound is only a guard, e.g. for RTU broadcasts
           (unit 0), to which the library expects no answer. */
        if (monotonic_ms() - started > (double) rq->timeout_ms + 250.0) {
            set_error(out, "timeout", "no response within the timeout");
            return;
        }
        posix_net::wait_for_io(1);
    }
    classify(rq, out);
}

int client_main(int argc, char **argv)
{
    static request_t rq;
    outcome_t out;
    cJSON *doc, *response = nullptr;
    char *first = nullptr;
    char resolver[64];
    const char *error = nullptr;
    char message[200] = "";
    int connected = 0, requests = 0, responses = 0;
    int exception_function = 0, exception_code = 0;
    double started;
    IPAddress ip;
    int rc = parse_arguments(argc, argv, &rq);

    if (rc != 0)
        return rc;

    started = monotonic_ms();
    /* Bound the name lookup of the C library as well. */
    snprintf(resolver, sizeof(resolver), "timeout:%ld attempts:1", (rq.connect_timeout_ms + 999) / 1000);
    setenv("RES_OPTIONS", resolver, 0);
    modbusesp_timeout_ms = (uint32_t) rq.timeout_ms;
    posix_net::set_connect_timeout_ms((unsigned long) rq.connect_timeout_ms);
    signal(SIGPIPE, SIG_IGN);

    /* Static storage, as a sketch declares them: ModbusRTUTemplate leaves
       _slaveId uninitialised (ModbusRTU.h:25), and send() refuses to send
       while it is not 0 in client mode, so a ModbusRTU on the stack may
       never send anything. */
    static ModbusPosix tcp;
    static PosixClient socket;
    static ModbusRTU rtu;

    ip = posix_net::resolve(rq.host);
    if ((uint32_t) ip == INADDR_NONE) {
        error = "connect-failed";
        snprintf(message, sizeof(message), "%s does not resolve to an IPv4 address", rq.host);
    } else if (rq.transport == TRANSPORT_TCP) {
        tcp.client();
        tcp.onRaw(on_raw);
        if (!tcp.connect(ip, (uint16_t) rq.port)) {
            error = "connect-failed";
            snprintf(message, sizeof(message), "no connection to %s:%ld", rq.host, rq.port);
        } else {
            connected = 1;
        }
    } else {
        if (!socket.connect(ip, (uint16_t) rq.port)) {
            error = "connect-failed";
            snprintf(message, sizeof(message), "no connection to %s:%ld", rq.host, rq.port);
        } else {
            connected = 1;
            rtu.begin((Stream *) &socket);
            rtu.client();
            rtu.onRaw(on_raw);
        }
    }

    for (long i = 0; error == nullptr && i < rq.repeat; i++) {
        char *text;

        requests++;
        if (rq.transport == TRANSPORT_TCP)
            exchange(tcp, ip, [&]() { return !tcp.isConnected(ip); }, &rq, &out);
        else
            exchange(rtu, (uint8_t) rq.unit_id, [&]() { return !socket.connected(); }, &rq, &out);
        if (out.error != nullptr) {
            error = out.error;
            snprintf(message, sizeof(message), "%s", out.message);
            exception_function = out.exception_function;
            exception_code = out.exception_code;
            break;
        }
        responses++;
        text = cJSON_PrintUnformatted(out.response);
        if (first == nullptr) {
            first = text;
        } else {
            if (text == nullptr || strcmp(first, text) != 0) {
                error = "failed";
                snprintf(message, sizeof(message), "response %d differs from the first", responses);
            }
            free(text);
        }
        cJSON_Delete(response);
        response = out.response;
    }
    free(first);
    if (rq.transport == TRANSPORT_TCP)
        tcp.disconnect(ip);
    socket.stop();

    doc = cJSON_CreateObject();
    cJSON_AddStringToObject(doc, "schemaVersion", SCHEMA_VERSION);
    cJSON_AddStringToObject(doc, "adapter", ADAPTER_NAME);
    cJSON_AddStringToObject(doc, "operation", rq.op->name);
    cJSON_AddBoolToObject(doc, "ok", error == nullptr);
    if (error == nullptr) {
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
    if (response != nullptr)
        cJSON_AddItemToObject(doc, "response", response);
    else
        cJSON_AddNullToObject(doc, "response");
    if (error != nullptr && !strcmp(error, "exception")) {
        cJSON *x = cJSON_AddObjectToObject(doc, "exception");
        cJSON_AddNumberToObject(x, "function", exception_function);
        cJSON_AddNumberToObject(x, "code", exception_code);
    } else {
        cJSON_AddNullToObject(doc, "exception");
    }
    cJSON_AddNumberToObject(doc, "elapsedMs", round((monotonic_ms() - started) * 1000.0) / 1000.0);
    emit_json(doc);

    if (error == nullptr)
        return EXIT_OK;
    return strcmp(error, "connect-failed") == 0 ? EXIT_CONNECT : EXIT_FAILED;
}
