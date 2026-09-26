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
int main(int argc, char **argv) {
    if (argc != 4) return 2;
    try {
        flyby::Vm vm(argv[1], 512, 1, argv[2], true);
        vm.start();
        wait(vm, "FLYBY_ALPINE_READY");
        shell(vm, "stty -echo\n");
        shell(vm, "i=0; until rc-service flyby-wayland-terminal status >/dev/null 2>&1; do i=$((i+1)); [ $i -lt 60 ] || break; sleep 1; done\n");
        shell(vm, "cat /var/log/weston.log; test -S /run/flyby-wayland/wayland-0 && rc-service flyby-wayland status && rc-service flyby-wayland-terminal status && echo WAYLAND_READY_FOR_INPUT\n");
        wait(vm, "\r\nWAYLAND_READY_FOR_INPUT\r\n");
        std::this_thread::sleep_for(3s);
        auto pixels = vm.displayFrame();
        if (pixels.size() != 800 * 600 || std::set<int32_t>(pixels.begin(), pixels.end()).size() < 16)
            throw std::runtime_error("Desktop framebuffer is missing or blank");
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
    } catch (const std::exception &e) { std::cerr << e.what() << '\n'; return 1; }
}
