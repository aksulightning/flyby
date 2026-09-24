package io.github.aksulightning.flyby.vm

import java.io.File

data class VmConfig(val memoryMiB: Int = 512, val cpuCount: Int = 1) {
    fun validate() {
        require(memoryMiB in 128..2048) { "RAM must be between 128 and 2048 MiB" }
        require(cpuCount in 1..4) { "CPU count must be between 1 and 4" }
    }
}

/** Roots must come from Context.filesDir and ApplicationInfo.nativeLibraryDir, never UI input. */
data class VmFiles(
    val privateDirectory: File,
    val nativeDirectory: File,
    val kernel: File = File(privateDirectory, "kernel"),
    val initrd: File = File(privateDirectory, "initrd"),
    val executable: File = File(nativeDirectory, "libqemu-system-aarch64.so"),
) {
    fun validated(): VmFiles {
        fun checked(file: File, root: File, label: String): File {
            require('\u0000' !in file.path && '\n' !in file.path && '\r' !in file.path) {
                "$label path contains a control character"
            }
            val canonical = file.canonicalFile
            require(canonical.toPath().startsWith(root.canonicalFile.toPath()) && canonical != root.canonicalFile) {
                "$label must stay inside its application directory"
            }
            require(canonical.isFile && canonical.canRead() && canonical.length() > 0) {
                "$label is missing, empty or unreadable: ${canonical.name}"
            }
            return canonical
        }
        val binary = checked(executable, nativeDirectory, "Android ARM64 QEMU runtime")
        require(binary.name == "libqemu-system-aarch64.so" && binary.canExecute()) {
            "QEMU must be an executable installed in nativeLibraryDir"
        }
        return copy(
            privateDirectory = privateDirectory.canonicalFile,
            nativeDirectory = nativeDirectory.canonicalFile,
            executable = binary,
            kernel = checked(kernel, privateDirectory, "Linux kernel"),
            initrd = checked(initrd, privateDirectory, "Linux initramfs"),
        )
    }
}
