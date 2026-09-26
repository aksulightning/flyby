package io.github.aksulightning.flyby.terminal

import io.github.aksulightning.flyby.nativebridge.NativeBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screen state survives UI replacement. libvterm owns ANSI parsing, colors and cursor state. */
class TerminalEmulator : AutoCloseable {
    private var id = NativeBridge.createTerminal()
    private val changed = MutableStateFlow(0L)
    val revision = changed.asStateFlow()
    @Synchronized fun append(data: ByteArray): ByteArray {
        if (id == 0L) return byteArrayOf()
        val reply = NativeBridge.feedTerminal(id, data)
        changed.value++
        return reply
    }
    @Synchronized fun reset() { check(id != 0L); NativeBridge.resetTerminal(id); changed.value++ }
    @Synchronized fun frame(offset: Int): IntArray = if (id == 0L) intArrayOf() else NativeBridge.frameTerminal(id, offset)
    @Synchronized fun resize(rows: Int, cols: Int) { if (id != 0L) { NativeBridge.resizeTerminal(id, rows, cols); changed.value++ } }
    @Synchronized fun key(key: Int, modifiers: Int): ByteArray = if (id == 0L) byteArrayOf() else NativeBridge.keyTerminal(id, key, modifiers)
    @Synchronized fun text(text: String, paste: Boolean = false): ByteArray = if (id == 0L) byteArrayOf() else NativeBridge.textTerminal(id, text.toByteArray(), paste)
    @Synchronized override fun close() { if (id != 0L) { NativeBridge.destroyTerminal(id); id = 0 } }
}
