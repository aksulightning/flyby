package io.github.aksulightning.flyby.vm

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Install once, atomically. An existing user disk is never replaced or formatted. */
object DiskImage {
    const val SIZE = 256L * 1024 * 1024
    fun validate(directory: File): File {
        val disk = privateFile(directory, "disk.raw")
        require(disk.isFile && disk.canRead() && disk.canWrite() && disk.length() == SIZE) {
            "Persistent disk is missing, inaccessible or has an invalid size; it was not modified"
        }
        return disk
    }
    fun prepare(directory: File, expectedHash: String, seed: () -> InputStream): File {
        val disk = privateFile(directory, "disk.raw")
        if (disk.exists()) return validate(directory)
        val pending = privateFile(directory, "disk.raw.pending")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            GZIPInputStream(seed()).use { input ->
                FileOutputStream(pending).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= SIZE) { "Disk seed exceeds configured size" }
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            require(size == SIZE && digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) {
                "Invalid persistent disk seed"
            }
            check(!disk.exists() && pending.renameTo(disk)) { "Cannot install persistent disk" }
        } finally { pending.delete() }
        return validate(directory)
    }
    private fun privateFile(root: File, name: String): File {
        val file = File(root.canonicalFile, name)
        require(file.canonicalFile == file.absoluteFile) { "Persistent disk path must not be a symlink" }
        return file
    }
}
