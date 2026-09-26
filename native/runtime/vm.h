#pragma once
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace flyby {
// The only board memory map. Firmware/kernel positions match RVVM's RV64 loader.
constexpr uint64_t RamBase = 0x80000000, KernelBase = RamBase + 0x200000;
constexpr uint64_t Clint = 0x02000000, Plic = 0x0c000000, Uart = 0x10000000;
constexpr uint64_t ControlUart = 0x10001000, SharedUart = 0x10002000, Syscon = 0x00100000;
constexpr uint64_t PciEcam = 0x30000000, PciIo = 0x03000000;
constexpr uint64_t PciMemory = 0x40000000, PciMemorySize = 0x40000000;
constexpr uint64_t Rtc = 0x00101000;
class Vm {
  public:
    Vm(const std::string &directory, unsigned memoryMiB, unsigned cpus, const std::string &diskPath = "",
       bool fullSystem = false, bool sharedFolder = false);
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
    void sharedInput(const uint8_t *data, size_t size);
    bool displayInput(const uint8_t *data, size_t size);
    std::vector<int32_t> displayFrame();
    std::vector<uint8_t> sharedOutput();
    std::vector<uint8_t> output(unsigned timeoutMs);

  private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
} // namespace flyby
