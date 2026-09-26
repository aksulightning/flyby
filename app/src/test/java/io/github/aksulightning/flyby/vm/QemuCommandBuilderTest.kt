package io.github.aksulightning.flyby.vm

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QemuCommandBuilderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun producesHeadlessArmVirtTcgCommand() {
        val files = temporary.vmFiles()
        val args = QemuCommandBuilder.build(VmConfig(), files)
        fun value(flag: String) = args[args.indexOf(flag) + 1]
        assertEquals(files.executable.canonicalPath, args.first())
        assertEquals("virt", value("-machine"))
        assertEquals("tcg,thread=single", value("-accel"))
        assertEquals("512", value("-m"))
        assertEquals("1", value("-smp"))
        assertEquals("none", value("-display"))
        assertEquals("none", value("-monitor"))
        assertEquals("stdio", value("-serial"))
        assertEquals("none", value("-nic"))
        assertEquals("console=ttyAMA0,115200 rdinit=/init panic=0", value("-append"))
        assertFalse(args.any { it.contains("kvm") || it == "sh" || it == "-c" })
    }

    @Test fun customResourceValuesAreValidatedAndUsed() {
        val args = QemuCommandBuilder.build(VmConfig(768, 2), temporary.vmFiles())
        assertEquals("768", args[args.indexOf("-m") + 1])
        assertEquals("2", args[args.indexOf("-smp") + 1])
    }

    @Test fun spacesAndShellMetacharactersRemainOneLiteralArgument() {
        val files = temporary.vmFiles()
        val image = File(files.privateDirectory, "kernel ; $(touch nope)").apply { writeText("kernel") }
        val args = QemuCommandBuilder.build(VmConfig(), files.copy(kernel = image))
        assertEquals(image.canonicalPath, args[args.indexOf("-kernel") + 1])
        assertFalse(File(files.privateDirectory, "nope").exists())
    }

    @Test fun invalidConfigAndMissingRuntimeFailBeforeLaunch() {
        val files = temporary.vmFiles()
        assertThrows(IllegalArgumentException::class.java) { QemuCommandBuilder.build(VmConfig(0), files) }
        files.executable.delete()
        assertThrows(IllegalArgumentException::class.java) { QemuCommandBuilder.build(VmConfig(), files) }
    }
}
