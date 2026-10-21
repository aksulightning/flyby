#include "vulkan-probe.h"
#include <venus/venus-protocol/vulkan.h>
#include <dlfcn.h>
#include <algorithm>
#include <cstring>
#include <vector>
namespace flyby {
VulkanSupport probeVulkan() {
#ifdef __ANDROID__
    const char *library = "libvulkan.so";
#else
    const char *library = "libvulkan.so.1";
#endif
    void *loader = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!loader) return {false, "Vulkan loader unavailable"};
    auto proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(loader, "vkGetInstanceProcAddr"));
    if (!proc) { dlclose(loader); return {false, "Vulkan entry point unavailable"}; }
    auto create = reinterpret_cast<PFN_vkCreateInstance>(proc(VK_NULL_HANDLE, "vkCreateInstance"));
    if (!create) { dlclose(loader); return {false, "Vulkan instance creation unavailable"}; }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Flyby Vulkan probe"; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; info.pApplicationInfo = &app;
    VkInstance instance{};
    if (create(&info, nullptr, &instance) != VK_SUCCESS) {
        dlclose(loader); return {false, "Vulkan 1.1 is unavailable"};
    }
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(proc(instance, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(proc(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(proc(instance, "vkGetPhysicalDeviceProperties"));
    auto extensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(proc(instance, "vkEnumerateDeviceExtensionProperties"));
    VulkanSupport result{false, "No Vulkan device supports the required Venus memory extensions"};
    if (destroy && enumerate && properties && extensions) {
        uint32_t count = 0;
        if (enumerate(instance, &count, nullptr) == VK_SUCCESS && count && count <= 64) {
            std::vector<VkPhysicalDevice> devices(count);
            if (enumerate(instance, &count, devices.data()) == VK_SUCCESS) for (auto device : devices) {
                VkPhysicalDeviceProperties props{}; properties(device, &props);
                if (props.apiVersion < VK_API_VERSION_1_1) continue;
                uint32_t n = 0;
                if (extensions(device, nullptr, &n, nullptr) != VK_SUCCESS || n > 4096) continue;
                std::vector<VkExtensionProperties> exts(n);
                if (extensions(device, nullptr, &n, exts.data()) != VK_SUCCESS) continue;
                auto has = [&](const char *name) { return std::any_of(exts.begin(), exts.end(),
                    [&](const auto &ext) { return !std::strcmp(name, ext.extensionName); }); };
#ifdef __ANDROID__
                bool compatible = has("VK_EXT_external_memory_dma_buf") &&
                    has("VK_EXT_image_drm_format_modifier") && has("VK_EXT_queue_family_foreign");
#else
                bool compatible = has("VK_KHR_external_memory_fd");
#endif
                if (compatible) { result = {true, std::string("Venus candidate: ") + props.deviceName}; break; }
            }
        }
    }
    if (destroy) destroy(instance, nullptr);
    dlclose(loader);
    return result;
}
}
