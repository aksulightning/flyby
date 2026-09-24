package io.github.aksulightning.flyby.shared

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class NinePServerTest {
    private class Tree : SharedTree {
        val entries = mutableMapOf("root" to SharedTree.Entry("root", "shared", true), "hello" to SharedTree.Entry("hello", "hello", false, 5))
        val contents = mutableMapOf("hello" to "world".toByteArray())
        override fun root() = entries.getValue("root")
        override fun stat(id: String) = entries.getValue(id)
        override fun children(id: String) = entries.values.filter { it.id != "root" }
        override fun read(id: String, offset: Long, count: Int) = contents.getValue(id).let { it.copyOfRange(offset.toInt().coerceAtMost(it.size), minOf(it.size, offset.toInt() + count)) }
        override fun write(id: String, offset: Long, data: ByteArray): Int { contents[id] = data; return data.size }
        override fun create(parent: String, name: String, directory: Boolean) = SharedTree.Entry(name, name, directory).also { entries[name] = it; contents[name] = byteArrayOf() }
        override fun remove(id: String) { entries.remove(id); contents.remove(id) }
        override fun rename(id: String, name: String) = entries.getValue(id).copy(name = name).also { entries[id] = it }
        override fun truncate(id: String, size: Long) { contents[id] = contents.getValue(id).copyOf(size.toInt()) }
    }
    private class Message {
        val out = ByteArrayOutputStream()
        fun b(n: Int) { out.write(n) }
        fun s(n: Int) { b(n); b(n ushr 8) }
        fun i(n: Int) { s(n); s(n ushr 16) }
        fun l(n: Long) { i(n.toInt()); i((n ushr 32).toInt()) }
        fun str(s: String) { val data = s.toByteArray(); this.s(data.size); out.write(data) }
        fun bytes(b: ByteArray) { out.write(b) }
    }
    private fun request(type: Int, body: Message.() -> Unit = {}): ByteArray {
        val data = Message().apply(body).out.toByteArray()
        return Message().apply { i(data.size + 7); b(type); s(1); bytes(data) }.out.toByteArray()
    }
    private fun exchange(server: NinePServer, type: Int, body: Message.() -> Unit = {}): ByteArray {
        var result = byteArrayOf(); server.receive(request(type, body)) { result = it }
        assertTrue(result.size >= 7); return result
    }
    private fun attach(server: NinePServer) { assertEquals(105, exchange(server, 104) { i(1); i(-1); str("root"); str("") }[4].toInt() and 255) }
    @Test fun fragmentedPacketsNegotiateAndReadFile() {
        val server = NinePServer(Tree())
        val version = request(100) { i(8192); str("9P2000.L") }
        val replies = mutableListOf<ByteArray>()
        version.forEach { byte -> server.receive(byteArrayOf(byte)) { replies += it } }
        assertEquals(1, replies.size); assertEquals(101, replies.single()[4].toInt())
        assertTrue(replies.single().toString(Charsets.UTF_8).contains("9P2000"))
        attach(server)
        assertEquals(111, exchange(server, 110) { i(1); i(2); s(1); str("hello") }[4].toInt())
        assertEquals(113, exchange(server, 112) { i(2); b(0) }[4].toInt())
        val read = exchange(server, 116) { i(2); l(0); i(5) }
        assertEquals("world", read.copyOfRange(11, read.size).toString(Charsets.UTF_8))
        assertEquals(107, exchange(server, 118) { i(2); l(0); i(1); b(1) }[4].toInt())
    }
    @Test fun createWriteAndRemoveUseOnlyAttachedNamespace() {
        val tree = Tree(); val server = NinePServer(tree); attach(server)
        exchange(server, 110) { i(1); i(2); s(0) }
        assertEquals(115, exchange(server, 114) { i(2); str("new"); i(0x1a4); b(2) }[4].toInt())
        assertEquals(119, exchange(server, 118) { i(2); l(0); i(3); bytes("abc".toByteArray()) }[4].toInt())
        assertArrayEquals("abc".toByteArray(), tree.contents["new"])
        assertEquals(123, exchange(server, 122) { i(2) }[4].toInt())
        assertFalse("new" in tree.entries)
        assertEquals(107, exchange(server, 122) { i(1) }[4].toInt())
        assertTrue("root" in tree.entries)
    }
    @Test fun missingFileReturnsTheErrnoStringRequiredByLinuxCreate() {
        val server = NinePServer(Tree()); attach(server)
        val error = exchange(server, 110) { i(1); i(2); s(1); str("does-not-exist") }
        assertEquals(107, error[4].toInt())
        assertEquals("No such file or directory", error.copyOfRange(9, error.size).toString(Charsets.UTF_8))
    }
    @Test fun traversalAndInvalidFrameAreRejected() {
        val server = NinePServer(Tree()); attach(server)
        assertEquals(111, exchange(server, 110) { i(1); i(2); s(2); str(".."); str("..") }[4].toInt())
        assertEquals(107, exchange(server, 114) { i(2); str("../escape"); i(0x1a4); b(2) }[4].toInt())
        assertEquals(107, exchange(server, 114) { i(2); str("/absolute"); i(0x1a4); b(2) }[4].toInt())
        assertThrows(IllegalArgumentException::class.java) { server.receive(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MAX_VALUE).array()) {} }
    }
    @Test fun directoryReadsKeepCompleteStatRecords() {
        val server = NinePServer(Tree()); attach(server)
        exchange(server, 112) { i(1); b(0) }
        val result = exchange(server, 116) { i(1); l(0); i(8192) }
        assertEquals(117, result[4].toInt())
        val length = ByteBuffer.wrap(result, 7, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val record = ByteBuffer.wrap(result, 11, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        assertEquals(record + 2, length)
        val end = exchange(server, 116) { i(1); l(length.toLong()); i(8192) }
        assertEquals(11, end.size)
    }
}
