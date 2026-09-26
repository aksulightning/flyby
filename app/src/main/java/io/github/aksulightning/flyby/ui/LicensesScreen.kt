package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LicensesScreen(back: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val entries = linkedMapOf(
        "Notices" to "NOTICE.txt", "Flyby · Apache 2.0" to "Flyby-Apache-2.0.txt",
        "RVVM · MPL 2.0" to "RVVM-MPL-2.0.txt", "Linux · GPL 2.0" to "Linux-GPL-2.0.txt",
        "OpenSBI · BSD 2-Clause" to "OpenSBI-BSD-2-Clause.txt", "libvterm · MIT" to "libvterm-MIT.txt",
        "OpenRC · BSD 2-Clause" to "OpenRC-BSD-2-Clause.txt",
        "Alpine package licenses and sources" to "vm/provenance.json",
    )
    val navigateBack = { if (selected != null) selected = null else back() }
    BackHandler(onBack = navigateBack)
    val text by produceState("Loading…", selected) {
        value = selected?.let { name -> withContext(Dispatchers.IO) {
            runCatching { context.assets.open(entries.getValue(name)).bufferedReader().use { it.readText() } }
                .getOrElse { "License text could not be loaded: ${it.message}" }
        } }.orEmpty()
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(navigateBack) { Text("Back") }
        Text(selected ?: "Licenses", style = MaterialTheme.typography.headlineMedium)
        if (selected == null) Column(Modifier.verticalScroll(rememberScrollState())) {
            entries.keys.forEach { title -> TextButton({ selected = title }) { Text(title) } }
        } else key(selected) {
            SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
