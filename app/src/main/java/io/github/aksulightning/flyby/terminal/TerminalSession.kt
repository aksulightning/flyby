package io.github.aksulightning.flyby.terminal

import kotlinx.coroutines.flow.StateFlow

/** UI-facing boundary; a terminal emulator can later consume decoded screen state here. */
interface TerminalSession {
    val transcript: StateFlow<String>
    suspend fun sendInput(bytes: ByteArray)
}
