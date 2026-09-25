package io.github.aksulightning.flyby.vm

data class VmConfig(val memoryMiB: Int = 512, val cpuCount: Int = 1, val upgradeEdge: Boolean = false) {
    fun validate() {
        require(memoryMiB in 128..768) { "RAM must be between 128 and 768 MiB" }
        require(cpuCount in 1..4) { "CPU count must be between 1 and 4" }
    }
    fun validateRuntime() {
        validate()
        require(cpuCount == 1) {
            "RV64 currently supports 128–768 MiB and one CPU"
        }
    }
}
