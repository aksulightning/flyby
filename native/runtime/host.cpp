#include "vm.h"
#include <atomic>
#include <cstdio>
#include <poll.h>
#include <thread>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: flyby-host GUEST_DIRECTORY\n");
        return 2;
    }
    try {
        flyby::Vm vm(argv[1], 512, 1);
        vm.start();
        std::atomic<bool> done{false};
        std::thread input([&] {
            uint8_t b[4096];
            while (!done) {
                pollfd p{STDIN_FILENO, POLLIN, 0};
                if (poll(&p, 1, 100) > 0) {
                    auto n = read(STDIN_FILENO, b, sizeof(b));
                    if (n <= 0)
                        break;
                    vm.input(b, n);
                }
            }
        });
        while (vm.running()) {
            auto b = vm.output(50);
            fwrite(b.data(), 1, b.size(), stdout);
            fflush(stdout);
        }
        done = true;
        input.join();
        auto b = vm.output(0);
        fwrite(b.data(), 1, b.size(), stdout);
        vm.stop();
        return 0;
    } catch (const std::exception &e) {
        fprintf(stderr, "NATIVE_ERROR %s\n", e.what());
        return 1;
    }
}
