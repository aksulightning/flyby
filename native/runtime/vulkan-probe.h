#pragma once
#include <string>
namespace flyby {
struct VulkanSupport { bool available; std::string detail; };
VulkanSupport probeVulkan();
}
