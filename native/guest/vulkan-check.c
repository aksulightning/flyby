// Small Vulkan transfer/readback acceptance test, run as the ordinary guest user.
// Uses the guest's real loader and Venus ICD, with no software ICD packaged.
#include "venus/venus-protocol/vulkan.h"
#include <stddef.h>
#include <stdint.h>
static long call(long n, long a, long b, long c) {
    register long a0 __asm__("a0") = a;
    register long a1 __asm__("a1") = b;
    register long a2 __asm__("a2") = c;
    register long a7 __asm__("a7") = n;
    __asm__ volatile("ecall" : "+r"(a0) : "r"(a1), "r"(a2), "r"(a7) : "memory");
    return a0;
}
static void say(const char *s) { size_t n = 0; while (s[n]) ++n; call(64, 1, (long)s, n); }
#define OK(x) do { if ((x) != VK_SUCCESS) { say("FLYBY_VULKAN_FAILED: " #x "\n"); return 1; } } while (0)
static int run(void) {
    VkApplicationInfo app = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .pApplicationName = "Flyby Vulkan check", .apiVersion = VK_API_VERSION_1_1 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &app };
    VkInstance instance; OK(vkCreateInstance(&ici, 0, &instance));
    uint32_t count = 0; OK(vkEnumeratePhysicalDevices(instance, &count, 0));
    if (!count || count > 16) return 2;
    VkPhysicalDevice devices[16]; OK(vkEnumeratePhysicalDevices(instance, &count, devices));
    VkPhysicalDevice physical = devices[0];
    VkPhysicalDeviceProperties properties; vkGetPhysicalDeviceProperties(physical, &properties);
    say("Vulkan device: "); say(properties.deviceName); say("\n");
    uint32_t families = 0; vkGetPhysicalDeviceQueueFamilyProperties(physical, &families, 0);
    if (!families || families > 64) return 3;
    VkQueueFamilyProperties queues[64]; vkGetPhysicalDeviceQueueFamilyProperties(physical, &families, queues);
    uint32_t family = 0;
    while (family < families && !(queues[family].queueFlags & (VK_QUEUE_TRANSFER_BIT | VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT))) ++family;
    if (family == families) return 4;
    float priority = 1;
    VkDeviceQueueCreateInfo qci = { .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO, .queueFamilyIndex = family, .queueCount = 1, .pQueuePriorities = &priority };
    VkDeviceCreateInfo dci = { .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, .queueCreateInfoCount = 1, .pQueueCreateInfos = &qci };
    VkDevice device; OK(vkCreateDevice(physical, &dci, 0, &device));
    VkQueue queue; vkGetDeviceQueue(device, family, 0, &queue);
    VkBufferCreateInfo bci = { .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO, .size = 4096, .usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT, .sharingMode = VK_SHARING_MODE_EXCLUSIVE };
    VkBuffer buffer; OK(vkCreateBuffer(device, &bci, 0, &buffer));
    VkMemoryRequirements req; vkGetBufferMemoryRequirements(device, buffer, &req);
    VkPhysicalDeviceMemoryProperties memory; vkGetPhysicalDeviceMemoryProperties(physical, &memory);
    uint32_t type = 0;
    while (type < memory.memoryTypeCount && (!(req.memoryTypeBits & (1u << type)) ||
        (memory.memoryTypes[type].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) !=
        (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))) ++type;
    if (type == memory.memoryTypeCount) return 5;
    VkMemoryAllocateInfo mai = { .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, .allocationSize = req.size, .memoryTypeIndex = type };
    VkDeviceMemory allocation; OK(vkAllocateMemory(device, &mai, 0, &allocation));
    OK(vkBindBufferMemory(device, buffer, allocation, 0));
    VkCommandPoolCreateInfo pci = { .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO, .queueFamilyIndex = family };
    VkCommandPool pool; OK(vkCreateCommandPool(device, &pci, 0, &pool));
    VkCommandBufferAllocateInfo cai = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO, .commandPool = pool, .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY, .commandBufferCount = 1 };
    VkCommandBuffer command; OK(vkAllocateCommandBuffers(device, &cai, &command));
    VkCommandBufferBeginInfo begin = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO, .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT };
    OK(vkBeginCommandBuffer(command, &begin));
    vkCmdFillBuffer(command, buffer, 0, 4096, 0x46425956);
    VkMemoryBarrier barrier = { .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER, .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT, .dstAccessMask = VK_ACCESS_HOST_READ_BIT };
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, 0, 0, 0);
    OK(vkEndCommandBuffer(command));
    VkFenceCreateInfo fci = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    VkFence fence; OK(vkCreateFence(device, &fci, 0, &fence));
    VkSubmitInfo submit = { .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO, .commandBufferCount = 1, .pCommandBuffers = &command };
    OK(vkQueueSubmit(queue, 1, &submit, fence));
    OK(vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ULL));
    uint32_t *mapped; OK(vkMapMemory(device, allocation, 0, 4096, 0, (void**)&mapped));
    for (size_t i = 0; i < 1024; ++i) if (mapped[i] != 0x46425956) { say("Vulkan readback mismatch\n"); return 6; }
    vkUnmapMemory(device, allocation);
    vkDestroyFence(device, fence, 0);
    vkDestroyCommandPool(device, pool, 0);
    vkDestroyBuffer(device, buffer, 0);
    vkFreeMemory(device, allocation, 0);
    vkDestroyDevice(device, 0);
    vkDestroyInstance(instance, 0);
    say("FLYBY_VULKAN_TRANSFER_OK\n");
    return 0;
}
static int app_main(int argc, char **argv, char **env) { (void)argc; (void)argv; (void)env; return run(); }
extern int __libc_start_main(int (*main)(int, char**, char**), int argc, char **argv,
                            void (*init)(void), void (*fini)(void), void (*ldso_fini)(void));
void flyby_start(long *stack) {
    __libc_start_main(app_main, (int)stack[0], (char**)(stack + 1), 0, 0, 0);
    call(93, 127, 0, 0);
    for (;;) {}
}
__asm__(".global _start\n_start:\n mv a0, sp\n tail flyby_start\n");
