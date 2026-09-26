package io.github.aksulightning.flyby.vm

import java.io.File

/** Root comes exclusively from Context.filesDir. Only fixed filenames enter JNI. */
data class GuestFiles(val directory: File, val mode: DiskMode = DiskMode.DATA) {
    fun validated(): GuestFiles {
        require(directory.path.none { it == '\u0000' || it == '\n' || it == '\r' }) { "Invalid guest directory" }
        val root = directory.canonicalFile
        require(root.isDirectory) { "Guest resources are missing" }
        for (name in listOf("kernel", "firmware", "initrd")) {
            val file = File(directory, name).canonicalFile
            require(file.parentFile == root && file.name == name) { "Guest resource escapes private directory: $name" }
            require(file.isFile && file.canRead() && file.length() > 0) { "Guest resource missing or empty: $name" }
        }
        return GuestFiles(root, mode)
    }
}
