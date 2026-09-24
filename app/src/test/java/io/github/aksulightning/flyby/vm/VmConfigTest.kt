package io.github.aksulightning.flyby.vm

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultsAndBoundsAreValid() {
        assertEquals(512, VmConfig().memoryMiB)
        assertEquals(1, VmConfig().cpuCount)
        VmConfig().validate()
        VmConfig(128, 1).validate()
        VmConfig(2048, 4).validate()
    }

    @Test fun currentRuntimeLimitsAreExplicit() {
        listOf(256, 512, 1024).forEach { VmConfig(it).validateRuntime() }
        listOf(VmConfig(128), VmConfig(2048), VmConfig(cpuCount = 2)).forEach {
            assertThrows(IllegalArgumentException::class.java) { it.validateRuntime() }
        }
    }

    @Test fun invalidRamIsRejected() {
        listOf(Int.MIN_VALUE, -1, 0, 127, 2049, Int.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { VmConfig(it).validate() }
        }
    }

    @Test fun invalidCpuCountIsRejected() {
        listOf(-1, 0, 5, Int.MAX_VALUE).forEach {
            assertThrows(IllegalArgumentException::class.java) { VmConfig(cpuCount = it).validate() }
        }
    }

    @Test fun missingAndEmptyImagesAreRejected() {
        val files = temporary.vmFiles()
        files.kernel.delete()
        assertThrows(IllegalArgumentException::class.java) { files.validated() }
        files.kernel.writeText("")
        assertThrows(IllegalArgumentException::class.java) { files.validated() }
    }

    @Test fun traversalAndPrefixSiblingAreRejected() {
        val files = temporary.vmFiles()
        val sibling = temporary.newFolder("vm-other")
        val outside = File(sibling, "kernel").apply { writeText("outside") }
        assertThrows(IllegalArgumentException::class.java) { files.copy(kernel = outside).validated() }
        val traversal = File(files.privateDirectory, "../vm-other/kernel")
        assertThrows(IllegalArgumentException::class.java) { files.copy(kernel = traversal).validated() }
    }

    @Test fun symlinkEscapeIsRejected() {
        val files = temporary.vmFiles()
        val outside = temporary.newFile("outside").apply { writeText("outside") }
        files.kernel.delete()
        Files.createSymbolicLink(files.kernel.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { files.validated() }
    }

    @Test fun imageDirectoryAndControlCharactersAreRejected() {
        val files = temporary.vmFiles()
        assertThrows(IllegalArgumentException::class.java) {
            files.copy(initrd = files.privateDirectory).validated()
        }
        listOf("bad\u0000file", "bad\nfile", "bad\rfile").forEach {
            assertThrows(IllegalArgumentException::class.java) {
                files.copy(initrd = File(files.privateDirectory, it)).validated()
            }
        }
    }

    @Test fun executableMustBeInstalledInNativeDirectory() {
        val files = temporary.vmFiles()
        val writableBinary = File(files.privateDirectory, "libqemu-system-aarch64.so").apply {
            writeText("bad location")
            setExecutable(true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            files.copy(executable = writableBinary).validated()
        }
    }
}
