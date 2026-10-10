/* SPDX-License-Identifier: MIT */
/* modbus-interop adapter for libmodbus: shared declarations. */
#ifndef ADAPTER_H
#define ADAPTER_H

#include <stddef.h>
#include <stdint.h>

#include <cjson/cJSON.h>

#define ADAPTER_NAME "libmodbus"
#define SCHEMA_VERSION "1.0"
#define DEFAULT_PORT 1502
#define FIXTURES_DIR "/fixtures"
#define DEFAULT_FIXTURE FIXTURES_DIR "/baseline/fixture.json"
#define DEFAULT_READY_FILE "/run/modbus-interop/ready"

/* Set by the build (see the Dockerfile). */
#ifndef ADAPTER_VERSION
#define ADAPTER_VERSION "dev"
#endif
#ifndef UPSTREAM_VERSION
#define UPSTREAM_VERSION "unknown"
#endif
#ifndef UPSTREAM_REVISION
#define UPSTREAM_REVISION "unknown"
#endif

/* Exit codes of docs/CONTAINER_CONTRACT.md. */
enum {
    EXIT_OK = 0,
    EXIT_FAILED = 1,
    EXIT_USAGE = 2,
    EXIT_CONNECT = 3,
    EXIT_FIXTURE = 4
};

/* One table of a fixture. count == 0: the device does not have the table. */
typedef struct {
    int address;
    int count;
    uint16_t *values; /* 0/1 for the bit tables */
} block_t;

typedef struct {
    char name[128];
    int unit_id;
    block_t coils;
    block_t discrete_inputs;
    block_t holding_registers;
    block_t input_registers;
} fixture_t;

/* fixture.c: loads and validates. Returns 0, or -1 with a message in err. */
int fixture_load(const char *path, fixture_t *fx, char *err, size_t err_size);
void fixture_free(fixture_t *fx);

/* util.c */
/* Decimal, or hexadecimal with a 0x prefix; nothing else. 0 on success. */
int parse_number(const char *text, long min, long max, long *out);
/* true/false, 1/0, on/off. 0 on success. */
int parse_bool(const char *text, int *out);
/* Prints the object on one line of stdout, flushes and deletes it. */
void emit_json(cJSON *object);
double monotonic_ms(void);

/* server.c, client.c: argv starts after the command. Return the exit code. */
int server_main(int argc, char **argv);
int client_main(int argc, char **argv);

#endif
