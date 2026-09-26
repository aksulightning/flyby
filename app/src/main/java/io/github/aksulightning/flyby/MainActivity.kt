package io.github.aksulightning.flyby

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import io.github.aksulightning.flyby.settings.ThemeMode
import io.github.aksulightning.flyby.ui.SettingsScreen
import io.github.aksulightning.flyby.ui.LicensesScreen
import io.github.aksulightning.flyby.vm.VmState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aksulightning.flyby.service.VmService
import io.github.aksulightning.flyby.ui.MainScreen
import io.github.aksulightning.flyby.ui.TerminalScreen
import io.github.aksulightning.flyby.ui.DisplayScreen
import io.github.aksulightning.flyby.vm.VmStatus
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<VmService?>(null)
    private var connectionError by mutableStateOf<String?>(null)
    private var bound = false
    private var pendingExport by mutableStateOf<String?>(null)
    private var pendingImport by mutableStateOf<String?>(null)
    private var pendingShared by mutableStateOf<String?>(null)
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { pendingExport = it?.toString() }
    private val importPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingImport = it?.toString() }
    private val sharedPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::selectSharedFolder)
    internal fun selectSharedFolder(uri: android.net.Uri?) {
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                // The result can arrive before onStart's asynchronous service binding completes.
                // Retain the grant now, but validate VM state only once the service reconnects.
                contentResolver.takePersistableUriPermission(uri, flags)
                pendingShared = uri.toString()
                connectionError = null
            } catch (failure: Exception) {
                connectionError = "Cannot select shared folder: ${failure.message}"
            }
        }
    }
    private val noTransfer = MutableStateFlow(VmService.Transfer())
    private val disconnected = MutableStateFlow(VmStatus())
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startServiceVm() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as VmService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("export")
        pendingImport = savedInstanceState?.getString("import")
        pendingShared = savedInstanceState?.getString("shared")
        enableEdgeToEdge()
        setContent {
            val connected = service
            val preferences = (application as FlybyApplication).settings
            val settings by preferences.state.collectAsStateWithLifecycle()
            val transfer by (connected?.transfer ?: noTransfer).collectAsStateWithLifecycle()
            var settingsVisible by rememberSaveable { mutableStateOf(false) }
            var licensesVisible by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(connected, pendingExport) {
                if (connected != null && pendingExport != null) {
                    transferDisk(VmService.ACTION_EXPORT, pendingExport!!)
                    pendingExport = null
                }
            }
            LaunchedEffect(connected, pendingShared) {
                val selected = pendingShared
                if (connected != null && selected != null) {
                    applySharedFolder(connected, selected)
                    pendingShared = null
                }
            }
            val status by (connected?.vm?.status ?: disconnected).collectAsStateWithLifecycle()
            var terminalVisible by rememberSaveable { mutableStateOf(false) }
            var displayVisible by rememberSaveable { mutableStateOf(false) }
            BackHandler(terminalVisible || displayVisible || settingsVisible || licensesVisible) {
                if (licensesVisible) licensesVisible = false else { terminalVisible = false; displayVisible = false; settingsVisible = false }
            }
            val dark = settings.theme == ThemeMode.DARK || (settings.theme == ThemeMode.SYSTEM && isSystemInDarkTheme())
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val content = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)
                    val visibleStatus = status.copy(error = connectionError ?: status.error)
                    val idle = connected != null && !transfer.busy && status.state in listOf(VmState.STOPPED, VmState.ERROR)
                    if (pendingImport != null) AlertDialog(
                        onDismissRequest = { pendingImport = null },
                        title = { Text("Replace selected disk?") },
                        text = { Text("The ${settings.disk.name.lowercase()} disk will be replaced by this backup. Export it first if you need its current contents.") },
                        confirmButton = { TextButton({ transferDisk(VmService.ACTION_IMPORT, pendingImport!!); pendingImport = null }, enabled = idle) { Text("Replace disk") } },
                        dismissButton = { TextButton({ pendingImport = null }) { Text("Cancel") } })
                    if (licensesVisible) {
                        LicensesScreen({ licensesVisible = false }, content)
                    } else if (settingsVisible) {
                        SettingsScreen(settings, idle, transfer, preferences,
                            { connected?.setMemory(it) }, { gib, image -> createDisk(gib, image) },
                            { sharedPicker.launch(settings.sharedTree?.let(android.net.Uri::parse)) },
                            { disconnectSharedFolder() }, { licensesVisible = true },
                            { exportPicker.launch("flyby-${settings.disk.name.lowercase()}-${System.currentTimeMillis()}.flyby") },
                            { importPicker.launch(arrayOf("*/*")) }, { settingsVisible = false }, content, connectionError)
                    } else if (displayVisible && connected != null) {
                        DisplayScreen(connected.vm, visibleStatus, { displayVisible = false },
                            { displayVisible = false; terminalVisible = true }, content)
                    } else if (terminalVisible && connected != null) {
                        TerminalScreen(connected.session, visibleStatus, { terminalVisible = false }, content, settings)
                    } else {
                        MainScreen(visibleStatus, {
                            terminalVisible = true
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startServiceVm()
                        }, { connected?.stopVm() }, { terminalVisible = true }, content, connected != null && !transfer.busy, { settingsVisible = true }, settings.memoryMiB, settings.diskGiB,
                            onDisplay = { displayVisible = true })
                    }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export", pendingExport)
        outState.putString("import", pendingImport)
        outState.putString("shared", pendingShared)
        super.onSaveInstanceState(outState)
    }
    private fun applySharedFolder(connected: VmService, selected: String) {
        val old = (application as FlybyApplication).settings.state.value.sharedTree
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (connected.setSharedTree(selected)) {
            if (old != null && old != selected) runCatching {
                contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), flags)
            }
            connectionError = null
        } else {
            if (old != selected) runCatching {
                contentResolver.releasePersistableUriPermission(android.net.Uri.parse(selected), flags)
            }
            connectionError = if (connected.transfer.value.busy)
                "Cannot select shared folder: wait for the disk operation to finish"
            else "Cannot select shared folder: stop Linux before changing the shared folder"
        }
    }
    private fun createDisk(gib: Int, image: io.github.aksulightning.flyby.vm.ImageVariant) {
        try {
            connectionError = null
            startForegroundService(Intent(this, VmService::class.java).setAction(VmService.ACTION_CREATE).putExtra(VmService.EXTRA_DISK_GIB, gib).putExtra(VmService.EXTRA_IMAGE, image.name))
        } catch (failure: RuntimeException) { connectionError = "Cannot create disk: ${failure.message}" }
    }
    private fun disconnectSharedFolder() {
        val old = (application as FlybyApplication).settings.state.value.sharedTree ?: return
        if (service?.setSharedTree(null) == true) {
            runCatching { contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            connectionError = null
        }
    }
    private fun transferDisk(action: String, uri: String) {
        try {
            connectionError = null
            startForegroundService(Intent(this, VmService::class.java).setAction(action).setData(android.net.Uri.parse(uri)))
        } catch (failure: RuntimeException) { connectionError = "Cannot transfer disk: ${failure.message}" }
    }
    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, VmService::class.java), connection, BIND_AUTO_CREATE)
    }
    override fun onStop() {
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onStop()
    }
    private fun startServiceVm() {
        try {
            connectionError = null
            startForegroundService(Intent(this, VmService::class.java).setAction(VmService.ACTION_START))
        } catch (failure: RuntimeException) {
            connectionError = "Cannot start foreground VM service: ${failure.message}"
        }
    }
}
