#include "vm.h"
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <poll.h>
#include <thread>
#include <unistd.h>
int main(int argc, char **argv) {
    if (argc < 2 || argc > 5 || (argc >= 4 && std::string(argv[3]) != "--system") ||
        (argc == 5 && std::string(argv[4]) != "--upgrade-edge")) {
        fprintf(stderr, "usage: flyby-host GUEST_DIRECTORY [DISK_RAW [--system [--upgrade-edge]]]\n");
        return 2;
    }
    try {
        unsigned ram = std::getenv("FLYBY_RAM_MIB") ? std::stoul(std::getenv("FLYBY_RAM_MIB")) : 512;
        flyby::Vm vm(argv[1], ram, 1, argc >= 3 ? argv[2] : "",
                     argc >= 4, false, argc == 5);
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
