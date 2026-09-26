#include "display.h"
extern "C" {
#include <rvvm/rvvm_fdt.h>
#include <rvvm/rvvm_region.h>
}
#include <cstring>
#include <stdexcept>
namespace flyby {
Display::Display() : pixels(Width * Height * 4) {}
void Display::attach(rvvm_machine_t *machine) {
    static const rvvm_reg_type_t type = [] {
        rvvm_reg_type_t t{};
        t.name = "flyby-display";
        t.read = [](rvvm_reg_dev_t *dev, void *data, size_t size, size_t off) {
            auto &d = *static_cast<Display *>(rvvm_region_data(dev));
            std::lock_guard lock(d.mutex);
            if (off <= d.pixels.size() && size <= d.pixels.size() - off)
                std::memcpy(data, d.pixels.data() + off, size);
        };
        t.write = [](rvvm_reg_dev_t *dev, const void *data, size_t size, size_t off) {
            auto &d = *static_cast<Display *>(rvvm_region_data(dev));
            std::lock_guard lock(d.mutex);
            if (off <= d.pixels.size() && size <= d.pixels.size() - off)
                std::memcpy(d.pixels.data() + off, data, size);
        };
        return t;
    }();
    rvvm_reg_desc_t desc{};
    desc.addr = 0x18000000;
    desc.size = pixels.size();
    desc.data = this;
    desc.type = &type;
    desc.attr = RVVM_REG_ATTR_FIX;
    if (!rvvm_region_init(machine, &desc)) throw std::runtime_error("Cannot attach display");
    auto *node = rvvm_fdt_init_reg("framebuffer", desc.addr);
    rvvm_fdt_prop_set_reg(node, "reg", desc.addr, desc.size);
    rvvm_fdt_prop_set_str(node, "compatible", "simple-framebuffer");
    rvvm_fdt_prop_set_str(node, "format", "a8r8g8b8");
    rvvm_fdt_prop_set_u32(node, "width", Width);
    rvvm_fdt_prop_set_u32(node, "height", Height);
    rvvm_fdt_prop_set_u32(node, "stride", Width * 4);
    rvvm_fdt_attach(rvvm_get_fdt_soc(machine), node);
}
std::vector<int32_t> Display::frame() {
    std::vector<int32_t> result(Width * Height);
    std::lock_guard lock(mutex);
    for (size_t i = 0; i < result.size(); ++i) {
        const auto *p = pixels.data() + i * 4;
        result[i] = int32_t(0xff000000u | uint32_t(p[2]) << 16 | uint32_t(p[1]) << 8 | p[0]);
    }
    return result;
}
}
