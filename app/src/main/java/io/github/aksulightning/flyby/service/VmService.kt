package io.github.aksulightning.flyby.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
import io.github.aksulightning.flyby.FlybyApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import io.github.aksulightning.flyby.MainActivity
import io.github.aksulightning.flyby.R
import io.github.aksulightning.flyby.terminal.TerminalEmulator
import io.github.aksulightning.flyby.terminal.TerminalSession
import io.github.aksulightning.flyby.vm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The started service, never an Activity, owns the native runtime and terminal. */
class VmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private val inputMutex = Mutex()
    data class Transfer(val busy: Boolean = false, val progress: Int = 0, val message: String? = null)
    private val storage = MutableStateFlow(Transfer())
    val transfer = storage.asStateFlow()
    private val settings get() = (application as FlybyApplication).settings
    private fun idle() = vm.status.value.state in listOf(VmState.STOPPED, VmState.ERROR) && !storage.value.busy
    fun setMemory(value: Int): Boolean {
        if (!idle()) return false
        settings.memory(value)
        return true
    }
    fun setSharedTree(value: String?): Boolean {
        if (!idle()) return false
        settings.sharedTree(value)
        return true
    }
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var terminal: TerminalEmulator? = null
    private var nativeError: String? = null
    lateinit var vm: VmManager
        private set
    lateinit var session: TerminalSession
        private set

    inner class LocalBinder : Binder() { val service: VmService get() = this@VmService }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Flyby Linux", NotificationManager.IMPORTANCE_LOW)
        )
        try { terminal = TerminalEmulator() }
        catch (failure: Exception) { nativeError = failure.message; log("NATIVE_ERROR $nativeError") }
        catch (failure: LinkageError) { nativeError = "Cannot load ARM64 native runtime: ${failure.message}"; log("NATIVE_ERROR $nativeError") }
        vm = VmManager(scope, NativeVmController(::log) {
            settings.state.value.sharedTree?.let {
                io.github.aksulightning.flyby.shared.AndroidSharedTree(contentResolver, android.net.Uri.parse(it))
            }
        }, ::log, shutdownTimeoutMs = 10_000,
            onReset = { checkNotNull(terminal) { nativeError ?: "Native terminal unavailable" }.reset() },
            onOutput = { data ->
                val reply = terminal?.append(data) ?: byteArrayOf()
                if (reply.isNotEmpty()) scope.launch { sendSafely(reply) }
            })
        session = object : TerminalSession {
            override val transcript = vm.transcript
            override val emulator get() = terminal
            override suspend fun sendInput(bytes: ByteArray) { sendSafely(bytes) }
            override suspend fun resize(rows: Int, cols: Int) {
                terminal?.resize(rows, cols)
                try { vm.resize(rows, cols) } catch (failure: Exception) { log("NATIVE_ERROR ${failure.message}") }
            }
        }
        scope.launch {
            vm.status.collectLatest { status ->
                if (status.state == VmState.RUNNING) {
                    try { terminal?.frame(0)?.let { if (it.size >= 2) vm.resize(it[0], it[1]) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { log("NATIVE_ERROR resize: ${failure.message}") }
                }
                if ((status.state == VmState.STOPPED || status.state == VmState.ERROR) && !storage.value.busy) {
                    releaseWakeLock()
                    if (started) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        started = false
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    // User-controlled foreground VM has no fixed duration. Every exit/destroy path releases it.
    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_UPGRADE_EDGE -> {
                if (storage.value.busy) return START_NOT_STICKY
                val upgradeEdge = intent.action == ACTION_UPGRADE_EDGE
                if (upgradeEdge && !idle()) return START_NOT_STICKY
                // Must run synchronously before provisioning assets or initializing native RAM.
                val notification = notification()
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification)
                started = true
                if (wakeLock == null) {
                    wakeLock = getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "Flyby:LinuxVM"
                    ).apply { setReferenceCounted(false); acquire() }
                }
                scope.launch {
                    val memory = if (upgradeEdge) maxOf(512, settings.state.value.memoryMiB) else settings.state.value.memoryMiB
                    val accepted = vm.start(VmConfig(memoryMiB = memory, upgradeEdge = upgradeEdge)) { GuestResources.prepare(this@VmService, DiskMode.SYSTEM) }
                    if (!accepted && vm.status.value.state in listOf(VmState.STOPPED, VmState.ERROR)) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        started = false
                    }
                }
            }
            ACTION_STOP -> scope.launch { vm.stop() }
            ACTION_CREATE -> {
                if (!idle()) return START_NOT_STICKY
                val gib = intent.getIntExtra(EXTRA_DISK_GIB, 0)
                if (gib !in 1..100) return START_NOT_STICKY
                storage.value = Transfer(busy = true, message = "Creating Alpine system disk")
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification())
                started = true
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flyby:DiskCreator")
                    .apply { setReferenceCounted(false); acquire() }
                scope.launch {
                    var result = "Alpine disk created ($gib GiB). Start Linux to expand its filesystem."
                    try {
                        GuestResources.createSystem(this@VmService, gib)
                        settings.diskSize(gib)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { result = "Disk creation failed: ${failure.message}" }
                    finally {
                        storage.value = Transfer(message = result)
                        releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); started = false
                    }
                }
            }
            ACTION_EXPORT, ACTION_IMPORT -> {
                if (!idle()) return START_NOT_STICKY
                val uri = intent.data ?: return START_NOT_STICKY
                val importing = intent.action == ACTION_IMPORT
                val mode = DiskMode.SYSTEM
                storage.value = Transfer(busy = true, message = if (importing) "Importing disk" else "Exporting disk")
                if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                else startForeground(NOTIFICATION, notification())
                started = true
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Flyby:DiskTransfer")
                    .apply { setReferenceCounted(false); acquire() }
                scope.launch {
                    var result = "Disk ${if (importing) "imported" else "exported"} successfully"
                    try {
                        withContext(Dispatchers.IO) {
                            val root = java.io.File(filesDir, "vm/default").apply { check(mkdirs() || isDirectory) }
                            check(root.canonicalFile.toPath().startsWith(filesDir.canonicalFile.toPath())) { "Invalid private storage path" }
                            val progress: (Int) -> Unit = { storage.value = storage.value.copy(progress = it) }
                            if (importing) {
                                checkNotNull(contentResolver.openInputStream(uri)) { "Cannot open backup" }.use {
                                    DiskArchive.import(root, mode, it, progress)
                                }
                                val gib = (DiskImage.validate(root, mode).length() / DiskImage.GIB).toInt()
                                withContext(Dispatchers.Main) { settings.diskSize(gib) }
                            } else {
                                GuestResources.prepare(this@VmService, mode)
                                checkNotNull(contentResolver.openOutputStream(uri, "wt")) { "Cannot create backup" }.use {
                                    DiskArchive.export(root, mode, it, progress)
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        result = "Disk transfer failed: ${failure.message}. The existing disk was not replaced."
                        log("DISK_TRANSFER_ERROR ${failure.javaClass.simpleName}")
                    } finally {
                        storage.value = Transfer(message = result)
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        started = false
                    }
                }
            }
        }
        // Process death loses RAM; never pretend a fresh boot is the previous session.
        return START_NOT_STICKY
    }

    fun stopVm() { scope.launch { vm.stop() } }
    private suspend fun sendSafely(bytes: ByteArray) = inputMutex.withLock {
        if (vm.status.value.state != VmState.RUNNING) return@withLock
        try {
            // Bounded chunks support larger clipboard pastes without filling the UART queue.
            for (offset in bytes.indices step 2048) {
                vm.sendInput(bytes.copyOfRange(offset, minOf(offset + 2048, bytes.size)))
                if (offset + 2048 < bytes.size) delay(20)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { log("NATIVE_ERROR console input: ${failure.message}") }
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, VmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle(if (storage.value.busy) "Flyby disk transfer" else "Flyby Linux is active")
            .setContentText("Local RISC-V Linux VM · tap to open terminal")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .apply { if (!storage.value.busy) addAction(Notification.Action.Builder(null, "Stop", stop).build()) }.build()
    }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() {
        releaseWakeLock()
        // onDestroy is not suspending. Cleanup is scoped, off the main thread, and joins all vCPUs.
        scope.launch {
            try { vm.forceStop() } finally { terminal?.close(); terminal = null; scope.cancel() }
        }
        super.onDestroy()
    }
    private fun log(message: String) { Log.i("FlybyVM", message) }
    companion object {
        const val ACTION_CREATE = "io.github.aksulightning.flyby.CREATE_DISK"
        const val EXTRA_DISK_GIB = "diskGiB"
        const val ACTION_EXPORT = "io.github.aksulightning.flyby.EXPORT"
        const val ACTION_IMPORT = "io.github.aksulightning.flyby.IMPORT"
        const val ACTION_START = "io.github.aksulightning.flyby.START"
        const val ACTION_UPGRADE_EDGE = "io.github.aksulightning.flyby.UPGRADE_EDGE"
        const val ACTION_STOP = "io.github.aksulightning.flyby.STOP"
        private const val CHANNEL = "flyby_linux"
        private const val NOTIFICATION = 1
    }
}
