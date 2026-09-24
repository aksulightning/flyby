package io.github.aksulightning.flyby

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
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
import io.github.aksulightning.flyby.vm.VmStatus
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<VmService?>(null)
    private var connectionError by mutableStateOf<String?>(null)
    private var bound = false
    private val disconnected = MutableStateFlow(VmStatus())
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { startServiceVm() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) { service = (binder as VmService.LocalBinder).service }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val connected = service
            val status by (connected?.vm?.status ?: disconnected).collectAsStateWithLifecycle()
            var terminalVisible by rememberSaveable { mutableStateOf(false) }
            BackHandler(terminalVisible) { terminalVisible = false }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val content = Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp)
                    val visibleStatus = status.copy(error = connectionError ?: status.error)
                    if (terminalVisible && connected != null) {
                        TerminalScreen(connected.session, visibleStatus, { terminalVisible = false }, content)
                    } else {
                        MainScreen(visibleStatus, {
                            terminalVisible = true
                            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else startServiceVm()
                        }, { connected?.stopVm() }, { terminalVisible = true }, content, connected != null)
                    }
                }
            }
        }
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
