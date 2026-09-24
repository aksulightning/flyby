package io.github.aksulightning.flyby.vm

data class VmConfig(val memoryMiB: Int = 512, val cpuCount: Int = 1) {
    fun validate() {
        require(memoryMiB in 128..2048) { "RAM must be between 128 and 2048 MiB" }
        require(cpuCount in 1..4) { "CPU count must be between 1 and 4" }
    }
    fun validateRuntime() {
        validate()
        require(memoryMiB in 256..1024 && cpuCount == 1) {
            "RV64 currently supports 256–1024 MiB and one CPU"
        }
    }
}
