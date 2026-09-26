package io.github.aksulightning.flyby.vm

import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Versioned streaming backup: metadata, exact disk bytes, SHA-256; no host paths. */
object DiskArchive {
    private const val MAGIC = "FLYBY-DISK"
    fun export(directory: File, mode: DiskMode, destination: OutputStream, progress: (Int) -> Unit = {}) {
        val disk = DiskImage.validate(directory, mode)
        DataOutputStream(GZIPOutputStream(destination, 1024 * 1024)).use { out ->
            out.writeUTF(MAGIC); out.writeInt(1); out.writeUTF(mode.name)
            out.writeUTF(DiskMode.GUEST_ID); out.writeLong(disk.length())
            val digest = MessageDigest.getInstance("SHA-256")
            disk.inputStream().use { input ->
                copy(input, disk.length(), progress) { b, n -> digest.update(b, 0, n); out.write(b, 0, n) }
                check(input.read() == -1) { "Disk changed while exporting" }
            }
            out.write(digest.digest())
        }
    }
    fun import(directory: File, mode: DiskMode, source: InputStream, progress: (Int) -> Unit = {}) {
        val target = DiskImage.privateFile(directory, mode.fileName)
        val pending = DiskImage.privateFile(directory, mode.fileName + ".importing")
        try {
            DataInputStream(GZIPInputStream(source, 1024 * 1024)).use { input ->
                require(input.readUTF() == MAGIC && input.readInt() == 1) { "Not a supported Flyby disk backup" }
                require(input.readUTF() == mode.name) { "Backup disk type differs from the selected storage mode" }
                require(input.readUTF() == DiskMode.GUEST_ID) { "Backup requires a different guest/kernel version" }
                val size = input.readLong()
                require(DiskImage.validSize(mode, size)) { "Invalid backup disk size" }
                val digest = MessageDigest.getInstance("SHA-256")
                RandomAccessFile(pending, "rw").use { output ->
                    output.setLength(0)
                    copy(input, size, progress) { b, n ->
                        digest.update(b, 0, n)
                        if ((0 until n).all { b[it] == 0.toByte() }) output.seek(output.filePointer + n)
                        else output.write(b, 0, n)
                    }
                    output.setLength(size)
                    val expected = ByteArray(32); input.readFully(expected)
                    require(MessageDigest.isEqual(expected, digest.digest()) && input.read() == -1) { "Backup checksum mismatch or unexpected data" }
                    output.seek(1080)
                    require(output.readUnsignedByte() == 0x53 && output.readUnsignedByte() == 0xef) { "Backup is not an ext4 disk" }
                    output.fd.sync()
                }
            }
            // Atomic replacement only after complete validation. Failure leaves the old disk intact.
            check(pending.renameTo(target)) { "Cannot replace disk; existing disk was retained" }
        } finally { pending.delete() }
    }
    private fun copy(input: InputStream, size: Long, progress: (Int) -> Unit, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(1024 * 1024)
        var total = 0L; var percent = -1
        while (total < size) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), size - total).toInt())
            require(n > 0) { "Truncated disk backup" }
            write(buffer, n); total += n
            val next = (total * 100 / size).toInt()
            if (next != percent) { progress(next); percent = next }
        }
    }
}
