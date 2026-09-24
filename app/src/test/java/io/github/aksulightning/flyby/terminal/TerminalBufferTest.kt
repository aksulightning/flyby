package io.github.aksulightning.flyby.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {
    @Test fun splitUtf8IsPreserved() {
        val buffer = TerminalBuffer()
        "Hei ä 🐧".toByteArray().forEach { buffer.append(byteArrayOf(it)) }
        assertEquals("Hei ä 🐧", buffer.transcript.value)
    }
    @Test fun transcriptIsBoundedAndCanBeReset() {
        val buffer = TerminalBuffer(5)
        buffer.append("12345678".toByteArray())
        assertEquals("45678", buffer.transcript.value)
        buffer.append(byteArrayOf(0xc3.toByte()))
        buffer.clear()
        buffer.append("new".toByteArray())
        assertEquals("new", buffer.transcript.value)
    }
}
