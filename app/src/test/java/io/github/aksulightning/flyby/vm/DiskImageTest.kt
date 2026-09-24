package io.github.aksulightning.flyby.vm

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class DiskImageTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun existingDiskIsNeverReplaced() {
        val disk = File(temp.root, "disk.raw")
        RandomAccessFile(disk, "rw").use { it.setLength(DiskImage.SIZE); it.writeUTF("user data") }
        DiskImage.prepare(temp.root, "unused") { error("Must not read seed for existing disk") }
        RandomAccessFile(disk, "r").use { assertEquals("user data", it.readUTF()) }
    }
    @Test fun corruptExistingDiskIsPreserved() {
        val disk = File(temp.root, "disk.raw").apply { writeText("keep this") }
        assertThrows(IllegalArgumentException::class.java) {
            DiskImage.prepare(temp.root, "unused") { error("Must not replace an invalid user disk") }
        }
        assertEquals("keep this", disk.readText())
    }
    @Test fun symlinkEscapeIsRejected() {
        val outside = temp.newFile("outside")
        Files.createSymbolicLink(File(temp.root, "disk.raw").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { DiskImage.validate(temp.root) }
    }
    @Test fun pendingSymlinkIsRejectedWithoutChangingTarget() {
        val outside = temp.newFile("outside").apply { writeText("keep") }
        Files.createSymbolicLink(File(temp.root, "disk.raw.pending").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) {
            DiskImage.prepare(temp.root, "unused") { ByteArrayInputStream(byteArrayOf()) }
        }
        assertEquals("keep", outside.readText())
    }
    @Test fun truncatedSeedDoesNotBecomeUserDisk() {
        val bytes = ByteArrayOutputStream().apply { GZIPOutputStream(this).use { it.write("short".toByteArray()) } }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            DiskImage.prepare(temp.root, "unused") { ByteArrayInputStream(bytes) }
        }
        assertFalse(File(temp.root, "disk.raw").exists())
        assertFalse(File(temp.root, "disk.raw.pending").exists())
    }
}
