package io.github.aksulightning.flyby

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aksulightning.flyby.ui.MainScreen
import io.github.aksulightning.flyby.ui.TerminalScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as FlybyApplication
        setContent {
            val status by app.vm.status.collectAsStateWithLifecycle()
            var terminalVisible by rememberSaveable { mutableStateOf(false) }
            BackHandler(terminalVisible) { terminalVisible = false }
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val content = Modifier.safeDrawingPadding().padding(16.dp)
                    if (terminalVisible) {
                        TerminalScreen(app.vm, status, { terminalVisible = false }, content)
                    } else {
                        MainScreen(status, app::startVm, app::stopVm, { terminalVisible = true }, content)
                    }
                }
            }
        }
    }
}
