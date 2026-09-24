package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aksulightning.flyby.BuildConfig
import io.github.aksulightning.flyby.settings.Settings
import io.github.aksulightning.flyby.settings.ThemeMode
import io.github.aksulightning.flyby.service.VmService
import io.github.aksulightning.flyby.vm.DiskMode

@Composable
fun SettingsScreen(settings: Settings, idle: Boolean, transfer: VmService.Transfer,
                   theme: (ThemeMode) -> Unit, disk: (DiskMode) -> Unit,
                   export: () -> Unit, import: () -> Unit, back: () -> Unit, modifier: Modifier, error: String? = null) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(back) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Row { RadioButton(settings.theme == mode, { theme(mode) }); TextButton({ theme(mode) }) { Text(when(mode) {
                ThemeMode.SYSTEM -> "Follow device"; ThemeMode.DARK -> "Night"; ThemeMode.LIGHT -> "Light"
            }) } }
        }
        HorizontalDivider()
        Text("Persistent disk", style = MaterialTheme.typography.titleMedium)
        DiskMode.entries.forEach { mode ->
            Row { RadioButton(settings.disk == mode, { disk(mode) }, enabled = idle)
                TextButton({ disk(mode) }, enabled = idle) { Text(if (mode == DiskMode.SYSTEM) "Whole system · 1 GiB" else "Home and data · 256 MiB") } }
        }
        Text("Whole system keeps installed packages and changes in /etc, /usr and /root. /run, /tmp and virtual filesystems remain temporary. Kernel and firmware are supplied by the app.")
        Text("These are separate disks. Switching modes keeps both disks; files are not automatically migrated. Stop Linux before changing or transferring a disk.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(export, enabled = idle) { Text("Export disk") }
            OutlinedButton(import, enabled = idle) { Text("Import disk") }
        }
        Text("Exports contain the selected disk, not running memory. Import accepts a Flyby backup of the same disk type and guest version and replaces that disk after validation.")
        if (transfer.busy) LinearProgressIndicator(progress = { transfer.progress / 100f }, modifier = Modifier.fillMaxWidth())
        transfer.message?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Flyby ${BuildConfig.VERSION_NAME}\nBuild ${BuildConfig.VERSION_CODE}\n${BuildConfig.REVISION}", style = MaterialTheme.typography.bodySmall)
    }
}
