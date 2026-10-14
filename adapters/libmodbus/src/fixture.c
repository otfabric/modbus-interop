/* SPDX-License-Identifier: MIT */
/*
 * Fixture loading. The checks are those of
 * fixtures/schema/modbus-fixture.schema.json and of the cross-field rules in
 * scripts/validate-fixtures.py; a fixture that fails one is not served.
 */
#include "adapter.h"

#include <errno.h>
#include <libgen.h>
#include <limits.h>
#include <math.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_FIXTURE_BYTES (16 * 1024 * 1024)

static int fail(char *err, size_t size, const char *format, ...)
{
    va_list ap;

    va_start(ap, format);
    vsnprintf(err, size, format, ap);
    va_end(ap);
    return -1;
}

static char *read_file(const char *path, char *err, size_t size)
{
    FILE *f = fopen(path, "rb");
    char *text = NULL;
    size_t used = 0, capacity = 0;

    if (f == NULL) {
        fail(err, size, "%s: %s", path, strerror(errno));
        return NULL;
    }
    for (;;) {
        size_t n;

        if (used + 65536 + 1 > capacity) {
            char *grown;

            capacity = capacity ? capacity * 2 : 131072;
            if (capacity > MAX_FIXTURE_BYTES || (grown = realloc(text, capacity)) == NULL) {
                fail(err, size, "%s: too large", path);
                free(text);
                fclose(f);
                return NULL;
            }
            text = grown;
        }
        n = fread(text + used, 1, 65536, f);
        used += n;
        if (n < 65536)
            break;
    }
    if (ferror(f)) {
        fail(err, size, "%s: read error", path);
        free(text);
        fclose(f);
        return NULL;
    }
    fclose(f);
    if (memchr(text, '\0', used) != NULL) {
        fail(err, size, "%s: not JSON (contains a NUL byte)", path);
        free(text);
        return NULL;
    }
    text[used] = '\0';
    return text;
}

/* A JSON number that is an integer in min..max. */
static int integer_in(const cJSON *item, long min, long max, long *out)
{
    double v;

    if (!cJSON_IsNumber(item))
        return -1;
    v = item->valuedouble;
    if (!isfinite(v) || v != floor(v) || v < (double) min || v > (double) max)
        return -1;
    *out = (long) v;
    return 0;
}

static int only_keys(const cJSON *object, const char *const *allowed, const char *where,
                     char *err, size_t size)
{
    const cJSON *child;

    cJSON_ArrayForEach(child, object)
    {
        int known = 0;

        for (const char *const *k = allowed; *k != NULL; k++) {
            if (!strcmp(child->string, *k))
                known = 1;
        }
        if (!known)
            return fail(err, size, "%s: unknown property \"%s\"", where, child->string);
    }
    return 0;
}

static int load_block(const cJSON *root, const char *table, int bits, block_t *block,
                      char *err, size_t size)
{
    static const char *const keys[] = {"address", "values", NULL};
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(root, table);
    const cJSON *values, *value;
    long address;
    int count, i = 0;

    block->address = 0;
    block->count = 0;
    block->values = NULL;
    if (item == NULL)
        return 0;
    if (!cJSON_IsObject(item))
        return fail(err, size, "%s: must be an object", table);
    if (only_keys(item, keys, table, err, size) != 0)
        return -1;
    if (integer_in(cJSON_GetObjectItemCaseSensitive(item, "address"), 0, 65535, &address) != 0)
        return fail(err, size, "%s: address must be an integer 0..65535", table);
    values = cJSON_GetObjectItemCaseSensitive(item, "values");
    if (!cJSON_IsArray(values))
        return fail(err, size, "%s: values must be an array", table);
    count = cJSON_GetArraySize(values);
    if (count < 1 || count > 65536)
        return fail(err, size, "%s: values must have 1..65536 elements", table);
    if (address + count - 1 > 65535)
        return fail(err, size, "%s: block ends at %ld, past address 65535", table,
                    address + count - 1);
    block->values = calloc((size_t) count, sizeof(uint16_t));
    if (block->values == NULL)
        return fail(err, size, "out of memory");
    cJSON_ArrayForEach(value, values)
    {
        if (bits) {
            if (!cJSON_IsBool(value))
                return fail(err, size, "%s: values[%d] must be a boolean", table, i);
            block->values[i] = cJSON_IsTrue(value) ? 1 : 0;
        } else {
            long v;

            if (integer_in(value, 0, 65535, &v) != 0)
                return fail(err, size, "%s: values[%d] must be an integer 0..65535", table, i);
            block->values[i] = (uint16_t) v;
        }
        i++;
    }
    block->address = (int) address;
    block->count = count;
    return 0;
}

/* The server does not implement Read Device Identification, but a fixture
   whose objects are invalid is still an invalid fixture. */
static int check_identification(const cJSON *root, char *err, size_t size)
{
    static const char *const keys[] = {"objects", NULL};
    static const char *const object_keys[] = {"id", "value", NULL};
    const cJSON *ident = cJSON_GetObjectItemCaseSensitive(root, "deviceIdentification");
    const cJSON *objects, *object;
    long previous = -1;
    int seen_basic = 0;

    if (ident == NULL)
        return 0;
    if (!cJSON_IsObject(ident))
        return fail(err, size, "deviceIdentification: must be an object");
    if (only_keys(ident, keys, "deviceIdentification", err, size) != 0)
        return -1;
    objects = cJSON_GetObjectItemCaseSensitive(ident, "objects");
    if (!cJSON_IsArray(objects) || cJSON_GetArraySize(objects) < 3)
        return fail(err, size, "deviceIdentification: objects must be an array of at least 3");
    cJSON_ArrayForEach(object, objects)
    {
        const cJSON *value;
        long id;
        size_t len;

        if (!cJSON_IsObject(object))
            return fail(err, size, "deviceIdentification: an object must be a JSON object");
        if (only_keys(object, object_keys, "deviceIdentification object", err, size) != 0)
            return -1;
        if (integer_in(cJSON_GetObjectItemCaseSensitive(object, "id"), 0, 255, &id) != 0)
            return fail(err, size, "deviceIdentification: id must be an integer 0..255");
        value = cJSON_GetObjectItemCaseSensitive(object, "value");
        if (!cJSON_IsString(value))
            return fail(err, size, "deviceIdentification: object %ld: value must be a string", id);
        len = strlen(value->valuestring);
        if (len < 1 || len > 240)
            return fail(err, size, "deviceIdentification: object %ld: value must have 1..240 characters", id);
        for (size_t i = 0; i < len; i++) {
            unsigned char c = (unsigned char) value->valuestring[i];

            if (c < 0x20 || c > 0x7e)
                return fail(err, size, "deviceIdentification: object %ld: value must be printable ASCII", id);
        }
        if (id <= previous)
            return fail(err, size, "deviceIdentification: object ids must be unique and ascending");
        if (id >= 7 && id <= 127)
            return fail(err, size, "deviceIdentification: object id %ld is reserved", id);
        if (id <= 2)
            seen_basic++;
        previous = id;
    }
    if (seen_basic != 3)
        return fail(err, size, "deviceIdentification: the basic objects 0, 1 and 2 are required");
    return 0;
}

/* The server answers FC17, FC20, FC21 and FC24 with exception 1 (see
   server.c), but a fixture whose reportServerId, fileRecords or fifoQueues
   are invalid is still an invalid fixture. */
static int check_report_server_id(const cJSON *root, char *err, size_t size)
{
    static const char *const keys[] = {"serverId", "runIndicator", NULL};
    const cJSON *item = cJSON_GetObjectItemCaseSensitive(root, "reportServerId");
    long id;

    if (item == NULL)
        return 0;
    if (!cJSON_IsObject(item))
        return fail(err, size, "reportServerId: must be an object");
    if (only_keys(item, keys, "reportServerId", err, size) != 0)
        return -1;
    if (integer_in(cJSON_GetObjectItemCaseSensitive(item, "serverId"), 0, 255, &id) != 0)
        return fail(err, size, "reportServerId: serverId must be an integer 0..255");
    if (!cJSON_IsBool(cJSON_GetObjectItemCaseSensitive(item, "runIndicator")))
        return fail(err, size, "reportServerId: runIndicator must be a boolean");
    return 0;
}

/* An array of at least one object {key: integer key_min..65535, list: array
   of list_min..list_max integers 0..65535}, the keys unique. */
static int check_keyed_lists(const cJSON *root, const char *table, const char *key, long key_min,
                             const char *list, int list_min, int list_max, char *err, size_t size)
{
    const char *const keys[] = {key, list, NULL};
    const cJSON *array = cJSON_GetObjectItemCaseSensitive(root, table);
    const cJSON *entry;
    int n = 0;

    if (array == NULL)
        return 0;
    if (!cJSON_IsArray(array))
        return fail(err, size, "%s: must be an array", table);
    if (cJSON_GetArraySize(array) < 1)
        return fail(err, size, "%s: must have at least one element", table);
    cJSON_ArrayForEach(entry, array)
    {
        const cJSON *values, *value;
        long k;
        int length, i = 0;

        if (!cJSON_IsObject(entry))
            return fail(err, size, "%s[%d]: must be an object", table, n);
        if (only_keys(entry, keys, table, err, size) != 0)
            return -1;
        if (integer_in(cJSON_GetObjectItemCaseSensitive(entry, key), key_min, 65535, &k) != 0)
            return fail(err, size, "%s[%d]: %s must be an integer %ld..65535", table, n, key, key_min);
        /* The entries before this one are valid: their keys are integers. */
        for (const cJSON *earlier = array->child; earlier != entry; earlier = earlier->next) {
            if ((long) cJSON_GetObjectItemCaseSensitive(earlier, key)->valuedouble == k)
                return fail(err, size, "%s: %s %ld is given twice", table, key, k);
        }
        values = cJSON_GetObjectItemCaseSensitive(entry, list);
        if (!cJSON_IsArray(values))
            return fail(err, size, "%s[%d]: %s must be an array", table, n, list);
        length = cJSON_GetArraySize(values);
        if (length < list_min || length > list_max)
            return fail(err, size, "%s[%d]: %s must have %d..%d elements", table, n, list, list_min, list_max);
        cJSON_ArrayForEach(value, values)
        {
            long v;

            if (integer_in(value, 0, 65535, &v) != 0)
                return fail(err, size, "%s[%d]: %s[%d] must be an integer 0..65535", table, n, list, i);
            i++;
        }
        n++;
    }
    return 0;
}

static int valid_name(const char *name)
{
    size_t len = strlen(name);

    if (len == 0 || name[0] == '-')
        return 0;
    return strspn(name, "abcdefghijklmnopqrstuvwxyz0123456789-") == len;
}

static int load_document(const cJSON *root, const char *directory, fixture_t *fx, char *err,
                         size_t size)
{
    static const char *const keys[] = {"schemaVersion", "name", "description", "unitId",
                                       "coils", "discreteInputs", "holdingRegisters",
                                       "inputRegisters", "deviceIdentification", "reportServerId",
                                       "fileRecords", "fifoQueues", NULL};
    const cJSON *item;
    long unit;

    if (!cJSON_IsObject(root))
        return fail(err, size, "the fixture must be a JSON object");
    if (only_keys(root, keys, "fixture", err, size) != 0)
        return -1;
    item = cJSON_GetObjectItemCaseSensitive(root, "schemaVersion");
    if (!cJSON_IsString(item) || strcmp(item->valuestring, SCHEMA_VERSION) != 0)
        return fail(err, size, "schemaVersion must be \"" SCHEMA_VERSION "\"");
    item = cJSON_GetObjectItemCaseSensitive(root, "name");
    if (!cJSON_IsString(item) || !valid_name(item->valuestring))
        return fail(err, size, "name must match ^[a-z0-9][a-z0-9-]*$");
    if (strlen(item->valuestring) >= sizeof(fx->name))
        return fail(err, size, "name is too long");
    strcpy(fx->name, item->valuestring);
    if (strcmp(fx->name, directory) != 0)
        return fail(err, size, "name \"%s\" is not the directory name \"%s\"", fx->name, directory);
    item = cJSON_GetObjectItemCaseSensitive(root, "description");
    if (item != NULL && !cJSON_IsString(item))
        return fail(err, size, "description must be a string");
    if (integer_in(cJSON_GetObjectItemCaseSensitive(root, "unitId"), 1, 247, &unit) != 0)
        return fail(err, size, "unitId must be an integer 1..247");
    fx->unit_id = (int) unit;

    if (load_block(root, "coils", 1, &fx->coils, err, size) != 0 ||
        load_block(root, "discreteInputs", 1, &fx->discrete_inputs, err, size) != 0 ||
        load_block(root, "holdingRegisters", 0, &fx->holding_registers, err, size) != 0 ||
        load_block(root, "inputRegisters", 0, &fx->input_registers, err, size) != 0)
        return -1;
    if (check_identification(root, err, size) != 0 || check_report_server_id(root, err, size) != 0 ||
        check_keyed_lists(root, "fileRecords", "file", 1, "records", 1, 10000, err, size) != 0 ||
        check_keyed_lists(root, "fifoQueues", "address", 0, "values", 0, 31, err, size) != 0)
        return -1;
    return 0;
}

int fixture_load(const char *path, fixture_t *fx, char *err, size_t err_size)
{
    char resolved[PATH_MAX];
    char *text, *directory;
    cJSON *root;
    int rc;

    memset(fx, 0, sizeof(*fx));
    text = read_file(path, err, err_size);
    if (text == NULL)
        return -1;
    /* The whole text must be one JSON value: no trailing garbage. */
    root = cJSON_ParseWithOpts(text, NULL, 1);
    free(text);
    if (root == NULL)
        return fail(err, err_size, "%s: not valid JSON", path);
    if (realpath(path, resolved) == NULL) {
        cJSON_Delete(root);
        return fail(err, err_size, "%s: %s", path, strerror(errno));
    }
    directory = basename(dirname(resolved));
    rc = load_document(root, directory, fx, err, err_size);
    cJSON_Delete(root);
    if (rc != 0)
        fixture_free(fx);
    return rc;
}

void fixture_free(fixture_t *fx)
{
    free(fx->coils.values);
    free(fx->discrete_inputs.values);
    free(fx->holding_registers.values);
    free(fx->input_registers.values);
    fx->coils.values = fx->discrete_inputs.values = NULL;
    fx->holding_registers.values = fx->input_registers.values = NULL;
}
