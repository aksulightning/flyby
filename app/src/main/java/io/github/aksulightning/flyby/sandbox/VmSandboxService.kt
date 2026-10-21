package io.github.aksulightning.flyby.sandbox

import android.app.Service
import android.content.Intent
import android.os.*
import io.github.aksulightning.flyby.nativebridge.NativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the interpreter and renderer under Android's isolated UID. */
class VmSandboxService : Service() {
    private var handle = 0L
    private var resources = emptyArray<ParcelFileDescriptor>()
    private val frameBusy = AtomicBoolean()
    private val frames = Executors.newSingleThreadExecutor()
    private val binder = object : IVmSandbox.Stub() {
        override fun identity() = intArrayOf(Process.myPid(), Process.myUid())
        override fun canReadPrivateFile(path: String) = File(path).canRead()
        @Synchronized override fun createVm(files: Array<ParcelFileDescriptor>, ram: Int, cpus: Int, system: Boolean, shared: Boolean, gpu: Boolean): Long {
            require(handle == 0L && files.size == 4)
            require(ram in 128..768 && cpus in 1..2)
            resources = files
            handle = NativeBridge.createVmFds(files[0].fd, files[1].fd, files[2].fd, files[3].fd,
                ram, cpus, system, shared, gpu)
            return handle
        }
        override fun startVm(id: Long) = NativeBridge.startVm(id)
        override fun runningVm(id: Long) = NativeBridge.runningVm(id)
        override fun requestStopVm(id: Long) = NativeBridge.requestStopVm(id)
        override fun inputVm(id: Long, data: ByteArray) { require(data.size <= 4096); NativeBridge.inputVm(id, data) }
        override fun readVm(id: Long, timeoutMs: Int): ByteArray { require(timeoutMs in 0..100); return NativeBridge.readVm(id, timeoutMs) }
        override fun sharedInputVm(id: Long, data: ByteArray) { require(data.size <= 65536); NativeBridge.sharedInputVm(id, data) }
        override fun sharedOutputVm(id: Long) = NativeBridge.sharedOutputVm(id)
        override fun resizeVm(id: Long, rows: Int, cols: Int) = NativeBridge.resizeVm(id, rows, cols)
        override fun displayInputVm(id: Long, reports: ByteArray): Boolean {
            require(reports.size in 16..4096 && reports.size % 16 == 0)
            return NativeBridge.displayInputVm(id, reports)
        }
        override fun networkOutputVm(id: Long) = NativeBridge.networkOutputVm(id)
        override fun networkInputVm(id: Long, data: ByteArray) {
            require(data.size in 14..1514); NativeBridge.networkInputVm(id, data)
        }
        override fun displayFrame(id: Long): ParcelFileDescriptor? {
            if (!frameBusy.compareAndSet(false, true)) return null
            try {
                val pixels = IntArray(800 * 600)
                if (!NativeBridge.displayFrameVm(id, pixels)) { frameBusy.set(false); return null }
                val pipe = ParcelFileDescriptor.createPipe()
                frames.execute {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { stream ->
                            val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.asIntBuffer().put(pixels)
                            stream.write(bytes.array())
                        }
                    } catch (_: java.io.IOException) {
                        // A disappearing Activity/client closes its read endpoint.
                    } finally { frameBusy.set(false) }
                }
                return pipe[0]
            } catch (failure: Exception) { frameBusy.set(false); throw failure }
        }
        override fun terminate() { Process.killProcess(Process.myPid()) }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
    override fun onDestroy() {
        frames.shutdownNow()
        resources.forEach { runCatching { it.close() } }
        super.onDestroy()
    }
}
