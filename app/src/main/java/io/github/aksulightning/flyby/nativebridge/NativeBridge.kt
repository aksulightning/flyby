package io.github.aksulightning.flyby.nativebridge

/** Opaque checked IDs, never raw pointers. Native registry holds shared ownership per call. */
object NativeBridge {
    init { System.loadLibrary("flyby") }
    external fun createVm(directory: String, memoryMiB: Int, cpus: Int, fullSystem: Boolean = false, sharedFolder: Boolean = false): Long
    external fun startVm(id: Long)
    external fun runningVm(id: Long): Boolean
    external fun requestStopVm(id: Long)
    external fun destroyVm(id: Long)
    external fun inputVm(id: Long, data: ByteArray)
    external fun readVm(id: Long, timeoutMs: Int): ByteArray
    external fun sharedInputVm(id: Long, data: ByteArray)
    external fun sharedOutputVm(id: Long): ByteArray
    external fun resizeVm(id: Long, rows: Int, cols: Int)
    external fun displayFrameVm(id: Long, pixels: IntArray): Boolean
    external fun displayInputVm(id: Long, reports: ByteArray): Boolean
    external fun createTerminal(): Long
    external fun destroyTerminal(id: Long)
    external fun resetTerminal(id: Long)
    external fun resizeTerminal(id: Long, rows: Int, cols: Int)
    external fun feedTerminal(id: Long, data: ByteArray): ByteArray
    external fun keyTerminal(id: Long, key: Int, modifiers: Int): ByteArray
    external fun textTerminal(id: Long, text: ByteArray, paste: Boolean): ByteArray
    external fun frameTerminal(id: Long, offset: Int): IntArray
}
