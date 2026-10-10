/* SPDX-License-Identifier: MIT */
/*
 * The device: a libmodbus TCP server over one modbus_mapping_t.
 *
 * libmodbus has no server object. An application owns the listening socket
 * and the sockets of its clients, calls modbus_receive() on the one that is
 * readable and hands the request to modbus_reply(), which answers it from the
 * mapping. This file is that application: one thread, one select() loop, one
 * modbus_t whose socket is switched with modbus_set_socket(), as in the
 * library's own multi-client example.
 *
 * modbus_reply() answers everything the fixture rules ask for, with three
 * exceptions that are decided here before it is called:
 *   - a request for another unit identifier gets exception 11
 *     (modbus_reply_exception), because libmodbus does not filter the unit
 *     identifier on TCP;
 *   - FC17 (Report Server ID) gets exception 1, because modbus_reply() would
 *     answer it with the library's own identification and the fixture rules
 *     limit the device to the functions of the contract;
 *   - a function code of 0 or above 127 is not answered: it is not a request,
 *     and modbus_reply() would answer it with a function code that wrapped.
 */
#include "adapter.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <unistd.h>

#include <modbus.h>

/* The contract asks for at least 16 connections. select() limits a process
   to FD_SETSIZE descriptors; 64 connections stay far below it. */
#define MAX_CONNECTIONS 64

typedef struct {
    int fd;
    char peer[80];
} connection_t;

static volatile sig_atomic_t stop_requested;

static void on_signal(int signo)
{
    (void) signo;
    stop_requested = 1;
}

static void event_simple(const char *event)
{
    cJSON *o = cJSON_CreateObject();

    cJSON_AddStringToObject(o, "event", event);
    emit_json(o);
}

static void event_connection(const char *event, const char *peer)
{
    cJSON *o = cJSON_CreateObject();

    cJSON_AddStringToObject(o, "event", event);
    cJSON_AddStringToObject(o, "peer", peer);
    emit_json(o);
}

static int in_block(int start, int count, int address, int quantity)
{
    return address >= start && address + quantity <= start + count;
}

static unsigned int be16(const uint8_t *p)
{
    return ((unsigned int) p[0] << 8) | p[1];
}

static cJSON *register_array(const uint8_t *data, int count)
{
    cJSON *a = cJSON_CreateArray();

    for (int i = 0; i < count; i++)
        cJSON_AddItemToArray(a, cJSON_CreateNumber(be16(data + 2 * i)));
    return a;
}

/*
 * Describes the request for the "request" event and works out which exception
 * modbus_reply() answers it with (0: a normal response). modbus_reply() does
 * not tell what it sent, only how many bytes; the caller compares that length
 * with this expectation, so the event never reports something the library did
 * not do. The rules are those of modbus_reply() in the pinned release: the
 * quantity first (exception 3), then the addresses against the mapping
 * (exception 2).
 *
 * pdu points at the function code; length is that of the PDU as
 * modbus_receive() framed it, which is complete for the functions it knows.
 */
static int describe(const modbus_mapping_t *map, const uint8_t *pdu, int length, cJSON *event)
{
    int function = pdu[0];
    int address, quantity;

    switch (function) {
    case MODBUS_FC_READ_COILS:
    case MODBUS_FC_READ_DISCRETE_INPUTS:
    case MODBUS_FC_READ_HOLDING_REGISTERS:
    case MODBUS_FC_READ_INPUT_REGISTERS: {
        int bits = function <= MODBUS_FC_READ_DISCRETE_INPUTS;
        int start, count;

        if (length < 5)
            return -1;
        address = (int) be16(pdu + 1);
        quantity = (int) be16(pdu + 3);
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", quantity);
        if (quantity < 1 || quantity > (bits ? MODBUS_MAX_READ_BITS : MODBUS_MAX_READ_REGISTERS))
            return MODBUS_EXCEPTION_ILLEGAL_DATA_VALUE;
        switch (function) {
        case MODBUS_FC_READ_COILS:
            start = map->start_bits, count = map->nb_bits;
            break;
        case MODBUS_FC_READ_DISCRETE_INPUTS:
            start = map->start_input_bits, count = map->nb_input_bits;
            break;
        case MODBUS_FC_READ_HOLDING_REGISTERS:
            start = map->start_registers, count = map->nb_registers;
            break;
        default:
            start = map->start_input_registers, count = map->nb_input_registers;
            break;
        }
        return in_block(start, count, address, quantity) ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
    }
    case MODBUS_FC_WRITE_SINGLE_COIL: {
        unsigned int value;

        if (length < 5)
            return -1;
        address = (int) be16(pdu + 1);
        value = be16(pdu + 3);
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", 1);
        if (value == 0xFF00 || value == 0x0000) {
            cJSON *bits = cJSON_AddArrayToObject(event, "bits");

            cJSON_AddItemToArray(bits, cJSON_CreateBool(value == 0xFF00));
        }
        if (!in_block(map->start_bits, map->nb_bits, address, 1))
            return MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
        return (value == 0xFF00 || value == 0x0000) ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_VALUE;
    }
    case MODBUS_FC_WRITE_SINGLE_REGISTER:
        if (length < 5)
            return -1;
        address = (int) be16(pdu + 1);
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", 1);
        cJSON_AddItemToObject(event, "registers", register_array(pdu + 3, 1));
        return in_block(map->start_registers, map->nb_registers, address, 1)
                   ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
    case MODBUS_FC_WRITE_MULTIPLE_COILS: {
        int byte_count;

        if (length < 6 || length < 6 + pdu[5])
            return -1;
        address = (int) be16(pdu + 1);
        quantity = (int) be16(pdu + 3);
        byte_count = pdu[5];
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", quantity);
        if (byte_count * 8 >= quantity) {
            cJSON *bits = cJSON_AddArrayToObject(event, "bits");

            for (int i = 0; i < quantity; i++)
                cJSON_AddItemToArray(bits, cJSON_CreateBool((pdu[6 + i / 8] >> (i % 8)) & 1));
        }
        if (quantity < 1 || quantity > MODBUS_MAX_WRITE_BITS || byte_count * 8 < quantity)
            return MODBUS_EXCEPTION_ILLEGAL_DATA_VALUE;
        return in_block(map->start_bits, map->nb_bits, address, quantity)
                   ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
    }
    case MODBUS_FC_WRITE_MULTIPLE_REGISTERS: {
        int byte_count;

        if (length < 6 || length < 6 + pdu[5])
            return -1;
        address = (int) be16(pdu + 1);
        quantity = (int) be16(pdu + 3);
        byte_count = pdu[5];
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", quantity);
        if (byte_count == quantity * 2)
            cJSON_AddItemToObject(event, "registers", register_array(pdu + 6, quantity));
        if (quantity < 1 || quantity > MODBUS_MAX_WRITE_REGISTERS || byte_count != quantity * 2)
            return MODBUS_EXCEPTION_ILLEGAL_DATA_VALUE;
        return in_block(map->start_registers, map->nb_registers, address, quantity)
                   ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
    }
    case MODBUS_FC_MASK_WRITE_REGISTER:
        if (length < 7)
            return -1;
        address = (int) be16(pdu + 1);
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", 1);
        cJSON_AddNumberToObject(event, "andMask", be16(pdu + 3));
        cJSON_AddNumberToObject(event, "orMask", be16(pdu + 5));
        return in_block(map->start_registers, map->nb_registers, address, 1)
                   ? 0 : MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
    case MODBUS_FC_WRITE_AND_READ_REGISTERS: {
        int write_address, write_quantity, byte_count;

        if (length < 10 || length < 10 + pdu[9])
            return -1;
        address = (int) be16(pdu + 1);
        quantity = (int) be16(pdu + 3);
        write_address = (int) be16(pdu + 5);
        write_quantity = (int) be16(pdu + 7);
        byte_count = pdu[9];
        cJSON_AddNumberToObject(event, "address", address);
        cJSON_AddNumberToObject(event, "quantity", quantity);
        cJSON_AddNumberToObject(event, "writeAddress", write_address);
        if (byte_count == write_quantity * 2)
            cJSON_AddItemToObject(event, "writeRegisters", register_array(pdu + 10, write_quantity));
        if (write_quantity < 1 || write_quantity > MODBUS_MAX_WR_WRITE_REGISTERS || quantity < 1 ||
            quantity > MODBUS_MAX_WR_READ_REGISTERS || byte_count != write_quantity * 2)
            return MODBUS_EXCEPTION_ILLEGAL_DATA_VALUE;
        if (!in_block(map->start_registers, map->nb_registers, address, quantity) ||
            !in_block(map->start_registers, map->nb_registers, write_address, write_quantity))
            return MODBUS_EXCEPTION_ILLEGAL_DATA_ADDRESS;
        return 0;
    }
    default:
        return MODBUS_EXCEPTION_ILLEGAL_FUNCTION;
    }
}

/* Answers one request and prints its event. Returns -1 when the connection
   can no longer be used. */
static int serve(modbus_t *ctx, modbus_mapping_t *map, int unit_id, const uint8_t *req, int length)
{
    const int header = modbus_get_header_length(ctx); /* 7: MBAP including the unit identifier */
    const int exception_length = header + 2;
    int unit, function, expected, sent;
    /* Bytes of the frame that libmodbus left on the socket: it derives the
       length of a request from the function code and ignores the MBAP length,
       so the data of a function it does not know is still unread. */
    int unread;
    cJSON *event;

    if (length < header + 1)
        return 0;
    unit = req[header - 1];
    function = req[header];
    unread = (int) be16(req + 4) > length - 6;

    if (function == 0 || function > 127) {
        fprintf(stderr, "modbus-interop: function code %d is not a request: not answered\n", function);
        if (unread)
            modbus_flush(ctx);
        return 0;
    }

    event = cJSON_CreateObject();
    cJSON_AddStringToObject(event, "event", "request");
    cJSON_AddNumberToObject(event, "unitId", unit);
    cJSON_AddNumberToObject(event, "function", function);
    expected = describe(map, req + header, length - header, event);
    if (expected < 0) {
        /* modbus_receive() hands out complete requests; this is unreachable
           unless that changes. */
        fprintf(stderr, "modbus-interop: short request for function %d: not answered\n", function);
        cJSON_Delete(event);
        return -1;
    }

    if (unit != unit_id) {
        expected = MODBUS_EXCEPTION_GATEWAY_TARGET;
        sent = modbus_reply_exception(ctx, req, MODBUS_EXCEPTION_GATEWAY_TARGET);
        if (sent != -1 && unread)
            modbus_flush(ctx);
    } else if (function == MODBUS_FC_REPORT_SLAVE_ID) {
        expected = MODBUS_EXCEPTION_ILLEGAL_FUNCTION;
        sent = modbus_reply_exception(ctx, req, MODBUS_EXCEPTION_ILLEGAL_FUNCTION);
        if (sent != -1 && unread)
            modbus_flush(ctx);
    } else {
        /* Everything else is the library's: the mapping, the checks and, for
           what it rejects, the exception (it also flushes the socket then). */
        sent = modbus_reply(ctx, req, length, map);
    }

    if (sent == -1) {
        fprintf(stderr, "modbus-interop: no response sent to function %d: %s\n", function,
                modbus_strerror(errno));
        cJSON_Delete(event);
        return -1;
    }
    if ((sent == exception_length) != (expected != 0)) {
        /* The library decided differently from what this file expects of it.
           What it sent is unknown here, so nothing is reported. */
        fprintf(stderr,
                "modbus-interop: function %d: libmodbus sent %d bytes where exception %d was "
                "expected; no request event\n", function, sent, expected);
        cJSON_Delete(event);
        return 0;
    }
    if (expected != 0)
        cJSON_AddNumberToObject(event, "exception", expected);
    else
        cJSON_AddNullToObject(event, "exception");
    emit_json(event);
    return 0;
}

static void peer_name(int fd, char *out, size_t size)
{
    struct sockaddr_storage ss;
    socklen_t len = sizeof(ss);
    char ip[INET6_ADDRSTRLEN] = "";

    snprintf(out, size, "unknown:0");
    if (getpeername(fd, (struct sockaddr *) &ss, &len) != 0)
        return;
    if (ss.ss_family == AF_INET) {
        const struct sockaddr_in *in = (const struct sockaddr_in *) &ss;

        inet_ntop(AF_INET, &in->sin_addr, ip, sizeof(ip));
        snprintf(out, size, "%s:%u", ip, (unsigned int) ntohs(in->sin_port));
    } else if (ss.ss_family == AF_INET6) {
        const struct sockaddr_in6 *in6 = (const struct sockaddr_in6 *) &ss;

        inet_ntop(AF_INET6, &in6->sin6_addr, ip, sizeof(ip));
        snprintf(out, size, "[%s]:%u", ip, (unsigned int) ntohs(in6->sin6_port));
    }
}

static modbus_mapping_t *mapping_of(const fixture_t *fx)
{
    modbus_mapping_t *map = modbus_mapping_new_start_address(
        (unsigned int) fx->coils.address, (unsigned int) fx->coils.count,
        (unsigned int) fx->discrete_inputs.address, (unsigned int) fx->discrete_inputs.count,
        (unsigned int) fx->holding_registers.address, (unsigned int) fx->holding_registers.count,
        (unsigned int) fx->input_registers.address, (unsigned int) fx->input_registers.count);

    if (map == NULL)
        return NULL;
    for (int i = 0; i < fx->coils.count; i++)
        map->tab_bits[i] = (uint8_t) fx->coils.values[i];
    for (int i = 0; i < fx->discrete_inputs.count; i++)
        map->tab_input_bits[i] = (uint8_t) fx->discrete_inputs.values[i];
    for (int i = 0; i < fx->holding_registers.count; i++)
        map->tab_registers[i] = fx->holding_registers.values[i];
    for (int i = 0; i < fx->input_registers.count; i++)
        map->tab_input_registers[i] = fx->input_registers.values[i];
    return map;
}

static int write_ready_file(const char *path)
{
    FILE *f = fopen(path, "w");

    if (f == NULL)
        return -1;
    fputs("ready\n", f);
    return fclose(f);
}

static int usage(const char *message, const char *detail)
{
    fprintf(stderr, "modbus-interop server: %s%s\n", message, detail ? detail : "");
    fprintf(stderr, "usage: modbus-interop server [--fixture PATH] [--bind-address ADDR] "
                    "[--port N] [--ready-file PATH]\n");
    return EXIT_USAGE;
}

int server_main(int argc, char **argv)
{
    const char *fixture_path = DEFAULT_FIXTURE;
    const char *bind_address = "0.0.0.0";
    const char *ready_file = DEFAULT_READY_FILE;
    long port = DEFAULT_PORT;
    struct in_addr parsed;
    fixture_t fx;
    char err[512];
    modbus_t *ctx;
    modbus_mapping_t *map;
    connection_t connections[MAX_CONNECTIONS];
    int connection_count = 0;
    int listener;
    struct sigaction sa;
    sigset_t blocked, unblocked;
    char address[96];
    cJSON *ready;

    for (int i = 0; i < argc; i++) {
        const char *flag = argv[i];
        const char **target = NULL;

        if (!strcmp(flag, "--fixture"))
            target = &fixture_path;
        else if (!strcmp(flag, "--bind-address"))
            target = &bind_address;
        else if (!strcmp(flag, "--ready-file"))
            target = &ready_file;
        else if (strcmp(flag, "--port") != 0)
            return usage("unknown flag ", flag);
        if (i + 1 >= argc)
            return usage("missing value of ", flag);
        i++;
        if (target != NULL)
            *target = argv[i];
        else if (parse_number(argv[i], 1, 65535, &port) != 0)
            return usage("invalid --port ", argv[i]);
    }
    /* modbus_new_tcp() and modbus_tcp_listen() are IPv4. */
    if (inet_pton(AF_INET, bind_address, &parsed) != 1)
        return usage("--bind-address must be an IPv4 address: ", bind_address);
    if (ready_file[0] == '\0')
        return usage("--ready-file must not be empty", NULL);

    /* A ready file left by a process that was killed must not announce this one. */
    unlink(ready_file);

    if (fixture_load(fixture_path, &fx, err, sizeof(err)) != 0) {
        fprintf(stderr, "modbus-interop server: invalid fixture: %s\n", err);
        return EXIT_FIXTURE;
    }
    map = mapping_of(&fx);
    if (map == NULL) {
        fprintf(stderr, "modbus-interop server: modbus_mapping_new_start_address: %s\n",
                modbus_strerror(errno));
        return EXIT_FAILED;
    }

    ctx = modbus_new_tcp(bind_address, (int) port);
    if (ctx == NULL) {
        fprintf(stderr, "modbus-interop server: modbus_new_tcp: %s\n", modbus_strerror(errno));
        return EXIT_FAILED;
    }
    /* modbus_reply() sleeps for the response timeout (500 ms by default)
       before it answers a request it rejects with exception 1 or 3, then
       discards what is left on the socket. In a one-thread server that sleep
       stalls every connection, so it is shortened. */
    modbus_set_response_timeout(ctx, 0, 20000);

    listener = modbus_tcp_listen(ctx, MAX_CONNECTIONS);
    if (listener == -1) {
        fprintf(stderr, "modbus-interop server: cannot listen on %s:%ld: %s\n", bind_address, port,
                modbus_strerror(errno));
        modbus_free(ctx);
        return EXIT_FAILED;
    }
    /* A client that gives up between select() and accept() must not block the loop. */
    fcntl(listener, F_SETFL, fcntl(listener, F_GETFL, 0) | O_NONBLOCK);

    /* SIGTERM and SIGINT are delivered only while the loop waits in pselect(). */
    signal(SIGPIPE, SIG_IGN);
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_signal;
    sigemptyset(&sa.sa_mask);
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGTERM);
    sigaddset(&blocked, SIGINT);
    sigprocmask(SIG_BLOCK, &blocked, &unblocked);
    sigdelset(&unblocked, SIGTERM);
    sigdelset(&unblocked, SIGINT);
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    if (write_ready_file(ready_file) != 0) {
        fprintf(stderr, "modbus-interop server: cannot write %s: %s\n", ready_file, strerror(errno));
        close(listener);
        modbus_free(ctx);
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
        fd_set readable;
        int highest = listener;
        int rc;

        FD_ZERO(&readable);
        FD_SET(listener, &readable);
        for (int i = 0; i < connection_count; i++) {
            FD_SET(connections[i].fd, &readable);
            if (connections[i].fd > highest)
                highest = connections[i].fd;
        }
        rc = pselect(highest + 1, &readable, NULL, NULL, NULL, &unblocked);
        if (rc == -1) {
            if (errno == EINTR)
                continue;
            fprintf(stderr, "modbus-interop server: select: %s\n", strerror(errno));
            break;
        }

        for (int i = 0; i < connection_count;) {
            uint8_t request[MODBUS_TCP_MAX_ADU_LENGTH];
            int length;

            if (!FD_ISSET(connections[i].fd, &readable)) {
                i++;
                continue;
            }
            modbus_set_socket(ctx, connections[i].fd);
            length = modbus_receive(ctx, request);
            if (length > 0 && serve(ctx, map, fx.unit_id, request, length) == 0) {
                i++;
                continue;
            }
            if (length == 0) { /* a request libmodbus ignored */
                i++;
                continue;
            }
            /* End of stream, an error, or a frame libmodbus could not read:
               the stream is not in step any more. */
            modbus_close(ctx);
            event_connection("connection-closed", connections[i].peer);
            connections[i] = connections[--connection_count];
        }

        if (FD_ISSET(listener, &readable)) {
            int fd = modbus_tcp_accept(ctx, &listener);

            if (fd == -1) {
                if (errno != EAGAIN && errno != EWOULDBLOCK && errno != ECONNABORTED && errno != EINTR)
                    fprintf(stderr, "modbus-interop server: accept: %s\n", strerror(errno));
            } else if (connection_count == MAX_CONNECTIONS) {
                fprintf(stderr, "modbus-interop server: more than %d connections: refused\n",
                        MAX_CONNECTIONS);
                modbus_close(ctx);
            } else {
                /* The listener is non-blocking; the connection must not be. */
                fcntl(fd, F_SETFL, fcntl(fd, F_GETFL, 0) & ~O_NONBLOCK);
                connections[connection_count].fd = fd;
                peer_name(fd, connections[connection_count].peer,
                          sizeof(connections[connection_count].peer));
                event_connection("connection-opened", connections[connection_count].peer);
                connection_count++;
            }
        }
    }

    unlink(ready_file);
    for (int i = 0; i < connection_count; i++) {
        modbus_set_socket(ctx, connections[i].fd);
        modbus_close(ctx);
        event_connection("connection-closed", connections[i].peer);
    }
    close(listener);
    modbus_mapping_free(map);
    modbus_free(ctx);
    fixture_free(&fx);
    event_simple("stopped");
    return EXIT_OK;
}
