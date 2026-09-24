package io.github.aksulightning.flyby.vm

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object GuestResources {
    suspend fun prepare(context: Context): GuestFiles = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "vm/default").apply { check(mkdirs() || isDirectory) }
        check(root.canonicalFile.toPath().startsWith(context.filesDir.canonicalFile.toPath())) { "Guest directory escapes app storage" }
        check(root.canonicalFile.parentFile == File(context.filesDir, "vm").canonicalFile) { "Invalid private guest directory" }
        val manifest = JSONObject(context.assets.open("vm/manifest.json").bufferedReader().use { it.readText() })
        for (name in listOf("kernel", "firmware", "initrd")) {
            val dest = File(root, name)
            check(dest.canonicalFile.parentFile == root.canonicalFile) { "Invalid guest path" }
            val expected = manifest.getString(name)
            if (!dest.isFile || sha256(dest) != expected) {
                val pending = File(root, "$name.pending")
                check(pending.canonicalFile.parentFile == root.canonicalFile) { "Invalid temporary guest path" }
                try {
                    context.assets.open("vm/$name").use { input -> pending.outputStream().use { input.copyTo(it) } }
                    check(sha256(pending) == expected) { "Guest resource checksum mismatch: $name" }
                    check(pending.renameTo(dest)) { "Cannot install guest resource: $name" }
                } finally { pending.delete() }
            }
        }
        DiskImage.prepare(root, manifest.getString("disk.raw")) { context.assets.open("vm/disk.seed") }
        GuestFiles(root).validated()
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65536)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
