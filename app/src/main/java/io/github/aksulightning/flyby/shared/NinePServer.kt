package io.github.aksulightning.flyby.shared

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small 9P2000 server. The only namespace is the user-selected document tree. */
interface SharedTree {
    data class Entry(val id: String, val name: String, val directory: Boolean, val size: Long = 0, val modified: Long = 0)
    fun root(): Entry
    fun stat(id: String): Entry
    fun children(id: String): List<Entry>
    fun read(id: String, offset: Long, count: Int): ByteArray
    fun write(id: String, offset: Long, data: ByteArray): Int
    fun create(parent: String, name: String, directory: Boolean): Entry
    fun remove(id: String)
    fun rename(id: String, name: String): Entry
    fun truncate(id: String, size: Long)
}

class NinePServer(private val tree: SharedTree) {
    private data class Fid(var entry: SharedTree.Entry, val parents: List<SharedTree.Entry>, var mode: Int? = null, var listing: List<ByteArray>? = null)
    private val fids = mutableMapOf<Int, Fid>()
    private val qids = mutableMapOf<String, Long>()
    private var msize = MAX_MESSAGE
    private var pending = byteArrayOf()

    /** Requests may span arbitrary UART reads. Responses never exceed the negotiated msize. */
    fun receive(bytes: ByteArray, reply: (ByteArray) -> Unit) {
        require(pending.size + bytes.size <= MAX_MESSAGE * 3) { "Shared channel overflow" }
        pending += bytes
        while (pending.size >= 4) {
            val size = ByteBuffer.wrap(pending).order(ByteOrder.LITTLE_ENDIAN).int
            require(size in 7..msize) { "Invalid 9P message size" }
            if (pending.size < size) return
            val request = pending.copyOfRange(0, size)
            pending = pending.copyOfRange(size, pending.size)
            reply(handle(request))
        }
    }

    private fun handle(bytes: ByteArray): ByteArray {
        val input = Reader(bytes.copyOfRange(4, bytes.size))
        val type = input.u8(); val tag = input.u16()
        val out = Writer()
        try {
            when (type) {
                100 -> { // version
                    val requested = input.i32(); val version = input.string()
                    require(requested >= 256) { "9P msize too small" }
                    msize = minOf(MAX_MESSAGE, requested); fids.clear()
                    out.i32(msize); out.string(if (version.startsWith("9P2000")) "9P2000" else "unknown")
                }
                102 -> error("Authentication not required on the private VM channel")
                104 -> { // attach
                    val id = input.i32(); input.i32(); input.string(); val name = input.string()
                    require(name.isEmpty() || name == "shared") { "Unknown share" }
                    require(id !in fids && fids.size < MAX_FIDS) { "Invalid fid" }
                    val root = tree.root(); fids[id] = Fid(root, emptyList()); qid(out, root)
                }
                108 -> { input.u16() } // synchronous requests have nothing to flush
                110 -> { // walk; even '..' cannot leave the tree root
                    val id = input.i32(); val next = input.i32(); val count = input.u16()
                    require(count <= 16) { "Too many path components" }
                    val source = fid(id)
                    require(source.mode == null && (id == next || next !in fids)) { "Invalid walk fid" }
                    require(next in fids || fids.size < MAX_FIDS) { "Too many open files" }
                    var entry = source.entry; val parents = source.parents.toMutableList()
                    val walked = mutableListOf<SharedTree.Entry>()
                    repeat(count) {
                        val name = input.string()
                        if (walked.size != it) return@repeat
                        require(entry.directory) { "Not a directory" }
                        when (name) {
                            "." -> Unit
                            ".." -> if (parents.isNotEmpty()) entry = parents.removeAt(parents.lastIndex)
                            else -> {
                                validName(name)
                                val child = tree.children(entry.id).firstOrNull { e -> e.name == name }
                                if (child == null) return@repeat
                                parents += entry; entry = child
                            }
                        }
                        walked += entry
                    }
                    require(count == 0 || walked.isNotEmpty()) { "File not found" }
                    if (walked.size == count) fids[next] = Fid(entry, parents)
                    out.u16(walked.size); walked.forEach { qid(out, it) }
                }
                112 -> { // open
                    val f = fid(input.i32()); val mode = input.u8()
                    require(f.mode == null && mode and 0x40 == 0) { "Unsupported open mode" }
                    require(!f.entry.directory || mode == 0) { "Directory is read-only" }
                    if (mode and 16 != 0) { require(mode and 3 in 1..2); tree.truncate(f.entry.id, 0) }
                    f.mode = mode; qid(out, tree.stat(f.entry.id)); out.i32(msize - 24)
                }
                114 -> { // create file/directory, replacing the directory fid
                    val id = input.i32(); val name = input.string(); val perm = input.i32(); val mode = input.u8()
                    validName(name)
                    val f = fid(id); require(f.entry.directory && f.mode == null) { "Not an unopened directory" }
                    require(perm and 0x7ffffe00 == 0 && mode and 0x40 == 0) { "Special files are unsupported" }
                    val directory = perm < 0
                    require(!directory || mode == 0) { "Invalid directory mode" }
                    val entry = tree.create(f.entry.id, name, directory)
                    fids[id] = Fid(entry, f.parents + f.entry, mode)
                    qid(out, entry); out.i32(msize - 24)
                }
                116 -> { // read
                    val f = fid(input.i32()); val offset = input.i64(); val requested = input.i32()
                    require(offset >= 0 && requested >= 0 && f.mode != null && f.mode!! and 3 != 1) { "Invalid read" }
                    val count = minOf(requested, msize - 11)
                    val data = if (f.entry.directory) {
                        if (offset == 0L || f.listing == null) f.listing = tree.children(f.entry.id).map(::stat)
                        val result = ByteArrayOutputStream(); var position = 0L
                        for (record in f.listing!!) {
                            if (position >= offset) {
                                require(position == offset + result.size()) { "Invalid directory offset" }
                                if (result.size() + record.size > count) break
                                result.write(record)
                            }
                            position += record.size
                        }
                        result.toByteArray()
                    } else tree.read(f.entry.id, offset, count)
                    require(data.size <= count); out.i32(data.size); out.bytes(data)
                }
                118 -> { // write
                    val f = fid(input.i32()); val offset = input.i64(); val count = input.i32()
                    require(offset >= 0 && count in 0..msize && !f.entry.directory && f.mode != null && f.mode!! and 3 in 1..2) { "Invalid write" }
                    out.i32(tree.write(f.entry.id, offset, input.bytes(count)))
                }
                120 -> { require(fids.remove(input.i32()) != null) { "Unknown fid" } }
                122 -> {
                    val f = fids.remove(input.i32()) ?: error("Unknown fid")
                    require(f.parents.isNotEmpty()) { "Cannot remove share root" }; tree.remove(f.entry.id)
                }
                124 -> { val record = stat(tree.stat(fid(input.i32()).entry.id)); out.u16(record.size); out.bytes(record) }
                126 -> { // wstat: name and length are the document operations SAF can represent
                    val f = fid(input.i32()); input.u16(); input.u16(); input.u16(); input.i32(); input.bytes(13)
                    val mode = input.i32(); input.i32(); input.i32(); val size = input.i64()
                    val name = input.string(); val uid = input.string(); val gid = input.string(); input.string()
                    require(uid.isEmpty() && gid.isEmpty()) { "Document ownership cannot be changed" }
                    require(mode == -1 || (mode < 0) == f.entry.directory) { "File type cannot be changed" }
                    if (size != -1L) { require(size >= 0 && !f.entry.directory); tree.truncate(f.entry.id, size) }
                    if (name.isNotEmpty() && name != f.entry.name) {
                        require(f.parents.isNotEmpty()) { "Cannot rename share root" }; validName(name)
                        val oldId = f.entry.id
                        val renamed = tree.rename(oldId, name)
                        qids[oldId]?.let { qids[renamed.id] = it }
                        fids.values.filter { it.entry.id == oldId }.forEach { it.entry = renamed }
                    }
                }
                else -> error("Unsupported 9P operation")
            }
            return packet(type + 1, tag, out.data())
        } catch (_: Exception) {
            // Provider exceptions may contain host paths or private document identifiers.
            val failure = Writer().apply { string("Shared folder operation failed; check folder access and file permissions") }
            return packet(107, tag, failure.data())
        }
    }
    private fun fid(id: Int) = fids[id] ?: error("Unknown fid")
    private fun qid(out: Writer, entry: SharedTree.Entry) {
        out.u8(if (entry.directory) 0x80 else 0); out.i32(0)
        out.i64(qids.getOrPut(entry.id) { qids.size.toLong() + 1 })
    }
    private fun stat(entry: SharedTree.Entry): ByteArray {
        val body = Writer().apply {
            u16(0); i32(0); qid(this, entry)
            i32((if (entry.directory) Int.MIN_VALUE else 0) or 0x1ff)
            i32((entry.modified / 1000).toInt()); i32((entry.modified / 1000).toInt()); i64(if (entry.directory) 0 else entry.size)
            string(entry.name); string("root"); string("root"); string("")
        }.data()
        return Writer().apply { u16(body.size); bytes(body) }.data()
    }
    private fun packet(type: Int, tag: Int, data: ByteArray) = Writer().apply {
        require(data.size + 7 <= msize); i32(data.size + 7); u8(type); u16(tag); bytes(data)
    }.data()
    private class Reader(data: ByteArray) {
        private val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun u8() = b.get().toInt() and 255
        fun u16() = b.short.toInt() and 65535
        fun i32() = b.int
        fun i64() = b.long
        fun bytes(n: Int): ByteArray { require(n in 0..b.remaining()); return ByteArray(n).also { b.get(it) } }
        fun string() = bytes(u16()).toString(Charsets.UTF_8)
    }
    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(n: Int) { out.write(n) }
        fun u16(n: Int) { u8(n); u8(n ushr 8) }
        fun i32(n: Int) { u16(n); u16(n ushr 16) }
        fun i64(n: Long) { i32(n.toInt()); i32((n ushr 32).toInt()) }
        fun string(s: String) { val b = s.toByteArray(); require(b.size <= 4096); u16(b.size); bytes(b) }
        fun bytes(b: ByteArray) { out.write(b) }
        fun data() = out.toByteArray()
    }
    companion object {
        const val MAX_MESSAGE = 8192
        private const val MAX_FIDS = 1024
        fun validName(name: String) { require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' } && name.toByteArray().size <= 255) { "Invalid document name" } }
    }
}
