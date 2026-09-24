#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace flyby {
// The only board memory map. Firmware/kernel positions match RVVM's RV64 loader.
constexpr uint64_t RamBase = 0x80000000, KernelBase = RamBase + 0x200000;
constexpr uint64_t InitrdBase = RamBase + 0x08000000;
constexpr uint64_t Clint = 0x02000000, Plic = 0x0c000000, Uart = 0x10000000;
constexpr uint64_t ControlUart = 0x10001000, Syscon = 0x00100000;
class Vm {
  public:
    Vm(const std::string &directory, unsigned memoryMiB, unsigned cpus);
    ~Vm();
    Vm(const Vm &) = delete;
    Vm &operator=(const Vm &) = delete;
    void start();
    void pause();
    void resume();
    void requestStop();
    void stop(); // joins native execution threads before reclaiming RAM
    bool running();
    void input(const uint8_t *data, size_t size);
    void resize(unsigned rows, unsigned cols);
    std::vector<uint8_t> output(unsigned timeoutMs);

  private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
} // namespace flyby
