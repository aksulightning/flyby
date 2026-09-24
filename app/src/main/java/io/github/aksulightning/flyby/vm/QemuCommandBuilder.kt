package io.github.aksulightning.flyby.vm

/** QEMU 9.2.4 CLI. Returned arguments go directly to ProcessBuilder, never to a shell. */
object QemuCommandBuilder {
    fun build(config: VmConfig, files: VmFiles): List<String> {
        config.validate()
        val valid = files.validated()
        return listOf(
            valid.executable.path,
            "-machine", "virt",
            "-accel", "tcg,thread=single",
            "-cpu", "cortex-a53",
            "-smp", config.cpuCount.toString(),
            "-m", config.memoryMiB.toString(),
            "-nodefaults",
            "-display", "none",
            "-monitor", "none",
            "-serial", "stdio",
            "-nic", "none",
            "-no-reboot",
            "-kernel", valid.kernel.path,
            "-initrd", valid.initrd.path,
            "-append", "console=ttyAMA0,115200 rdinit=/init panic=0",
        )
    }
}
