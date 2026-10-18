#pragma once
#include <memory>
extern "C" {
#include <rvvm/rvvm.h>
#include <rvvm/rvvm_irq.h>
}
namespace flyby {
class Display;
// One renderer thread owns every EGL/VirGL call. Guest queue processing and
// renderer teardown finish before RVVM releases guest RAM or its interrupt controller.
class Gpu {
public:
    Gpu(rvvm_machine_t*, rvvm_irq_dev_t*, Display&);
    ~Gpu();
    void check();
    Gpu(const Gpu&) = delete;
    Gpu& operator=(const Gpu&) = delete;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
}
