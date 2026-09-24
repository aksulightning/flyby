#include "terminal.h"
#include "vm.h"
#include <chrono>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <thread>
using Clock = std::chrono::steady_clock;
static void check(bool value, const char *message) {
    if (!value)
        throw std::runtime_error(message);
}
template <class F> void rejects(F &&fn) {
    bool failed = false;
    try {
        fn();
    } catch (const std::exception &) {
        failed = true;
    }
    check(failed, "Expected rejection");
}
static void terminalTests() {
    flyby::Terminal t;
    auto feed = [&](const std::string &s) {
        return t.feed(reinterpret_cast<const uint8_t *>(s.data()), s.size());
    };
    feed("Hello\x1b[2;3H\x1b[31mX");
    auto f = t.frame(0);
    check(f[2] == 1 && f[3] == 3, "ANSI cursor movement");
    check(f[6 + (1 * 80 + 2) * 10] == 'X', "ANSI cell placement");
    check(f[6 + (1 * 80 + 2) * 10 + 6] != f[6 + 6], "ANSI foreground color");
    check(t.key(5, 0) == std::vector<uint8_t>({'\x1b', '[', 'A'}), "Arrow key encoding");
    check(t.key(1, 0) == std::vector<uint8_t>({'\r'}), "Enter encoding");
    check(feed("\x1b[6n") == std::vector<uint8_t>({'\x1b', '[', '2', ';', '4', 'R'}),
          "Cursor position report");
    check(!feed("").size(), "Responses consumed");
    for (int i = 0; i < 50; i++)
        feed("\r\nline");
    check(t.frame(0)[4] > 0, "Scrollback retains lines");
    t.resize(12, 40);
    f = t.frame(0);
    check(f[0] == 12 && f[1] == 40, "Resize dimensions");
    t.reset();
    feed("\xc3");
    feed("\xa4");
    check(t.frame(0)[6] == 0xe4, "Split UTF-8 decoding");
    rejects([&] { t.resize(0, 80); });
    std::cout << "PASS terminal ANSI, colors, cursor, keys, history, resize, UTF-8\n";
}
static std::string waitFor(flyby::Vm &vm, const std::string &pattern, int seconds) {
    std::string out;
    auto end = Clock::now() + std::chrono::seconds(seconds);
    while (Clock::now() < end) {
        auto b = vm.output(100);
        out.append(b.begin(), b.end());
        if (out.find(pattern) != std::string::npos)
            return out;
        if (!vm.running())
            break;
    }
    std::cerr << out;
    throw std::runtime_error("Timeout waiting for " + pattern);
}
int main(int argc, char **argv) {
    try {
        terminalTests();
        rejects([] { flyby::Vm vm("/missing", 0, 1); });
        rejects([] { flyby::Vm vm("/missing", 512, 2); });
        rejects([] { flyby::Vm vm("/missing", 512, 1); });
        if (argc != 2)
            throw std::runtime_error("Guest directory argument required");
        auto invalid = std::filesystem::path(argv[1]) / "invalid-test";
        std::filesystem::create_directories(invalid);
        {
            std::ofstream file(invalid / "kernel");
            file << "not a RISC-V Image";
        }
        rejects([&] { flyby::Vm vm(invalid.string(), 512, 1); });
        std::filesystem::remove_all(invalid);
        flyby::Vm vm(argv[1], 512, 1);
        vm.start();
        rejects([&] { vm.start(); });
        waitFor(vm, "FLYBY_ALPINE_READY", 180);
        vm.pause();
        check(!vm.running(), "Pause did not stop execution");
        vm.resume();
        vm.resize(37, 101);
        std::string command = "for i in $(seq 1 50); do [ \"$(stty size 2>/dev/null)\" = \"37 101\" ] && "
                              "break; sleep .1; done; stty size; echo FLYBY_NATIVE_IO\n";
        vm.input(reinterpret_cast<const uint8_t *>(command.data()), command.size());
        auto response = waitFor(vm, "\r\nFLYBY_NATIVE_IO\r\n", 15);
        check(response.find("37 101") != std::string::npos, "Control UART resize failed");
        auto cpuStart = std::clock();
        auto wallStart = Clock::now();
        std::this_thread::sleep_for(std::chrono::seconds(2));
        auto cpuSeconds = double(std::clock() - cpuStart) / CLOCKS_PER_SEC;
        auto wallSeconds = std::chrono::duration<double>(Clock::now() - wallStart).count();
        std::cout << "Idle host CPU ratio: " << cpuSeconds / wallSeconds << '\n';
        check(cpuSeconds / wallSeconds < 0.8, "Guest WFI is busy-looping");
        std::string sleepCommand = "echo FLYBY_SLEEPING; sleep 20\n";
        vm.input(reinterpret_cast<const uint8_t *>(sleepCommand.data()), sleepCommand.size());
        waitFor(vm, "\r\nFLYBY_SLEEPING\r\n", 10);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        const uint8_t interrupt = 3;
        vm.input(&interrupt, 1);
        waitFor(vm, "root@flyby:", 10);
        std::string after = "echo FLYBY_CTRL_C_OK\n";
        vm.input(reinterpret_cast<const uint8_t *>(after.data()), after.size());
        waitFor(vm, "\r\nFLYBY_CTRL_C_OK\r\n", 10);
        vm.requestStop();
        auto end = Clock::now() + std::chrono::seconds(15);
        while (vm.running() && Clock::now() < end)
            vm.output(100);
        check(!vm.running(), "Graceful shutdown through control UART failed");
        vm.stop();
        vm.stop();
        rejects([&] { vm.start(); });
        {
            flyby::Vm again(argv[1], 256, 1);
            again.start();
            again.stop();
            check(!again.running(), "Immediate stop failed");
        }
        std::cout << "PASS native initialization, validation, duplicate start, pause/resume, serial IO, "
                     "resize, graceful stop, restart\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << "FAIL " << e.what() << '\n';
        return 1;
    }
}
