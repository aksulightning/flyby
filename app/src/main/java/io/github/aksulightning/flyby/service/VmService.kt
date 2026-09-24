package io.github.aksulightning.flyby.service

import android.app.*
import android.annotation.SuppressLint
import android.content.Intent
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
        vm = VmManager(scope, NativeVmController(::log), ::log, shutdownTimeoutMs = 10_000,
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
                if (status.state == VmState.STOPPED || status.state == VmState.ERROR) {
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
            ACTION_START -> {
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
                    val accepted = vm.start(VmConfig()) { GuestResources.prepare(this@VmService) }
                    if (!accepted && vm.status.value.state in listOf(VmState.STOPPED, VmState.ERROR)) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        started = false
                    }
                }
            }
            ACTION_STOP -> scope.launch { vm.stop() }
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
            .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Flyby Linux is active")
            .setContentText("Local RISC-V Linux VM · tap to open terminal")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
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
        const val ACTION_START = "io.github.aksulightning.flyby.START"
        const val ACTION_STOP = "io.github.aksulightning.flyby.STOP"
        private const val CHANNEL = "flyby_linux"
        private const val NOTIFICATION = 1
    }
}
