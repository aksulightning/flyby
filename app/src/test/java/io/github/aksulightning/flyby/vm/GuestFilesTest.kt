package io.github.aksulightning.flyby.vm

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GuestFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun validGuestIsCanonical() {
        val files = temporary.guestFiles()
        assertEquals(files.directory.canonicalFile, files.validated().directory)
    }
    @Test fun missingAndEmptyResourcesAreRejected() {
        val files = temporary.guestFiles()
        for (name in listOf("kernel", "firmware", "initrd")) {
            val resource = File(files.directory, name)
            resource.delete()
            assertThrows(IllegalArgumentException::class.java) { files.validated() }
            resource.writeText("")
            assertThrows(IllegalArgumentException::class.java) { files.validated() }
            resource.writeText("fixture")
        }
    }
    @Test fun resourceSymlinkCannotEscapeDirectory() {
        val files = temporary.guestFiles()
        val outside = temporary.newFile("outside").apply { writeText("outside") }
        val kernel = File(files.directory, "kernel")
        kernel.delete(); Files.createSymbolicLink(kernel.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { files.validated() }
    }
    @Test fun directoriesAndInvalidPathCharactersAreRejected() {
        val files = temporary.guestFiles()
        File(files.directory, "initrd").apply { delete(); mkdir() }
        assertThrows(IllegalArgumentException::class.java) { files.validated() }
        listOf("missing", "bad\nname", "bad\rname", "bad\u0000name").forEach {
            assertThrows(IllegalArgumentException::class.java) { GuestFiles(File(temporary.root, it)).validated() }
        }
    }
}
