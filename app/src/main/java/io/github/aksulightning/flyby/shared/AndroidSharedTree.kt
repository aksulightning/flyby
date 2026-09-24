package io.github.aksulightning.flyby.shared

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.Os

/** Never resolve a content URI to an Android filesystem path. */
class AndroidSharedTree(private val resolver: ContentResolver, private val tree: Uri) : SharedTree {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)
    private val known = mutableSetOf(rootId)
    private fun uri(id: String): Uri {
        require(id in known) { "Document is outside the selected tree" }
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }
    override fun root() = stat(rootId)
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
    private fun query(uri: Uri): List<SharedTree.Entry> = checkNotNull(resolver.query(uri, projection, null, null, null)) { "Folder is unavailable" }.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0); known += id
                add(SharedTree.Entry(id, cursor.getString(1), cursor.getString(2) == Document.MIME_TYPE_DIR, cursor.getLong(3), cursor.getLong(4)))
            }
        }
    }
    override fun stat(id: String) = query(uri(id)).single()
    override fun children(id: String): List<SharedTree.Entry> {
        uri(id)
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)).sortedBy { it.name }
    }
    override fun read(id: String, offset: Long, count: Int): ByteArray = checkNotNull(resolver.openInputStream(uri(id))).use { input ->
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() < 0) return byteArrayOf() else remaining--
        }
        val data = ByteArray(count); var n = 0
        while (n < count) { val read = input.read(data, n, count - n); if (read < 0) break; n += read }
        data.copyOf(n)
    }
    override fun write(id: String, offset: Long, data: ByteArray): Int {
        checkNotNull(resolver.openFileDescriptor(uri(id), "rw")).use { descriptor ->
            var written = 0
            while (written < data.size) {
                val n = Os.pwrite(descriptor.fileDescriptor, data, written, data.size - written, offset + written)
                check(n > 0) { "Cannot write document" }; written += n
            }
            Os.fsync(descriptor.fileDescriptor)
        }
        return data.size
    }
    override fun create(parent: String, name: String, directory: Boolean): SharedTree.Entry {
        NinePServer.validName(name)
        require(children(parent).none { it.name == name }) { "File exists" }
        val created = checkNotNull(DocumentsContract.createDocument(resolver, uri(parent), if (directory) Document.MIME_TYPE_DIR else "application/octet-stream", name))
        val id = DocumentsContract.getDocumentId(created); known += id
        return stat(id)
    }
    override fun remove(id: String) { require(id != rootId); check(DocumentsContract.deleteDocument(resolver, uri(id))); known -= id }
    override fun rename(id: String, name: String): SharedTree.Entry {
        require(id != rootId); NinePServer.validName(name)
        val renamed = checkNotNull(DocumentsContract.renameDocument(resolver, uri(id), name))
        val next = DocumentsContract.getDocumentId(renamed); known += next
        return stat(next)
    }
    override fun truncate(id: String, size: Long) {
        checkNotNull(resolver.openFileDescriptor(uri(id), "rw")).use { descriptor ->
            Os.ftruncate(descriptor.fileDescriptor, size)
            Os.fsync(descriptor.fileDescriptor)
        }
    }
}
