package io.github.aksulightning.flyby.vm

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Install once, atomically. An existing user disk is never replaced or formatted. */
object DiskImage {
    const val SIZE = 256L * 1024 * 1024
    fun validate(directory: File, mode: DiskMode = DiskMode.DATA): File {
        val disk = privateFile(directory, mode.fileName)
        require(disk.isFile && disk.canRead() && disk.canWrite() && disk.length() == mode.size) {
            "Persistent disk is missing, inaccessible or has an invalid size; it was not modified"
        }
        return disk
    }
    fun prepare(directory: File, expectedHash: String, mode: DiskMode = DiskMode.DATA, seed: () -> InputStream): File {
        val disk = privateFile(directory, mode.fileName)
        if (disk.exists()) return validate(directory, mode)
        val pending = privateFile(directory, mode.fileName + ".pending")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            GZIPInputStream(seed()).use { input ->
                RandomAccessFile(pending, "rw").use { output ->
                    output.setLength(0)
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= mode.size) { "Disk seed exceeds configured size" }
                        digest.update(buffer, 0, n)
                        if ((0 until n).all { buffer[it] == 0.toByte() }) output.seek(output.filePointer + n)
                        else output.write(buffer, 0, n)
                    }
                    output.setLength(size)
                    output.fd.sync()
                }
            }
            require(size == mode.size && digest.digest().joinToString("") { "%02x".format(it) } == expectedHash) {
                "Invalid persistent disk seed"
            }
            check(!disk.exists() && pending.renameTo(disk)) { "Cannot install persistent disk" }
        } finally { pending.delete() }
        return validate(directory, mode)
    }
    internal fun privateFile(root: File, name: String): File {
        val file = File(root.canonicalFile, name)
        require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile == file.absoluteFile) { "Persistent disk path must not be a symlink" }
        return file
    }
}
