package io.github.aksulightning.flyby.vm

data class VmConfig(val memoryMiB: Int = 512, val cpuCount: Int = 1, val gpuEnabled: Boolean = false, val vulkanEnabled: Boolean = false) {
    fun validate() {
        require(memoryMiB in 128..768) { "RAM must be between 128 and 768 MiB" }
        require(!vulkanEnabled || gpuEnabled) { "Enable GPU before Vulkan" }
        require(cpuCount in 1..2) { "CPU count must be 1 or 2" }
    }
    fun validateRuntime() {
        validate()
    }
}
