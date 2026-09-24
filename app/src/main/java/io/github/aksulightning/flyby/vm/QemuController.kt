package io.github.aksulightning.flyby.vm

/**
 * Phase 2 process boundary. There is deliberately no fake production implementation.
 *
 * start must return only after spawning the process, and must clean up if cancelled.
 * It must drain stdout AND stderr concurrently off the main thread. stdout chunks
 * feed onOutput; stderr goes to QEMU_STDERR diagnostics, never to a host shell.
 * awaitExit suspends without polling and returns only after reaping the child.
 * stop requests guest shutdown; forceStop terminates and reaps the child. Both
 * are idempotent, including after failed/partially completed start.
 * sendInput writes only to the guest's serial stream and never logs its content.
 */
interface QemuController {
    suspend fun start(arguments: List<String>, onOutput: (ByteArray) -> Unit)
    suspend fun awaitExit(): Int
    suspend fun stop()
    suspend fun forceStop()
    suspend fun sendInput(bytes: ByteArray)
}
