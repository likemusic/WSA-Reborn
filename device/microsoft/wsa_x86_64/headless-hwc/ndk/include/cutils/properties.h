// NDK build of the module (ndk-build.sh): the three libcutils property helpers it uses, over bionic's
// __system_property_get (libcutils is not in the NDK and the vendor partition is frozen at VNDK v33 anyway).
#pragma once
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
static inline int property_get(const char* key, char* value, const char* def) {
    int n = __system_property_get(key, value);
    if (n > 0) return n;
    if (!def) { value[0] = 0; return 0; }
    strncpy(value, def, PROPERTY_VALUE_MAX - 1);
    value[PROPERTY_VALUE_MAX - 1] = 0;
    return (int)strlen(value);
}
static inline int32_t property_get_int32(const char* key, int32_t def) {
    char v[PROPERTY_VALUE_MAX];
    if (__system_property_get(key, v) <= 0) return def;
    char* end;
    long r = strtol(v, &end, 0);
    return end != v && !*end ? (int32_t)r : def;
}
static inline bool property_get_bool(const char* key, bool def) {
    char v[PROPERTY_VALUE_MAX];
    if (__system_property_get(key, v) <= 0) return def;
    if (!strcmp(v, "1") || !strcmp(v, "y") || !strcmp(v, "yes") || !strcmp(v, "on") || !strcmp(v, "true")) return true;
    if (!strcmp(v, "0") || !strcmp(v, "n") || !strcmp(v, "no") || !strcmp(v, "off") || !strcmp(v, "false")) return false;
    return def;
}
