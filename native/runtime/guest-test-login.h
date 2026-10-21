#pragma once
#include "vm.h"
#include <chrono>
#include <iostream>
#include <stdexcept>
#include <string>
// Test-only credentials are entered through the real first-run UI. They are
// never present in an APK resource or production disk seed.
inline void guestTestLogin(flyby::Vm &vm) {
    const std::string password = "Flyby-test-setup-42!\n";
    auto send = [&](const std::string &s) { vm.input(reinterpret_cast<const uint8_t*>(s.data()), s.size()); };
    std::string tail, all;
    bool first = false, second = false, elevating = false, authenticating = false;
    auto end = std::chrono::steady_clock::now() + std::chrono::seconds(180);
    while (vm.running() && std::chrono::steady_clock::now() < end) {
        auto bytes = vm.output(50);
        std::cout.write(reinterpret_cast<const char*>(bytes.data()), bytes.size()).flush();
        tail.append(bytes.begin(), bytes.end());
        all.append(bytes.begin(), bytes.end());
        if (!first && tail.find("New password:") != std::string::npos) {
            send(password); first = true; tail.clear();
        } else if (!second && tail.find("Retype password:") != std::string::npos) {
            send(password); second = true; tail.clear();
        } else if (!elevating && tail.find("FLYBY_ALPINE_READY") != std::string::npos) {
            send("[ \"$(id -u)\" = 1000 ] && [ \"$HOME\" = /home/flybyuser ] && ! test -w /etc/passwd && ! doas -n true && printf '\\nFLYBY_USER_OK\\n'\ndoas /bin/sh -l\n");
            elevating = true; tail.clear();
        } else if (elevating && !authenticating && tail.find("password:") != std::string::npos) {
            send(password); authenticating = true; tail.clear();
        } else if (authenticating && tail.find("root@flyby:") != std::string::npos) {
            if (all.find("\r\nFLYBY_USER_OK\r\n") == std::string::npos)
                throw std::runtime_error("Ordinary account permission checks failed");
            return;
        }
        if (tail.size() > 16384) tail.erase(0, tail.size() - 16384);
    }
    throw std::runtime_error("Guest password setup or doas authentication timed out");
}
