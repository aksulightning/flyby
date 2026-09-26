package io.github.aksulightning.flyby.vm

/** Service-owned runtime; all potentially blocking work runs off the Android main thread. */
interface VmController {
    suspend fun start(config: VmConfig, files: GuestFiles, onOutput: (ByteArray) -> Unit)
    suspend fun awaitExit(): Int
    suspend fun stop()
    suspend fun forceStop()
    suspend fun sendInput(bytes: ByteArray)
    suspend fun resize(rows: Int, cols: Int) = Unit
    suspend fun displayFrame(pixels: IntArray): Boolean = false
    suspend fun displayInput(reports: ByteArray) = Unit
}
