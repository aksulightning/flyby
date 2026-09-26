package io.github.aksulightning.flyby.terminal

import kotlinx.coroutines.flow.StateFlow

/** UI-facing boundary. UI knows nothing about CPU execution or JNI VM handles. */
interface TerminalSession {
    val transcript: StateFlow<String>
    val emulator: TerminalEmulator? get() = null
    suspend fun sendInput(bytes: ByteArray)
    suspend fun resize(rows: Int, cols: Int) = Unit
}
