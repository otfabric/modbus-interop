/* SPDX-License-Identifier: MIT */
#include "adapter.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

int parse_number(const char *text, long min, long max, long *out)
{
    const char *digits = text;
    const char *allowed = "0123456789";
    int base = 10;
    size_t len;
    long value;

    if (text == NULL)
        return -1;
    if (text[0] == '0' && (text[1] == 'x' || text[1] == 'X')) {
        digits = text + 2;
        allowed = "0123456789abcdefABCDEF";
        base = 16;
    }
    len = strlen(digits);
    /* Twelve digits are far beyond any value of the contract and far below
       what a long holds. */
    if (len == 0 || len > 12 || strspn(digits, allowed) != len)
        return -1;
    value = strtol(digits, NULL, base);
    if (value < min || value > max)
        return -1;
    *out = value;
    return 0;
}

int parse_bool(const char *text, int *out)
{
    if (text == NULL)
        return -1;
    if (!strcmp(text, "true") || !strcmp(text, "1") || !strcmp(text, "on")) {
        *out = 1;
        return 0;
    }
    if (!strcmp(text, "false") || !strcmp(text, "0") || !strcmp(text, "off")) {
        *out = 0;
        return 0;
    }
    return -1;
}

void emit_json(cJSON *object)
{
    char *text = cJSON_PrintUnformatted(object);

    if (text != NULL) {
        fputs(text, stdout);
        fputc('\n', stdout);
        fflush(stdout);
        free(text);
    } else {
        fprintf(stderr, "modbus-interop: out of memory while printing a document\n");
    }
    cJSON_Delete(object);
}

double monotonic_ms(void)
{
    struct timespec ts;

    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double) ts.tv_sec * 1000.0 + (double) ts.tv_nsec / 1e6;
}
