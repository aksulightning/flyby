// Descriptor-only resource access for the isolated Android runtime.
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
int flyby_open(const char *path, int flags, ...) {
    if (!strncmp(path, "flyby-fd:", 9)) {
        char *end;
        errno = 0;
        long value = strtol(path + 9, &end, 10);
        if (errno || end == path + 9 || *end || value < 0 || value > INT_MAX ||
            (flags & (O_CREAT | O_TRUNC))) { errno = EINVAL; return -1; }
        int actual = fcntl((int)value, F_GETFL);
        if (actual < 0) return -1;
        if ((flags & O_ACCMODE) != O_RDONLY && (actual & O_ACCMODE) == O_RDONLY) {
            errno = EACCES; return -1;
        }
        return fcntl((int)value, F_DUPFD_CLOEXEC, 0);
    }
    // Host tests retain ordinary resource paths. The Android sandbox never
    // receives paths, only descriptors explicitly opened by the owning app.
    mode_t mode = 0600;
    if (flags & O_CREAT) {
        va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args);
    }
    return open(path, flags, mode);
}
