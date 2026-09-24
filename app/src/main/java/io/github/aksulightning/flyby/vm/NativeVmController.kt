package io.github.aksulightning.flyby.vm

import io.github.aksulightning.flyby.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class NativeVmController(private val log: (String) -> Unit) : VmController {
    private val kernelPanic = Regex("(?:^|[\\r\\n])\\[\\s*[0-9]+\\.[0-9]+] Kernel panic - not syncing:")
    private val mutex = Mutex()
    private var handle = 0L
    private var output: (ByteArray) -> Unit = {}

    override suspend fun start(config: VmConfig, files: GuestFiles, onOutput: (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(handle == 0L) { "VM already running" }
            config.validateRuntime()
            DiskImage.validate(files.directory)
            log("VM_CREATE")
            try {
                handle = NativeBridge.createVm(files.validated().directory.path, config.memoryMiB, config.cpuCount)
                check(handle != 0L) { "Native initialization failed" }
                output = onOutput
                NativeBridge.startVm(handle)
                log("CONSOLE_CONNECTED")
            } catch (failure: LinkageError) {
                throw IllegalStateException("Native ARM64 library could not be loaded", failure)
            }
        }
    }

    override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
        val id = mutex.withLock { check(handle != 0L); handle }
        var tail = ""
        var booted = false
        val bootDeadline = System.nanoTime() + 300_000_000_000L
        while (NativeBridge.runningVm(id)) {
            currentCoroutineContext().ensureActive()
            val first = NativeBridge.readVm(id, 50)
            if (first.isNotEmpty()) {
                // Coalesce bursts to at most ~60 terminal updates/second.
                delay(16)
                val batch = ByteArrayOutputStream().apply {
                    write(first)
                    while (size() < 65536) {
                        val next = NativeBridge.readVm(id, 0)
                        if (next.isEmpty()) break
                        write(next)
                    }
                }.toByteArray()
                output(batch)
                tail = (tail + batch.toString(Charsets.UTF_8)).takeLast(8192)
                if (!booted) {
                    check("FLYBY_STORAGE_ERROR" !in tail) { "Persistent disk could not be mounted. See Terminal; disk was not reformatted." }
                    if ("FLYBY_ALPINE_READY" in tail) { booted = true; log("LINUX_BOOT Alpine shell ready") }
                }
                // Do not treat ordinary shell text mentioning a kernel panic as a fatal event.
                check(!kernelPanic.containsMatchIn(tail)) {
                    "Linux kernel panic. See Terminal for diagnostics."
                }
            }
            check(booted || System.nanoTime() < bootDeadline) { "Linux did not reach its shell within 5 minutes. See Terminal." }
        }
        while (true) {
            val bytes = NativeBridge.readVm(id, 0)
            if (bytes.isEmpty()) break
            output(bytes)
        }
        0
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        mutex.withLock { if (handle != 0L) NativeBridge.requestStopVm(handle) }
    }
    override suspend fun forceStop() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (handle != 0L) { NativeBridge.destroyVm(handle); handle = 0 }
        }
    }
    override suspend fun sendInput(bytes: ByteArray) = withContext(Dispatchers.IO) {
        mutex.withLock { check(handle != 0L) { "VM stopped" }; NativeBridge.inputVm(handle, bytes) }
    }
    override suspend fun resize(rows: Int, cols: Int) = withContext(Dispatchers.IO) {
        mutex.withLock { if (handle != 0L) NativeBridge.resizeVm(handle, rows, cols) }
    }
}
