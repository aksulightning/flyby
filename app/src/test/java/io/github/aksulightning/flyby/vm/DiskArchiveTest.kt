package io.github.aksulightning.flyby.vm

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*

class DiskArchiveTest {
    @get:Rule val temp = TemporaryFolder()
    private fun disk(): File = File(temp.root, "disk.raw").also { file ->
        RandomAccessFile(file, "rw").use {
            it.setLength(DiskMode.DATA.size); it.seek(1080); it.write(byteArrayOf(0x53, 0xef.toByte()))
            it.seek(2048); it.writeUTF("original content")
        }
    }
    @Test fun roundTripRestoresAllDiskBytes() {
        val disk = disk(); val archive = temp.newFile("backup.flyby")
        archive.outputStream().use { DiskArchive.export(temp.root, DiskMode.DATA, it) }
        RandomAccessFile(disk,"rw").use { it.seek(2048); it.writeUTF("changed content") }
        archive.inputStream().use { DiskArchive.import(temp.root, DiskMode.DATA, it) }
        RandomAccessFile(disk,"r").use { it.seek(2048); assertEquals("original content",it.readUTF()) }
        assertFalse(File(temp.root,"disk.raw.importing").exists())
    }
    @Test fun truncatedArchivePreservesExistingDisk() {
        val disk = disk(); val archive = temp.newFile("backup.flyby")
        archive.outputStream().use { DiskArchive.export(temp.root, DiskMode.DATA, it) }
        RandomAccessFile(archive,"rw").use { it.setLength(it.length()/2) }
        assertThrows(Exception::class.java) { archive.inputStream().use { DiskArchive.import(temp.root,DiskMode.DATA,it) } }
        RandomAccessFile(disk,"r").use { it.seek(2048); assertEquals("original content",it.readUTF()) }
        assertFalse(File(temp.root,"disk.raw.importing").exists())
    }
    @Test fun wrongModeIsRejectedBeforeReplacement() {
        disk(); val archive=temp.newFile("backup.flyby")
        archive.outputStream().use { DiskArchive.export(temp.root, DiskMode.DATA, it) }
        assertThrows(IllegalArgumentException::class.java) { archive.inputStream().use { DiskArchive.import(temp.root,DiskMode.SYSTEM,it) } }
        assertFalse(File(temp.root,"system.raw").exists())
    }
    @Test fun variableSystemSizeRoundTrip() {
        val disk = File(temp.root, "system.raw")
        RandomAccessFile(disk, "rw").use {
            it.setLength(2 * DiskImage.GIB); it.seek(1080); it.write(byteArrayOf(0x53, 0xef.toByte()))
            it.seek(DiskImage.GIB + 32); it.writeUTF("beyond original seed")
        }
        val backup = temp.newFile("large.flyby")
        backup.outputStream().use { DiskArchive.export(temp.root, DiskMode.SYSTEM, it) }
        RandomAccessFile(disk, "rw").use { it.setLength(DiskImage.GIB) }
        backup.inputStream().use { DiskArchive.import(temp.root, DiskMode.SYSTEM, it) }
        assertEquals(2 * DiskImage.GIB, disk.length())
        RandomAccessFile(disk, "r").use { it.seek(DiskImage.GIB + 32); assertEquals("beyond original seed", it.readUTF()) }
    }

}
