#include "vm.h"
#include <array>
#include <chrono>
#include <fstream>
#include <iostream>
#include <set>
#include <stdexcept>
#include <thread>
using namespace std::chrono_literals;
static void shell(flyby::Vm &vm, const std::string &s) {
    vm.input(reinterpret_cast<const uint8_t *>(s.data()), s.size());
}
static std::string wait(flyby::Vm &vm, const std::string &marker, int seconds = 120) {
    std::string output;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (std::chrono::steady_clock::now() < deadline && vm.running()) {
        auto b = vm.output(50);
        std::cout.write(reinterpret_cast<const char *>(b.data()), b.size()).flush();
        output.append(b.begin(), b.end());
        if (output.find(marker) != std::string::npos) return output;
        if (output.size() > 65536) output.erase(0, output.size() - 65536);
    }
    throw std::runtime_error("Timed out waiting for " + marker);
}
static void key(flyby::Vm &vm, unsigned code, unsigned mods = 0) {
    std::array<uint8_t,16> p{'F','I',1,1};
    p[4] = mods; p[6] = code; vm.displayInput(p.data(), p.size());
    std::this_thread::sleep_for(25ms);
    p[4] = 0; p[6] = 0; vm.displayInput(p.data(), p.size());
    std::this_thread::sleep_for(25ms);
}
static void pointer(flyby::Vm &vm, unsigned x, unsigned y, unsigned buttons) {
    std::array<uint8_t,16> p{'F','I',1,2};
    x = x * 32767 / 799; y = y * 32767 / 599;
    p[4] = buttons; p[5] = x; p[6] = x >> 8; p[7] = y; p[8] = y >> 8;
    vm.displayInput(p.data(), p.size());
}
static std::vector<int32_t> waitForDesktop(flyby::Vm &vm) {
    // OpenRC's "started" and the Wayland socket precede the first rendered
    // client frame. Font discovery and software rendering are slow on RV64.
    const auto deadline = std::chrono::steady_clock::now() + 60s;
    size_t size = 0, colors = 0;
    while (vm.running() && std::chrono::steady_clock::now() < deadline) {
        auto pixels = vm.displayFrame();
        size = pixels.size();
        colors = std::set<int32_t>(pixels.begin(), pixels.end()).size();
        if (size == 800 * 600 && colors > 16) return pixels;
        auto output = vm.output(100);
        std::cout.write(reinterpret_cast<const char *>(output.data()), output.size()).flush();
        std::this_thread::sleep_for(100ms);
    }
    throw std::runtime_error("Desktop did not render: pixels=" + std::to_string(size) +
                             ", colors=" + std::to_string(colors));
}
static void diagnostics(flyby::Vm &vm) {
    if (!vm.running()) return;
    shell(vm, "\003\n");
    std::this_thread::sleep_for(100ms);
    shell(vm, "stty -echo; rc-status -a; cat /var/log/weston*.log; cat /proc/bus/input/devices; ls -l /dev/dri /dev/input; free; echo WAYLAND_DIAGNOSTICS_DONE\n");
    try { wait(vm, "\r\nWAYLAND_DIAGNOSTICS_DONE\r\n", 10); }
    catch (const std::exception &e) { std::cerr << "Diagnostics: " << e.what() << '\n'; }
}
int main(int argc, char **argv) {
    if (argc != 4) return 2;
    try {
        flyby::Vm vm(argv[1], 512, 1, argv[2], true);
        vm.start();
        try {
            wait(vm, "FLYBY_ALPINE_READY");
            shell(vm, "stty -echo\n");
            // Do not let early terminal echo satisfy a later output assertion.
            shell(vm, "printf '\\nWAYLAND_SERIAL_READY\\n'\n");
            wait(vm, "\r\nWAYLAND_SERIAL_READY\r\n");
            shell(vm, "i=0; until test -s /run/flyby-wayland/terminal-ready; do i=$((i+1)); [ $i -lt 90 ] || break; sleep 1; done\n");
            shell(vm, "cat /var/log/weston.log; test -S /run/flyby-wayland/wayland-0 && kill -0 $(cat /run/flyby-wayland/terminal-ready) && rc-service flyby-wayland-terminal status && echo WAYLAND_READY_FOR_INPUT\n");
            wait(vm, "\r\nWAYLAND_READY_FOR_INPUT\r\n");
            auto pixels = waitForDesktop(vm);
            shell(vm, "test \"$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY\" = /run/flyby-wayland/wayland-0 && printf '\\nWAYLAND_ENV_OK\\n'\n");
            wait(vm, "\r\nWAYLAND_ENV_OK\r\n", 10);
            std::ofstream image(argv[3], std::ios::binary);
            image << "P6\n800 600\n255\n";
            for (auto p : pixels) { char rgb[]{char(p >> 16), char(p >> 8), char(p)}; image.write(rgb, 3); }
            image.close();
            // The auto-started terminal owns keyboard focus. Type through UART ->
            // UHID -> evdev -> libinput -> Weston, never through the serial shell.
            for (char c : std::string("echo gui > /root/flyby-gui-test\n")) {
                if (c >= 'a' && c <= 'z') key(vm, 4 + c - 'a');
                else if (c == ' ') key(vm, 44);
                else if (c == '>') key(vm, 55, 2);
                else if (c == '/') key(vm, 56);
                else if (c == '-') key(vm, 45);
                else if (c == '\n') key(vm, 40);
                else throw std::runtime_error("Unknown test character");
            }
            shell(vm, "i=0; until grep -qx gui /root/flyby-gui-test 2>/dev/null; do i=$((i+1)); [ $i -lt 30 ] || break; sleep 1; done; grep -qx gui /root/flyby-gui-test && echo WAYLAND_KEYBOARD_OK\n");
            wait(vm, "WAYLAND_KEYBOARD_OK\r\n", 40);
            shell(vm, "dd if=/dev/input/event1 of=/tmp/pointer-event bs=24 count=1 2>/dev/null &\n");
            std::this_thread::sleep_for(500ms);
            pointer(vm, 350, 280, 0); pointer(vm, 350, 280, 1); pointer(vm, 350, 280, 0);
            shell(vm, "sleep 1; test -s /tmp/pointer-event && ! apk info -e xwayland && ! apk info -e xorg-server && echo WAYLAND_POINTER_OK\n");
            wait(vm, "WAYLAND_POINTER_OK\r\n");
            vm.requestStop();
            const auto deadline = std::chrono::steady_clock::now() + 30s;
            while (vm.running() && std::chrono::steady_clock::now() < deadline) vm.output(50);
            if (vm.running()) throw std::runtime_error("Wayland shutdown timed out");
            vm.stop();
            if (!vm.displayFrame().empty()) throw std::runtime_error("Stale frame after Stop");
            std::cout << "PASS: Wayland 800x600 framebuffer, keyboard, pointer and graceful shutdown\n";
        } catch (...) { diagnostics(vm); throw; }
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
