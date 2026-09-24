package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aksulightning.flyby.terminal.TerminalSession
import io.github.aksulightning.flyby.vm.VmStatus

/** Phase 1 diagnostic surface. Real terminal emulation and key handling arrive after guest boot. */
@Composable
fun TerminalScreen(session: TerminalSession, status: VmStatus, onBack: () -> Unit,
                   modifier: Modifier = Modifier) {
    val transcript by session.transcript.collectAsStateWithLifecycle()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onBack) { Text("Back") }
            Text("Terminal · ${status.state.label()}", style = MaterialTheme.typography.titleMedium)
        }
        Text("Serial diagnostics preview. Interactive terminal is not available in Phase 1.")
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(transcript.ifEmpty { "No guest output yet." }, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("ESC", "TAB", "CTRL", "ALT", "↑", "↓", "←", "→").forEach { key ->
                // Disabled visibly: no simulated terminal or input sent to the host.
                OutlinedButton(onClick = {}, enabled = false) { Text(key) }
            }
        }
    }
}
