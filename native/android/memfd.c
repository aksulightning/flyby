#include <sys/syscall.h>
#include <unistd.h>
int flyby_memfd_create(const char *name, unsigned int flags) {
    return (int)syscall(__NR_memfd_create, name, flags);
}
