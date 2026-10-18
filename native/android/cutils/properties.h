#pragma once
// The embedded Mesa utility expects the platform property_get wrapper. Use
// the NDK's read-only property API; no dependency on private libcutils.
#include <sys/system_properties.h>
#include <string.h>
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX
#define PROPERTY_KEY_MAX 32
static inline int property_get(const char* name, char* value, const char* fallback) {
    int size = __system_property_get(name, value);
    if (!size && fallback) {
        size = (int)strnlen(fallback, PROPERTY_VALUE_MAX - 1);
        memcpy(value, fallback, size); value[size] = 0;
    }
    return size;
}
