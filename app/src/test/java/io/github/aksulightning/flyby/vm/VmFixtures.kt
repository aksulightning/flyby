package io.github.aksulightning.flyby.vm

import java.io.File
import org.junit.rules.TemporaryFolder

internal fun TemporaryFolder.vmFiles(): VmFiles {
    val files = VmFiles(newFolder("vm"), newFolder("native"))
    files.kernel.writeText("test kernel")
    files.initrd.writeText("test initrd")
    files.executable.writeText("test fixture, never executed")
    check(files.executable.setExecutable(true))
    return files
}

internal fun TemporaryFolder.guestFiles(): GuestFiles {
    val root = newFolder("guest")
    listOf("kernel", "firmware", "initrd").forEach { File(root, it).writeText("test-only resource") }
    return GuestFiles(root)
}
