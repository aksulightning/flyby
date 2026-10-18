package io.github.aksulightning.flyby.vm

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class GpuImageTest {
    @get:Rule val temp = TemporaryFolder()
    private fun disk(root: File, label: String): File = File(root, "system.raw").also { file ->
        RandomAccessFile(file, "rw").use {
            it.setLength(DiskImage.GIB)
            it.seek(1080); it.write(byteArrayOf(0x53, 0xef.toByte()))
            it.seek(1144); it.write(label.toByteArray(Charsets.US_ASCII))
        }
    }
    @Test fun gpuImageSelectionRequiresEnabledSetting() {
        assertFalse(ImageVariant.X11_GPU.available(false))
        assertTrue(ImageVariant.X11_GPU.available(true))
        ImageVariant.entries.filter { it != ImageVariant.X11_GPU }.forEach { assertTrue(it.available(false)) }
    }
    @Test fun switchingGpuOffBlocksBootWithoutChangingDisk() {
        val image = disk(temp.root, "flyby-x11-gpu")
        assertTrue(DiskImage.requireGpuAllowed(image, true))
        assertThrows(IllegalArgumentException::class.java) { DiskImage.requireGpuAllowed(image, false) }
        assertTrue(DiskImage.requiresGpu(image))
        assertEquals(DiskImage.GIB, image.length())
        assertTrue(DiskImage.requireGpuAllowed(image, true))
    }
    @Test fun ordinaryX11DiskDoesNotUseGpuWhenSettingIsEnabled() {
        val image = disk(temp.root, "flyby-x11")
        assertFalse(DiskImage.requireGpuAllowed(image, false))
        assertFalse(DiskImage.requireGpuAllowed(image, true))
    }
    @Test fun importedGpuDiskRetainsTheRequirement() {
        val source = temp.newFolder("source")
        disk(source, "flyby-x11-gpu")
        val archive = temp.newFile("backup.gz")
        archive.outputStream().use { DiskArchive.export(source, DiskMode.SYSTEM, it) }
        val destination = temp.newFolder("destination")
        disk(destination, "flyby-x11")
        archive.inputStream().use { DiskArchive.import(destination, DiskMode.SYSTEM, it) }
        assertThrows(IllegalArgumentException::class.java) {
            DiskImage.requireGpuAllowed(DiskImage.validate(destination, DiskMode.SYSTEM), false)
        }
    }
}
