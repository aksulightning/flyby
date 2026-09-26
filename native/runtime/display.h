#pragma once
#include <cstdint>
#include <mutex>
#include <vector>
extern "C" {
#include <rvvm/rvvm.h>
}
namespace flyby {
// CPU-rendered XRGB8888 framebuffer, exposed as a simple-framebuffer DT node.
// MMIO callbacks serialize guest writes with Android snapshots; no shared raw pointer.
class Display {
public:
    static constexpr unsigned Width = 800, Height = 600;
    Display();
    void attach(rvvm_machine_t *machine);
    std::vector<int32_t> frame();
private:
    std::mutex mutex;
    std::vector<uint8_t> pixels;
};
}
