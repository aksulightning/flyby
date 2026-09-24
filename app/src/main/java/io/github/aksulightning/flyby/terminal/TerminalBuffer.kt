package io.github.aksulightning.flyby.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bounded diagnostic transcript, not an ANSI emulator. Handles split UTF-8 reads. */
class TerminalBuffer(private val capacity: Int = 32_768) {
    init { require(capacity > 0) }
    private val text = MutableStateFlow("")
    val transcript = text.asStateFlow()
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()

    @Synchronized
    fun append(bytes: ByteArray) {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        text.value = (text.value + output.toString()).takeLast(capacity)
    }

    @Synchronized
    fun clear() {
        decoder.reset()
        pending = byteArrayOf()
        text.value = ""
    }
}
