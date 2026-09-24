package io.github.aksulightning.flyby

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Disposable document provider installed only with the instrumentation APK. */
class SharedTestProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).filesDir, "shared-test").apply { mkdirs() }.canonicalFile
    private fun file(id: String): File {
        val f = if (id == "root") root else File(root, id.removePrefix("root/")).canonicalFile
        require(id == "root" || id.startsWith("root/"))
        require(f.toPath().startsWith(root.toPath()))
        return f
    }
    private fun id(f: File) = if (f == root) "root" else "root/" + f.relativeTo(root).path
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf("root_id"))
    private fun rows(files: List<File>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
        return MatrixCursor(columns).apply { files.forEach { f -> addRow(columns.map { column -> when (column) {
            Document.COLUMN_DOCUMENT_ID -> id(f)
            Document.COLUMN_DISPLAY_NAME -> f.name
            Document.COLUMN_MIME_TYPE -> if (f.isDirectory) Document.MIME_TYPE_DIR else "application/octet-stream"
            Document.COLUMN_SIZE -> f.length()
            Document.COLUMN_LAST_MODIFIED -> f.lastModified()
            Document.COLUMN_FLAGS -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or (if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else 0)
            else -> null
        } }.toTypedArray()) } }
    }
    override fun queryDocument(documentId: String, projection: Array<out String>?) = rows(listOf(file(documentId)), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?) = rows(file(parentDocumentId).listFiles()?.toList().orEmpty(), projection)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?) = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        require(displayName != "." && displayName != ".." && '/' !in displayName)
        val f = File(file(parentDocumentId), displayName)
        check(if (mimeType == Document.MIME_TYPE_DIR) f.mkdir() else f.createNewFile())
        return id(f)
    }
    override fun deleteDocument(documentId: String) { require(documentId != "root"); check(file(documentId).delete()) }
    override fun renameDocument(documentId: String, displayName: String): String {
        require(documentId != "root" && displayName != "." && displayName != ".." && '/' !in displayName)
        val f = file(documentId); val next = File(f.parentFile, displayName)
        check(f.renameTo(next)); return id(next)
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String) = file(documentId).toPath().startsWith(file(parentDocumentId).toPath())
}
