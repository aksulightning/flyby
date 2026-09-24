#include "vm.h"
extern "C" {
#include <devices/ns16550a.h>
#include <devices/rtl8169.h>
#include <rvvm/rvvm.h>
#include <rvvm/rvvm_board.h>
#include <rvvm/rvvm_fdt.h>
}
#include <algorithm>
#include <array>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <fstream>
#include <mutex>
#include <sstream>
#include <stdexcept>

namespace flyby {
namespace {
constexpr size_t OutputCapacity = 1024 * 1024, InputCapacity = 64 * 1024;
std::vector<uint8_t> load(const std::string &path, size_t limit) {
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream)
        throw std::runtime_error("Guest resource missing: " + path);
    auto size = stream.tellg();
    if (size <= 0 || static_cast<size_t>(size) > limit)
        throw std::runtime_error("Invalid guest resource size: " + path);
    std::vector<uint8_t> result(static_cast<size_t>(size));
    stream.seekg(0);
    if (!stream.read(reinterpret_cast<char *>(result.data()), size))
        throw std::runtime_error("Cannot read guest resource: " + path);
    return result;
}
struct Console {
    chardev_t dev{};
    std::mutex mutex;
    std::condition_variable changed;
    // Allocate bounded queues before starting a vCPU. UART callbacks do not allocate.
    std::vector<uint8_t> incoming = std::vector<uint8_t>(InputCapacity);
    std::vector<uint8_t> outgoing = std::vector<uint8_t>(OutputCapacity);
    size_t inStart = 0, inSize = 0, outStart = 0, outSize = 0, readyMatch = 0;
    bool closed = false, enabled;
    explicit Console(bool gated = false) : enabled(!gated) {
        dev.data = this;
        dev.poll = [](chardev_t *d) {
            auto &c = *static_cast<Console *>(d->data);
            std::lock_guard lock(c.mutex);
            return uint32_t(CHARDEV_TX | (c.inSize && c.enabled ? CHARDEV_RX : 0));
        };
        dev.read = [](chardev_t *d, void *bytes, size_t size) {
            auto &c = *static_cast<Console *>(d->data);
            std::lock_guard lock(c.mutex);
            size = c.enabled ? std::min(size, c.inSize) : 0;
            auto *b = static_cast<uint8_t *>(bytes);
            for (size_t i = 0; i < size; ++i) {
                b[i] = c.incoming[c.inStart];
                c.inStart = (c.inStart + 1) % InputCapacity;
                --c.inSize;
            }
            return size;
        };
        dev.write = [](chardev_t *d, const void *bytes, size_t size) {
            auto &c = *static_cast<Console *>(d->data);
            std::lock_guard lock(c.mutex);
            const auto *b = static_cast<const uint8_t *>(bytes);
            constexpr char ready[] = "FLYBY_CONTROL_READY\n";
            for (size_t i = 0; i < size; ++i) {
                if (!c.enabled) {
                    c.readyMatch = b[i] == ready[c.readyMatch] ? c.readyMatch + 1 : 0;
                    if (c.readyMatch == sizeof(ready) - 1)
                        c.enabled = true;
                }
                if (c.outSize == OutputCapacity) {
                    c.outStart = (c.outStart + 1) % OutputCapacity;
                    --c.outSize;
                }
                c.outgoing[(c.outStart + c.outSize++) % OutputCapacity] = b[i];
            }
            c.changed.notify_one();
            return size;
        };
        dev.update = [](chardev_t *d) { chardev_notify(d, chardev_poll(d)); };
        dev.remove = [](chardev_t *d) {
            auto &c = *static_cast<Console *>(d->data);
            std::lock_guard lock(c.mutex);
            c.closed = true;
            c.changed.notify_all();
        };
    }
    void write(const uint8_t *data, size_t size) {
        {
            std::lock_guard lock(mutex);
            if (closed)
                throw std::runtime_error("Console is closed");
            if (size > InputCapacity - inSize)
                throw std::runtime_error("Console input buffer is full");
            for (size_t i = 0; i < size; ++i)
                incoming[(inStart + inSize++) % InputCapacity] = data[i];
        }
        chardev_notify(&dev, chardev_poll(&dev));
    }
};
} // namespace
struct Vm::Impl {
    std::mutex lifecycle;
    rvvm_machine_t *machine = nullptr;
    Console console;
    Console control{true};
    Console shared;
    ~Impl() {
        if (machine)
            rvvm_free_machine(machine);
    }
};
Vm::Vm(const std::string &dir, unsigned memoryMiB, unsigned cpus, const std::string &diskPath,
       bool fullSystem, bool sharedFolder)
    : impl(std::make_unique<Impl>()) {
    if (memoryMiB < 128 || memoryMiB > 768 || cpus != 1)
        throw std::invalid_argument("RV64 requires 128–768 MiB and one CPU in this milestone");
    auto kernel = load(dir + "/kernel", 120 * 1024 * 1024);
    if (kernel.size() < 64 || std::memcmp(kernel.data() + 56, "RSC\x05", 4))
        throw std::invalid_argument("Invalid RISC-V Linux Image header");
    auto fw = load(dir + "/firmware", 2 * 1024 * 1024);
    auto initrd = load(dir + "/initrd", 64 * 1024 * 1024);
    const uint64_t initrdBase = (RamBase + (uint64_t(memoryMiB) << 20) - initrd.size()) & ~uint64_t(4095);
    if (initrdBase < KernelBase + kernel.size() + 2 * 1024 * 1024)
        throw std::runtime_error("Guest kernel and initrd do not fit in the selected RAM");
    impl->machine = rvvm_create_machine(size_t(memoryMiB) << 20, cpus, "rv64");
    auto *m = impl->machine;
    if (!m)
        throw std::runtime_error("Native VM RAM allocation/initialization failed");
    auto *irq = rvvm_riscv_plic_init(m, Plic);
    if (!irq || !rvvm_riscv_clint_init(m, Clint) || !rvvm_syscon_init(m, Syscon) ||
        !rvvm_ns16550a_init(m, &impl->console.dev, Uart, 0, irq, 1) ||
        !rvvm_ns16550a_init(m, &impl->control.dev, ControlUart, 0, irq, 2) ||
        (sharedFolder && !rvvm_ns16550a_init(m, &impl->shared.dev, SharedUart, 0, irq, 8)))
        throw std::runtime_error("Native board initialization failed");
    const rvvm_irq_t pciIrqs[] = {3, 4, 5, 6};
    if (!rvvm_pci_ecam_init(m, 0, PciEcam, irq, pciIrqs, PciIo, PciMemory, PciMemorySize) ||
        !rvvm_rtc_goldfish_init(m, Rtc, irq, 7) || !rtl8169_init_auto(m))
        throw std::runtime_error("Cannot initialize PCI, RTC or userspace networking");
    if (!diskPath.empty() && !rvvm_nvme_init_auto(m, diskPath.c_str()))
        throw std::runtime_error("Cannot attach persistent NVMe disk");
    if (!rvvm_load_firmware(m, (dir + "/firmware").c_str()) ||
        !rvvm_load_kernel(m, (dir + "/kernel").c_str()) ||
        !rvvm_write_ram(m, initrdBase, initrd.data(), initrd.size()))
        throw std::runtime_error("Cannot load guest into native RAM");
    auto *chosen = rvvm_fdt_find(rvvm_get_fdt_root(m), "chosen");
    rvvm_fdt_prop_set_u64(chosen, "linux,initrd-start", initrdBase);
    rvvm_fdt_prop_set_u64(chosen, "linux,initrd-end", initrdBase + initrd.size());
    std::string command =
        "console=ttyS0,115200 earlycon=uart8250,mmio,0x10000000 rdinit=/init loglevel=6 panic=0";
    if (!diskPath.empty())
        command += " flyby.disk=1";
    if (fullSystem)
        command += " flyby.root=1";
    if (sharedFolder)
        command += " flyby.shared=1";
    rvvm_set_cmdline(m, command.c_str());
}
Vm::~Vm() {
    stop();
}
void Vm::start() {
    std::lock_guard lock(impl->lifecycle);
    if (!impl->machine || rvvm_machine_running(impl->machine))
        throw std::runtime_error("VM already running or stopped");
    if (!rvvm_start_machine(impl->machine))
        throw std::runtime_error("Native VM start failed");
}
void Vm::pause() {
    std::lock_guard lock(impl->lifecycle);
    rvvm_pause_machine(impl->machine);
}
void Vm::resume() {
    start();
}
void Vm::stop() {
    std::lock_guard lock(impl->lifecycle);
    if (impl->machine) {
        rvvm_free_machine(impl->machine);
        impl->machine = nullptr;
    }
}
bool Vm::running() {
    std::lock_guard lock(impl->lifecycle);
    return impl->machine && rvvm_machine_running(impl->machine);
}
void Vm::input(const uint8_t *data, size_t size) {
    std::lock_guard lock(impl->lifecycle);
    if (!impl->machine)
        throw std::runtime_error("VM stopped");
    impl->console.write(data, size);
}
void Vm::requestStop() {
    std::lock_guard lock(impl->lifecycle);
    if (impl->machine) {
        const std::string s = "stop\n";
        impl->control.write(reinterpret_cast<const uint8_t *>(s.data()), s.size());
    }
}
void Vm::resize(unsigned rows, unsigned cols) {
    if (rows < 2 || rows > 300 || cols < 2 || cols > 500)
        throw std::invalid_argument("Invalid terminal dimensions");
    std::lock_guard lock(impl->lifecycle);
    if (impl->machine) {
        auto s = "resize " + std::to_string(rows) + " " + std::to_string(cols) + "\n";
        impl->control.write(reinterpret_cast<const uint8_t *>(s.data()), s.size());
    }
}
static std::vector<uint8_t> readConsole(Console &c, unsigned timeoutMs) {
    std::unique_lock lock(c.mutex);
    c.changed.wait_for(lock, std::chrono::milliseconds(std::min(timeoutMs, 1000u)),
                       [&] { return c.closed || c.outSize; });
    size_t n = std::min(c.outSize, size_t(16384));
    std::vector<uint8_t> b(n);
    for (size_t i = 0; i < n; ++i) {
        b[i] = c.outgoing[c.outStart];
        c.outStart = (c.outStart + 1) % OutputCapacity;
        --c.outSize;
    }
    return b;
}
std::vector<uint8_t> Vm::output(unsigned timeoutMs) { return readConsole(impl->console, timeoutMs); }
std::vector<uint8_t> Vm::sharedOutput() { return readConsole(impl->shared, 0); }
void Vm::sharedInput(const uint8_t *data, size_t size) {
    std::lock_guard lock(impl->lifecycle);
    if (!impl->machine) throw std::runtime_error("VM stopped");
    impl->shared.write(data, size);
}
} // namespace flyby
