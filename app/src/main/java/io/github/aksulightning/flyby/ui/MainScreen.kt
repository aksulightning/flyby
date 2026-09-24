package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aksulightning.flyby.vm.VmState
import io.github.aksulightning.flyby.vm.VmStatus

@Composable
fun MainScreen(status: VmStatus, onStart: () -> Unit, onStop: () -> Unit,
               onTerminal: () -> Unit, modifier: Modifier = Modifier, connected: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Linux VM", style = MaterialTheme.typography.headlineMedium)
        Text("Status: ${status.state.label()}")
        Text("RISC-V 64 · Alpine Linux · 512 MiB RAM")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onStart, enabled = connected && (status.state == VmState.STOPPED || status.state == VmState.ERROR)) {
                Text("Start")
            }
            OutlinedButton(onStop, enabled = status.state == VmState.STARTING || status.state == VmState.RUNNING) {
                Text("Stop")
            }
            OutlinedButton(onTerminal) { Text("Terminal") }
        }
        Text("Local development Linux VM. Files in /root and /data survive VM restarts. Other filesystem changes are temporary.")
        status.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status.exitCode?.let { Text("VM exit code: $it") }
    }
}

internal fun VmState.label(): String = when (this) {
    VmState.STOPPED -> "Stopped"
    VmState.STARTING -> "Starting"
    VmState.RUNNING -> "Running"
    VmState.STOPPING -> "Stopping"
    VmState.ERROR -> "Error"
}
