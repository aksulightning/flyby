package io.github.aksulightning.flyby.vm

import io.github.aksulightning.flyby.terminal.TerminalBuffer
import io.github.aksulightning.flyby.terminal.TerminalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Owned outside Activity. A service must own the runtime before a controller is installed. */
class VmManager(
    private val scope: CoroutineScope,
    private val controller: VmController? = null,
    private val log: (String) -> Unit = {},
    private val shutdownTimeoutMs: Long = 30_000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onOutput: (ByteArray) -> Unit = {},
    private val onReset: () -> Unit = {},
) : TerminalSession {
    private val mutex = Mutex()
    private val current = MutableStateFlow(VmStatus())
    val status = current.asStateFlow()
    private val buffer = TerminalBuffer()
    override val transcript = buffer.transcript
    private var active: Job? = null
    private var stoppingOperation = false
    private var cleanupPending = false

    suspend fun start(config: VmConfig, files: GuestFiles): Boolean = start(config) { files }

    suspend fun start(config: VmConfig, prepare: suspend () -> GuestFiles): Boolean = mutex.withLock {
        if (active != null || stoppingOperation || cleanupPending) return@withLock false
        current.value = VmStatus(VmState.STARTING)
        buffer.clear()
        log("VM_START")
        // Enter try/finally now: Stop immediately after Start must still run cleanup.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var result = VmStatus(VmState.STOPPED)
            var attemptedStart = false
            try {
                onReset()
                val checked = withContext(ioDispatcher) { config.validateRuntime(); prepare().validated() }
                val runtime = checkNotNull(controller) {
                    "Native VM runtime is not installed"
                }
                attemptedStart = true
                runtime.start(config, checked) { bytes -> buffer.append(bytes); onOutput(bytes) }
                mutex.withLock {
                    if (current.value.state == VmState.STARTING) current.value = VmStatus(VmState.RUNNING)
                }
                val code = runtime.awaitExit()
                log("NATIVE_EXIT_CODE $code")
                result = if (code == 0 || current.value.state == VmState.STOPPING) {
                    VmStatus(VmState.STOPPED, exitCode = code)
                } else {
                    VmStatus(VmState.ERROR, "Native VM exited with code $code", code)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log("NATIVE_ERROR ${failure.message}")
                result = VmStatus(VmState.ERROR, failure.message ?: failure.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) {
                    var cleanupFailed = false
                    try {
                        if (attemptedStart) controller?.forceStop()
                    } catch (failure: Exception) {
                        cleanupFailed = true
                        result = VmStatus(VmState.ERROR, "Native cleanup failed: ${failure.message}")
                    }
                    mutex.withLock {
                        current.value = result
                        cleanupPending = cleanupFailed
                        active = null
                    }
                    log("VM_EXIT ${result.state}")
                }
            }
        }
        active = job
        true
    }

    suspend fun stop() {
        val job = mutex.withLock {
            val running = active ?: return
            if (current.value.state == VmState.STOPPING) return
            stoppingOperation = true
            val starting = current.value.state == VmState.STARTING
            current.value = VmStatus(VmState.STOPPING)
            log("VM_STOP")
            // A controller must handle cancellation during spawn without leaking a runtime.
            if (starting) running.cancel()
            running
        }
        try {
            withTimeoutOrNull(shutdownTimeoutMs) {
                if (!job.isCancelled) controller?.stop()
                job.join()
                true
            } ?: cancelAndReap(job)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { cancelAndReap(job) }
            throw cancelled
        } catch (_: Exception) {
            cancelAndReap(job)
        } finally {
            withContext(NonCancellable) { mutex.withLock { stoppingOperation = false } }
        }
    }

    suspend fun forceStop() {
        val job = mutex.withLock {
            if (active == null && cleanupPending) {
                // Do not allow a replacement VM until failed cleanup has been retried.
                try {
                    controller?.forceStop()
                    cleanupPending = false
                    current.value = VmStatus(VmState.STOPPED)
                } catch (failure: Exception) {
                    current.value = VmStatus(VmState.ERROR, "Native cleanup failed: ${failure.message}")
                }
            }
            active
        } ?: return
        cancelAndReap(job)
    }

    private suspend fun cancelAndReap(job: Job) {
        mutex.withLock {
            if (active === job) {
                current.value = VmStatus(VmState.STOPPING)
                job.cancel()
            }
        }
        job.join() // run's finally performs forceStop and reaps before publishing STOPPED.
    }

    override suspend fun resize(rows: Int, cols: Int) {
        if (current.value.state == VmState.RUNNING) controller?.resize(rows, cols)
    }

    override suspend fun sendInput(bytes: ByteArray) {
        check(current.value.state == VmState.RUNNING) { "VM is not running" }
        checkNotNull(controller).sendInput(bytes.copyOf())
    }
}
