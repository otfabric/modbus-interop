/* SPDX-License-Identifier: MIT */
/*
 * The device, served by the library.
 *
 * Modbus TCP: one ModbusPosix object in server mode; the application loop
 * calls its task(), as a sketch's loop() does. The fixture's tables are the
 * library's registers (addCoil, addIsts, addHreg, addIreg), so reading,
 * writing, the bit packing, FC22 and FC23 are the library's code
 * (Modbus::slavePDU). File records are served by the library's FC20/FC21
 * code through its onFile() callback, which reads and writes the fixture's
 * files.
 *
 * RTU on TCP: every accepted connection gets its own ModbusRTU object in
 * server mode, bound to the connection as its Stream (ModbusRTU::begin).
 * MODBUS_GLOBAL_REGS (the library default) makes all Modbus objects of the
 * process share one register set, so every connection sees the same device.
 *
 * In front of the library, through its public callbacks:
 *   onRaw()     sees every request frame first; it is used only to note the
 *               unit identifier of a Modbus TCP request, which the library
 *               does not check (ModbusTCPTemplate::task hands every frame to
 *               slavePDU whatever its unit identifier).
 *   onRequest() is called by slavePDU before it serves FC01-06, 15, 16, 22
 *               and 23, and may refuse with an exception code. It applies
 *               the device rules of docs/FIXTURES.md: another unit
 *               identifier (TCP): exception 11; a quantity of 0 or above the
 *               maximum: exception 3; an address outside the block:
 *               exception 2 (the library checks only the first address of a
 *               read, and answers a bad quantity with exception 2).
 *   onFile()    the storage of FC20/FC21, with the same rules.
 * Anything else (FC08, FC17, FC24, FC43 and every other function code) the
 * library answers with exception 1.
 */
#include "modbusesp.h"

#include <arpa/inet.h>
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include <memory>
#include <vector>

static volatile sig_atomic_t stop_requested;
static fixture_t fx;
static transport_t transport = TRANSPORT_TCP;
/* The unit identifier of the request being served (TCP). */
static int request_unit;
/* FC20: bytes of the response so far (see on_file). */
static unsigned file_response_bytes;

static void on_signal(int) { stop_requested = 1; }

static void event_simple(const char *event)
{
    cJSON *o = cJSON_CreateObject();
    cJSON_AddStringToObject(o, "event", event);
    emit_json(o);
}

static bool in_block(const block_t &b, unsigned address, unsigned quantity)
{
    return b.count > 0 && address >= (unsigned) b.address &&
           address + quantity <= (unsigned) b.address + (unsigned) b.count;
}

static Modbus::ResultCode on_raw(uint8_t *, uint8_t, void *custom)
{
    const Modbus::frame_arg_t *arg = (const Modbus::frame_arg_t *) custom;

    file_response_bytes = 2;
    /* ModbusRTU passes the RTU address (slaveId) and filters it itself. */
    request_unit = transport == TRANSPORT_TCP ? arg->unitId : fx.unit_id;
    return Modbus::EX_PASSTHROUGH;
}

static Modbus::ResultCode check(const block_t &b, unsigned address, unsigned quantity, unsigned max)
{
    if (quantity < 1 || quantity > max)
        return Modbus::EX_ILLEGAL_VALUE;
    if (!in_block(b, address, quantity))
        return Modbus::EX_ILLEGAL_ADDRESS;
    return Modbus::EX_SUCCESS;
}

static Modbus::ResultCode on_request(Modbus::FunctionCode fc, const Modbus::RequestData data)
{
    if (request_unit != fx.unit_id)
        return Modbus::EX_DEVICE_FAILED_TO_RESPOND;
    switch (fc) {
    case Modbus::FC_READ_COILS:
        return check(fx.coils, data.reg.address, data.regCount, 2000);
    case Modbus::FC_READ_INPUT_STAT:
        return check(fx.discrete_inputs, data.reg.address, data.regCount, 2000);
    case Modbus::FC_READ_REGS:
        return check(fx.holding_registers, data.reg.address, data.regCount, 125);
    case Modbus::FC_READ_INPUT_REGS:
        return check(fx.input_registers, data.reg.address, data.regCount, 125);
    case Modbus::FC_WRITE_COIL: /* regCount is the value; the library checks it */
        return check(fx.coils, data.reg.address, 1, 1);
    case Modbus::FC_WRITE_REG:
    case Modbus::FC_MASKWRITE_REG:
        return check(fx.holding_registers, data.reg.address, 1, 1);
    case Modbus::FC_WRITE_COILS:
        return check(fx.coils, data.reg.address, data.regCount, 1968);
    case Modbus::FC_WRITE_REGS:
        return check(fx.holding_registers, data.reg.address, data.regCount, 123);
    case Modbus::FC_READWRITE_REGS: {
        /* Both ranges before anything is written (the library writes first
           and checks the read range afterwards). */
        Modbus::ResultCode r;
        if (data.regReadCount < 1 || data.regReadCount > 125 || data.regWriteCount < 1 ||
            data.regWriteCount > 121)
            return Modbus::EX_ILLEGAL_VALUE;
        r = check(fx.holding_registers, data.regRead.address, data.regReadCount, 125);
        if (r == Modbus::EX_SUCCESS)
            r = check(fx.holding_registers, data.regWrite.address, data.regWriteCount, 121);
        return r;
    }
    default:
        return Modbus::EX_SUCCESS;
    }
}

/* FC20 and FC21, one call per sub-request, after the library has checked the
   reference type and the record number. frame is where the record data goes
   (FC20) or comes from (FC21), big-endian. */
static Modbus::ResultCode on_file(Modbus::FunctionCode fc, uint16_t number, uint16_t record, uint16_t length,
                                  uint8_t *frame)
{
    const file_t *file = nullptr;

    if (request_unit != fx.unit_id)
        return Modbus::EX_DEVICE_FAILED_TO_RESPOND;
    for (int i = 0; i < fx.file_count; i++) {
        if (fx.files[i].number == number)
            file = &fx.files[i];
    }
    if (file == nullptr || (unsigned) record + length > (unsigned) file->count)
        return Modbus::EX_ILLEGAL_ADDRESS;
    if (fc == Modbus::FC_READ_FILE_REC) {
        /* The library sizes the FC20 response in a uint8_t before it asks for
           the records (Modbus.cpp:303-316): when the response would be longer
           than 255 bytes that size wraps, and the buffer handed here is too
           small for the records asked for. Only such a request is refused,
           before anything is copied, with the exception the library's own
           (unreachable) size check intends for it (Modbus.cpp:318, exception
           2). Up to 255 bytes the library's behaviour is left as it is. */
        file_response_bytes += 2u + 2u * length;
        if (file_response_bytes > 255)
            return Modbus::EX_ILLEGAL_ADDRESS;
        for (unsigned i = 0; i < length; i++) {
            frame[2 * i] = (uint8_t) (file->records[record + i] >> 8);
            frame[2 * i + 1] = (uint8_t) file->records[record + i];
        }
    } else {
        for (unsigned i = 0; i < length; i++)
            file->records[record + i] = (uint16_t) ((frame[2 * i] << 8) | frame[2 * i + 1]);
    }
    return Modbus::EX_SUCCESS;
}

/* The fixture as the library's registers. */
template <class M> static void load(M &mb)
{
    for (int i = 0; i < fx.coils.count; i++)
        mb.addCoil((uint16_t) (fx.coils.address + i), fx.coils.values[i] != 0);
    for (int i = 0; i < fx.discrete_inputs.count; i++)
        mb.addIsts((uint16_t) (fx.discrete_inputs.address + i), fx.discrete_inputs.values[i] != 0);
    for (int i = 0; i < fx.holding_registers.count; i++)
        mb.addHreg((uint16_t) (fx.holding_registers.address + i), fx.holding_registers.values[i]);
    for (int i = 0; i < fx.input_registers.count; i++)
        mb.addIreg((uint16_t) (fx.input_registers.address + i), fx.input_registers.values[i]);
}

template <class M> static void hook(M &mb)
{
    mb.onRaw(on_raw);
    mb.onRequest(on_request);
    mb.onFile(on_file);
}

static int write_ready_file(const char *path)
{
    FILE *f = fopen(path, "w");
    if (f == nullptr)
        return -1;
    fputs("ready\n", f);
    return fclose(f);
}

static int usage(const char *message, const char *detail)
{
    fprintf(stderr, "modbus-interop server: %s%s\n", message, detail ? detail : "");
    fprintf(stderr, "usage: modbus-interop server [--fixture PATH] [--bind-address ADDR] "
                    "[--port N] [--ready-file PATH] [--transport tcp|rtu-over-tcp]\n");
    return EXIT_USAGE;
}

/* One RTU-over-TCP connection: a ModbusRTU server bound to the socket. */
struct RtuConnection {
    PosixClient socket;
    ModbusRTU rtu;
};

int server_main(int argc, char **argv)
{
    const char *fixture_path = DEFAULT_FIXTURE;
    const char *bind_address = "0.0.0.0";
    const char *ready_file = DEFAULT_READY_FILE;
    const char *transport_name = "tcp";
    long port = DEFAULT_PORT;
    struct in_addr parsed;
    char err[512];
    char address[96];
    struct sigaction sa;
    cJSON *ready;

    for (int i = 0; i < argc; i++) {
        const char *flag = argv[i];
        const char **target = nullptr;

        if (!strcmp(flag, "--fixture"))
            target = &fixture_path;
        else if (!strcmp(flag, "--bind-address"))
            target = &bind_address;
        else if (!strcmp(flag, "--ready-file"))
            target = &ready_file;
        else if (!strcmp(flag, "--transport"))
            target = &transport_name;
        else if (strcmp(flag, "--port") != 0)
            return usage("unknown flag ", flag);
        if (i + 1 >= argc)
            return usage("missing value of ", flag);
        i++;
        if (target != nullptr)
            *target = argv[i];
        else if (parse_number(argv[i], 1, 65535, &port) != 0)
            return usage("invalid --port ", argv[i]);
    }
    if (parse_transport(transport_name, &transport) != 0)
        return usage("--transport must be tcp or rtu-over-tcp (the library has no Modbus over UDP), not ",
                     transport_name);
    if (inet_pton(AF_INET, bind_address, &parsed) != 1)
        return usage("--bind-address must be an IPv4 address: ", bind_address);
    if (ready_file[0] == '\0')
        return usage("--ready-file must not be empty", nullptr);

    /* A ready file left by a process that was killed must not announce this one. */
    unlink(ready_file);
    if (fixture_load(fixture_path, &fx, err, sizeof(err)) != 0) {
        fprintf(stderr, "modbus-interop server: invalid fixture: %s\n", err);
        return EXIT_FIXTURE;
    }

    signal(SIGPIPE, SIG_IGN);
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGTERM, &sa, nullptr);
    sigaction(SIGINT, &sa, nullptr);

    posix_net::set_bind_address(bind_address);
    std::unique_ptr<ModbusPosix> tcp;
    std::unique_ptr<PosixServer> listener;
    std::vector<std::unique_ptr<RtuConnection>> connections;
    if (transport == TRANSPORT_TCP) {
        tcp.reset(new ModbusPosix());
        tcp->server((uint16_t) port);
        load(*tcp);
        hook(*tcp);
    } else {
        /* The registers are global: any Modbus object loads them. */
        ModbusRTU loader;
        load(loader);
        listener.reset(new PosixServer((uint16_t) port));
        listener->begin();
    }
    if (posix_net::last_begin_error() != 0) {
        fprintf(stderr, "modbus-interop server: cannot listen on %s:%ld: %s\n", bind_address, port,
                strerror(posix_net::last_begin_error()));
        return EXIT_FAILED;
    }

    if (write_ready_file(ready_file) != 0) {
        fprintf(stderr, "modbus-interop server: cannot write %s: %s\n", ready_file, strerror(errno));
        return EXIT_FAILED;
    }
    snprintf(address, sizeof(address), "%s:%ld", bind_address, port);
    ready = cJSON_CreateObject();
    cJSON_AddStringToObject(ready, "event", "ready");
    cJSON_AddStringToObject(ready, "adapter", ADAPTER_NAME);
    cJSON_AddStringToObject(ready, "address", address);
    cJSON_AddStringToObject(ready, "fixture", fx.name);
    cJSON_AddNumberToObject(ready, "unitId", fx.unit_id);
    emit_json(ready);

    while (!stop_requested) {
        if (tcp) {
            tcp->task();
        } else {
            for (PosixClient c = listener->accept(); c; c = listener->accept()) {
                std::unique_ptr<RtuConnection> rc(new RtuConnection());
                rc->socket = c;
                rc->rtu.begin((Stream *) &rc->socket);
                rc->rtu.server((uint8_t) fx.unit_id);
                hook(rc->rtu);
                connections.push_back(std::move(rc));
            }
            for (size_t i = 0; i < connections.size();) {
                connections[i]->rtu.task();
                if (!connections[i]->socket.connected()) {
                    connections[i]->socket.stop();
                    connections.erase(connections.begin() + (long) i);
                } else {
                    i++;
                }
            }
        }
        posix_net::wait_for_io(5);
    }

    unlink(ready_file);
    connections.clear();
    listener.reset();
    tcp.reset();
    fixture_free(&fx);
    event_simple("stopped");
    return EXIT_OK;
}
