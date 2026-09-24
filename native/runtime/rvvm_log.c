// Flyby Apache-2.0. Android adapter for RVVM's existing formatted diagnostic lines.
#include <android/log.h>
#include <stdio.h>
int flyby_rvvm_fputs(const char *message, FILE *stream) {
    (void)stream;
    __android_log_write(ANDROID_LOG_WARN, "FlybyRVVM", message);
    return 0;
}
