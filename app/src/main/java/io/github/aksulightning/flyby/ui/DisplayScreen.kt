package io.github.aksulightning.flyby.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.aksulightning.flyby.display.DisplayInput
import io.github.aksulightning.flyby.display.DisplayView
import io.github.aksulightning.flyby.vm.VmManager
import io.github.aksulightning.flyby.vm.VmState
import io.github.aksulightning.flyby.vm.VmStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

@Composable
fun DisplayScreen(vm: VmManager, status: VmStatus, onBack: () -> Unit, onTerminal: () -> Unit, modifier: Modifier = Modifier) {
    var view by remember { mutableStateOf<DisplayView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    val events = remember(vm) { Channel<ByteArray>(128) }
    val input = remember(vm) { DisplayInput {
        if (!events.trySend(it).isSuccess) error = "Input queue full. Reopen Display to reset the keyboard."
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(vm, lifecycle, status.state) {
        ready = false; view?.clear()
        if (status.state != VmState.RUNNING) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (events.tryReceive().isSuccess) { /* Drop input from a previous foreground session. */ }
            input.reset(); ctrl = false
            try {
                coroutineScope {
                    launch { for (report in events) vm.displayInput(report) }
                    val pixels = IntArray(800 * 600)
                    while (isActive) {
                        ready = vm.displayFrame(pixels)
                        if (ready) view?.update(pixels) else view?.clear()
                        delay(100) // Bound copies to 10 fps; no polling while backgrounded.
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Display failed" }
            finally {
                // Release keys/buttons even when navigation or lifecycle cancels the consumer.
                withContext(NonCancellable) {
                    val release = mutableListOf<ByteArray>()
                    DisplayInput { release += it }.reset()
                    runCatching { vm.displayInput(release.single()) }
                }
                ready = false; view?.clear()
            }
        }
    }
    DisposableEffect(events) { onDispose { events.close() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onBack) { Text("Back") }
            TextButton(onTerminal) { Text("Terminal") }
            Text("800 × 600", Modifier.padding(12.dp))
        }
        if (!ready) Text(if (status.state == VmState.RUNNING)
            "Waiting for Wayland. Use a Minimal Alpine Wayland disk; see Terminal for boot messages."
            else "Start the VM to use Display.")
        (error ?: status.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        AndroidView(factory = { DisplayView(it).also { v -> view = v } },
            update = {
                it.input = if (ready) input else null
                it.onUnsupportedText = { error = "Text is unsupported. Use US keyboard characters or ä, ö, å, é (up to 2048 characters)." }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton({ ctrl = !ctrl; input.key(0xe0, ctrl) }, enabled = ready) { Text(if (ctrl) "Ctrl ✓" else "Ctrl") }
            for ((label, code) in listOf("Esc" to 41, "Tab" to 43, "Enter" to 40, "←" to 80, "↓" to 81, "↑" to 82, "→" to 79)) {
                TextButton({ input.tap(code) }, enabled = ready) { Text(label) }
            }
            TextButton({ view?.keyboard() }, enabled = ready) { Text("Keyboard") }
        }
    }
}
