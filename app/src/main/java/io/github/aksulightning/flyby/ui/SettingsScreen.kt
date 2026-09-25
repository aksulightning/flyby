package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aksulightning.flyby.BuildConfig
import io.github.aksulightning.flyby.settings.*
import io.github.aksulightning.flyby.service.VmService

@Composable
fun SettingsScreen(settings: Settings, idle: Boolean, transfer: VmService.Transfer,
                   preferences: AppSettings, memory: (Int) -> Unit, createDisk: (Int) -> Unit, upgradeEdge: () -> Unit,
                   selectShared: () -> Unit, clearShared: () -> Unit, licenses: () -> Unit,
                   export: () -> Unit, import: () -> Unit, back: () -> Unit, modifier: Modifier, error: String? = null) {
    var ram by rememberSaveable(settings.memoryMiB) { mutableStateOf(settings.memoryMiB.toString()) }
    var disk by rememberSaveable(settings.diskGiB) { mutableStateOf(settings.diskGiB.toString()) }
    var confirmCreate by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmUpgrade by rememberSaveable { mutableStateOf(false) }
    val ramValue = ram.toIntOrNull()?.takeIf { it in 128..768 }
    val diskValue = disk.toIntOrNull()?.takeIf { it in 1..100 }
    if (confirmUpgrade) AlertDialog(
        onDismissRequest = { confirmUpgrade = false },
        title = { Text("Upgrade this installation to Edge?") },
        text = { Text("This starts Linux and updates the existing disk's repositories and installed packages to Alpine Edge. Your disk is not replaced. Export a backup first so you can restore it if the upgrade fails. Internet access is required. Keep Flyby open and do not stop Linux during the upgrade. At least 512 MiB RAM is used for this start; your saved RAM setting is kept.") },
        confirmButton = { TextButton({ confirmUpgrade = false; upgradeEdge() }, enabled = idle) { Text("Start Edge upgrade") } },
        dismissButton = { TextButton({ confirmUpgrade = false }) { Text("Cancel") } })
    confirmCreate?.let { size -> AlertDialog(
        onDismissRequest = { confirmCreate = null },
        title = { Text("Create a new Alpine Edge disk?") },
        text = { Text("This replaces the current system disk with a fresh $size GiB Alpine Edge installation. All files and installed packages on the current system disk will be lost. Export it first if needed. The old Home/Data disk is kept separately.") },
        confirmButton = { TextButton({ createDisk(size); confirmCreate = null }, enabled = idle) { Text("Create and replace") } },
        dismissButton = { TextButton({ confirmCreate = null }) { Text("Cancel") } }) }
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(back) { Text("Back") }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Row { RadioButton(settings.theme == mode, { preferences.theme(mode) }); TextButton({ preferences.theme(mode) }) { Text(when(mode) {
                ThemeMode.SYSTEM -> "Follow device"; ThemeMode.DARK -> "Night"; ThemeMode.LIGHT -> "Light"
            }) } }
        }
        HorizontalDivider()
        Text("Virtual machine", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(ram, { ram = it }, label = { Text("RAM · 128–768 MiB") }, singleLine = true,
            isError = ramValue == null, enabled = idle, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button({ ramValue?.let(memory) }, enabled = idle && ramValue != null && ramValue != settings.memoryMiB) { Text("Save RAM") }
        Text("Saved RAM: ${settings.memoryMiB} MiB. Stop Linux before changing RAM, disks or the shared folder. Changes apply at the next start.")
        Text("Disk Creator", style = MaterialTheme.typography.titleMedium)
        OutlinedButton({ confirmUpgrade = true }, enabled = idle) { Text("Upgrade current disk to Edge") }
        Text("Still seeing Alpine 3.23? Updating the Android app preserves that installed system. Upgrade the current disk here to keep your files, or create a fresh Edge disk below.")
        OutlinedTextField(disk, { disk = it }, label = { Text("System disk · 1–100 GiB") }, singleLine = true,
            isError = diskValue == null, enabled = idle, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Button({ confirmCreate = diskValue }, enabled = idle && diskValue != null) { Text("Create Alpine Edge disk") }
        Text("A complete persistent Alpine Edge system (main + community repositories), including /etc, /usr and /root. Updating Flyby keeps your existing installation; only new disks use this system image. The filesystem expands to the selected size on its first boot. Disk space is allocated as it is used; keep enough free Android storage. /run and /tmp stay temporary.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(export, enabled = idle) { Text("Export disk") }
            OutlinedButton(import, enabled = idle) { Text("Import disk") }
        }
        Text("Backups include the system disk, not running memory or /shared. Import replaces the system disk after validation.")
        if (transfer.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        transfer.message?.let { Text(it) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text("Shared folder · /shared", style = MaterialTheme.typography.titleMedium)
        Text(settings.sharedTree?.let { android.net.Uri.decode(it.substringAfterLast('/')) } ?: "No folder selected")
        OutlinedButton(selectShared, enabled = idle) { Text("Choose Android folder") }
        if (settings.sharedTree != null) TextButton(clearShared, enabled = idle) { Text("Disconnect folder") }
        Text("The selected Android folder is available at /shared in Linux. File edits and deletions affect the Android files directly. Access is limited to that folder. Document providers may not support every Unix file operation.")
        Text("The previous Home/Data disk is retained in app storage; its contents are not automatically copied to the system disk or /shared.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("Terminal", style = MaterialTheme.typography.titleMedium)
        Text("Text size · ${settings.fontSize} sp")
        Slider(settings.fontSize.toFloat(), { preferences.fontSize(it.toInt()) }, valueRange = 8f..32f, steps = 23)
        Text("Line spacing · ${settings.lineSpacing}%")
        Slider(settings.lineSpacing.toFloat(), { preferences.lineSpacing(it.toInt()) }, valueRange = 100f..160f, steps = 59)
        Text("Cursor")
        CursorStyle.entries.forEach { style ->
            Row { RadioButton(settings.cursor == style, { preferences.cursor(style) }); TextButton({ preferences.cursor(style) }) { Text(style.name.lowercase().replaceFirstChar { it.uppercase() }) } }
        }
        SettingSwitch("Extra key row", settings.extraKeys, preferences::extraKeys)
        SettingSwitch("Keep screen on in terminal", settings.keepScreenOn, preferences::keepScreenOn)
        HorizontalDivider()
        OutlinedButton(licenses) { Text("Licenses") }
        Text("Flyby ${BuildConfig.VERSION_NAME}\nBuild ${BuildConfig.VERSION_CODE}\n${BuildConfig.REVISION}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp)); Switch(checked, change)
    }
}
