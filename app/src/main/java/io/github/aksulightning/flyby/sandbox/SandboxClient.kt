package io.github.aksulightning.flyby.sandbox

import android.content.*
import android.os.*
import io.github.aksulightning.flyby.nativebridge.NativeBridge
import io.github.aksulightning.flyby.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Owner-side broker. No runtime fallback is allowed if isolation fails. */
class SandboxClient(context: Context, private val log: (String) -> Unit) {
    private val context = context.applicationContext
    @Volatile private var remote: IVmSandbox? = null
    private var connection: ServiceConnection? = null
    private var broker = 0L
    @Volatile private var death: CompletableDeferred<Unit>? = null
    private val closeMutex = kotlinx.coroutines.sync.Mutex()
    private fun runtime() = checkNotNull(remote) { "VM sandbox disconnected" }

    suspend fun createVm(files: GuestFiles, ram: Int, cpus: Int, shared: Boolean, gpu: Boolean, vulkan: Boolean): Long {
        check(connection == null)
        val connected = CompletableDeferred<IVmSandbox>()
        val link = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (connection !== this) return
                val api = IVmSandbox.Stub.asInterface(service)
                val died = CompletableDeferred<Unit>()
                death = died
                try { service.linkToDeath({ died.complete(Unit) }, 0) }
                catch (failure: RemoteException) {
                    died.complete(Unit); connected.completeExceptionally(failure); return
                }
                remote = api
                connected.complete(api)
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
            override fun onBindingDied(name: ComponentName) { remote = null; connected.completeExceptionally(IllegalStateException("VM sandbox died")) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("VM sandbox rejected binding")) }
        }
        connection = link
        try {
            withContext(Dispatchers.Main) {
                check(context.bindService(Intent(context, VmSandboxService::class.java), link,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) { "Cannot bind VM sandbox" }
            }
            val api = withTimeout(15_000) { connected.await() }
            val identity = api.identity()
            check(identity.size == 2 && identity[0] != Process.myPid() && identity[1] != Process.myUid()) {
                "VM sandbox did not receive an isolated process and UID"
            }
            val validated = files.validated()
            check(!api.canReadPrivateFile(File(validated.directory, "kernel").path)) {
                "VM sandbox unexpectedly has access to private application files"
            }
            check(!api.canOpenInternetSocket()) { "VM sandbox unexpectedly has internet socket access" }
            log("SANDBOX_BOUND pid=${identity[0]} uid=${identity[1]} private_files=denied")
            val opened = mutableListOf<ParcelFileDescriptor>()
            try {
                for (name in listOf("kernel", "firmware", "initrd"))
                    opened += ParcelFileDescriptor.open(File(validated.directory, name), ParcelFileDescriptor.MODE_READ_ONLY)
                val disk = DiskImage.validate(validated.directory, validated.mode)
                opened += ParcelFileDescriptor.open(disk, ParcelFileDescriptor.MODE_READ_WRITE)
                if (vulkan) {
                    val support = api.vulkanSupport()
                    log("VULKAN_PROBE $support")
                    check(support.startsWith("available:")) { "Vulkan $support" }
                }
                val id = api.createVm(opened.toTypedArray(), ram, cpus, validated.mode == DiskMode.SYSTEM, shared, gpu, vulkan)
                check(id != 0L)
                broker = NativeBridge.createNetworkBroker()
                return id
            } finally { opened.forEach { it.close() } }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }
    suspend fun close(): Unit = closeMutex.withLock {
        val api = remote
        val died = death
        remote = null
        try { api?.terminate() } catch (_: RemoteException) { died?.complete(Unit) }
        finally {
            val link = connection
            connection = null
            if (link != null) withContext(NonCancellable + Dispatchers.Main) {
                runCatching { context.unbindService(link) }
            }
            try {
                if (died != null) withContext(NonCancellable) {
                    withTimeout(5_000) { died.await() }
                    if (death === died) death = null
                }
            } finally {
                if (broker != 0L) { NativeBridge.destroyNetworkBroker(broker); broker = 0L }
            }
        }
        Unit
    }
    fun startVm(id: Long) = runtime().startVm(id)
    fun runningVm(id: Long) = runtime().runningVm(id)
    fun requestStopVm(id: Long) = runtime().requestStopVm(id)
    suspend fun destroyVm(@Suppress("UNUSED_PARAMETER") id: Long) = close()
    fun inputVm(id: Long, data: ByteArray) = runtime().inputVm(id, data)
    fun readVm(id: Long, timeout: Int): ByteArray = runtime().readVm(id, timeout).also { check(it.size <= 16384) }
    fun sharedInputVm(id: Long, data: ByteArray) = runtime().sharedInputVm(id, data)
    fun sharedOutputVm(id: Long): ByteArray = runtime().sharedOutputVm(id).also { check(it.size <= 16384) }
    fun resizeVm(id: Long, rows: Int, cols: Int) = runtime().resizeVm(id, rows, cols)
    fun displayInputVm(id: Long, reports: ByteArray) = runtime().displayInputVm(id, reports)
    fun displayFrameVm(id: Long, pixels: IntArray): Boolean {
        require(pixels.size == 800 * 600)
        val pipe = runtime().displayFrame(id) ?: return false
        val bytes = ByteArray(pixels.size * 4)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).use { stream ->
            DataInputStream(stream).readFully(bytes)
            check(stream.read() == -1) { "Invalid sandbox frame length" }
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
        return true
    }
    fun pumpNetwork(id: Long): Boolean {
        var work = false
        // Bound work per turn so shutdown and the other channels remain responsive.
        repeat(32) {
            val outgoing = runtime().networkOutputVm(id)
            check(outgoing.isEmpty() || outgoing.size in 14..1514)
            if (outgoing.isNotEmpty()) { NativeBridge.inputNetworkBroker(broker, outgoing); work = true }
            val incoming = NativeBridge.readNetworkBroker(broker)
            if (incoming.isNotEmpty()) { runtime().networkInputVm(id, incoming); work = true }
            if (outgoing.isEmpty() && incoming.isEmpty()) return work
        }
        return work
    }
}
