package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aksulightning.flyby.terminal.TerminalSession
import io.github.aksulightning.flyby.terminal.TerminalView
import io.github.aksulightning.flyby.vm.VmStatus
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(session: TerminalSession, status: VmStatus, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val emulator = session.emulator
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onBack) { Text("Back") }
            TextButton({ view?.copyScreen() }) { Text("Copy") }
            TextButton({ view?.paste() }) { Text("Paste") }
            Text(status.state.label(), Modifier.padding(12.dp))
        }
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (emulator != null) {
            val revision by emulator.revision.collectAsStateWithLifecycle()
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context -> TerminalView(context).also { view = it } },
                update = { terminal ->
                    terminal.terminal = emulator
                    terminal.ctrlNext = ctrl; terminal.altNext = alt
                    terminal.onModifiersConsumed = { ctrl = false; alt = false }
                    terminal.onInput = { bytes -> scope.launch { session.sendInput(bytes) } }
                    terminal.onResize = { rows, cols -> scope.launch { session.resize(rows, cols) } }
                    // Observe one revision per native output batch, never one per UART byte.
                    if (revision >= 0) terminal.invalidate()
                },
            )
        } else {
            Text("Native terminal is unavailable. Start will show the initialization error.", Modifier.weight(1f))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("ESC" to 4, "TAB" to 2).forEach { (label, key) -> TextButton({ view?.extraKey(key) }) { Text(label) } }
            TextButton({ ctrl = !ctrl }) { Text(if (ctrl) "CTRL •" else "CTRL") }
            TextButton({ alt = !alt }) { Text(if (alt) "ALT •" else "ALT") }
            listOf("↑" to 5, "↓" to 6, "←" to 7, "→" to 8).forEach { (label, key) -> TextButton({ view?.extraKey(key) }) { Text(label) } }
        }
    }
}
