package io.github.aksulightning.flyby

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.os.*
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import io.github.aksulightning.flyby.terminal.TerminalView
import io.github.aksulightning.flyby.nativebridge.NativeBridge
import io.github.aksulightning.flyby.service.VmService
import io.github.aksulightning.flyby.vm.VmState
import io.github.aksulightning.flyby.vm.DiskMode
import io.github.aksulightning.flyby.settings.ThemeMode
import android.net.Uri
import java.io.File
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SDK-only on-device integration runner; real embedded RVVM engine.
 * adb shell am instrument -w io.github.aksulightning.flyby.test/io.github.aksulightning.flyby.VmInstrumentation
 */
class VmInstrumentation : Instrumentation() {
    private var checkNetwork = false
    private var uiTree = ""
    private var observedService: VmService? = null
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        checkNetwork = arguments?.getString("network") == "true"
        start()
    }
    override fun onStart() {
        var activity: Activity? = null
        var service: VmService? = null
        var bound = false
        var connected = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as VmService.LocalBinder).service; connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        var result = Activity.RESULT_CANCELED
        val report = Bundle()
        try {
            // Invalid JNI IDs and missing images must report Java exceptions, never native crashes.
            var rejected = false
            try { NativeBridge.runningVm(-1) } catch (_: IllegalStateException) { rejected = true }
            check(rejected)
            rejected = false
            try { NativeBridge.createVm("${targetContext.filesDir}/missing-guest", 512, 1) }
            catch (_: IllegalStateException) { rejected = true }
            check(rejected)
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            bound = targetContext.bindService(Intent(targetContext, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(connected.await(10, TimeUnit.SECONDS)) { "Service bind timed out" }
            val vmService = checkNotNull(service)
            observedService = vmService
            waitForIdleSync()
            clickStart()
            await(300_000) { "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            var terminalView: TerminalView? = null
            await(10_000) { runOnMainSync { terminalView = findTerminal(checkNotNull(activity).window.decorView) }; terminalView != null }
            runOnMainSync {
                val input = checkNotNull(terminalView).onCreateInputConnection(EditorInfo())
                input.commitText("echo Kernel panic; printf '\\nFLYBY_IME_OK\\n'", 1)
                input.performEditorAction(EditorInfo.IME_ACTION_NONE)
            }
            await(15_000) { "\nFLYBY_IME_OK\r\n" in vmService.session.transcript.value }
            val terminal = vmService.session.emulator
            check(vmService.vm.status.value.state == VmState.RUNNING)
            // Duplicate Start must retain the exact same terminal and running guest.
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity?.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) { "Activity did not recreate" }
            removeMonitor(monitor)
            runOnMainSync { activity?.moveTaskToBack(true) }
            // Neither the Activity nor the test keeps the service bound in the background.
            targetContext.unbindService(connection)
            bound = false
            SystemClock.sleep(1500)
            check(vmService.vm.status.value.state == VmState.RUNNING)
            check(vmService.session.emulator === terminal)
            runBlocking {
                vmService.session.sendInput("cat /etc/os-release; printf '\\nFLYBY_DEVICE_BACKGROUND_OK\\n'\n".toByteArray())
            }
            await(15_000) { "\nFLYBY_DEVICE_BACKGROUND_OK\r\n" in vmService.session.transcript.value }
            check("ID=alpine" in vmService.session.transcript.value)
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            waitForIdleSync()
            connected = CountDownLatch(1)
            bound = targetContext.bindService(Intent(targetContext, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(connected.await(10, TimeUnit.SECONDS))
            check(service === vmService) { "Service was replaced while backgrounded" }
            check(vmService.vm.status.value.state == VmState.RUNNING)
            check(vmService.session.emulator === terminal)
            if (checkNetwork) {
                await(30_000) { "FLYBY_NETWORK_READY" in vmService.session.transcript.value }
                runBlocking { vmService.session.sendInput("flyby-network-check\n".toByteArray()) }
                await(90_000) { "\nFLYBY_NETWORK_OK\r\n" in vmService.session.transcript.value }
            }
            val token = "persist-${SystemClock.elapsedRealtime()}"
            runBlocking { vmService.session.sendInput("echo $token > /root/.flyby-test-$token; sync; printf '\\nFLYBY_WRITE_OK\\n'\n".toByteArray()) }
            await(15_000) { "\nFLYBY_WRITE_OK\r\n" in vmService.session.transcript.value }
            runBlocking { vmService.vm.stop() }
            check(vmService.vm.status.value.state == VmState.STOPPED)
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            await(300_000) { vmService.vm.status.value.state == VmState.RUNNING && "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            runBlocking { vmService.session.sendInput("[ \"\$(cat /root/.flyby-test-$token)\" = $token ] && printf '\\nFLYBY_PERSIST_OK\\n'\n".toByteArray()) }
            await(15_000) { "\nFLYBY_PERSIST_OK\r\n" in vmService.session.transcript.value }
            runBlocking { vmService.vm.stop() }
            check(vmService.vm.status.value.state == VmState.STOPPED)
            runOnMainSync { activity?.window?.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
            await(10_000) {
                var hidden = false
                runOnMainSync { hidden = activity?.window?.decorView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == false }
                hidden
            }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            clickText("Settings")
            clickText("Night")
            val preferences = (targetContext.applicationContext as FlybyApplication).settings
            check(preferences.state.value.theme == ThemeMode.DARK)
            clickText("Whole system · 1 GiB")
            check(preferences.state.value.disk == DiskMode.SYSTEM)
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            await(300_000) { vmService.vm.status.value.state == VmState.RUNNING && "FLYBY_SYSTEM_READY" in vmService.session.transcript.value && "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            runOnMainSync { check(!vmService.selectDisk(DiskMode.DATA)) }
            runBlocking { vmService.session.sendInput("echo $token > /etc/flyby-system-test; sync; printf '\\nFLYBY_SYSTEM_WRITE_OK\\n'\n".toByteArray()) }
            await(15_000) { "\nFLYBY_SYSTEM_WRITE_OK\r\n" in vmService.session.transcript.value }
            runBlocking { vmService.vm.stop() }
            val backup = File(targetContext.cacheDir, "system-test.flyby")
            fun transfer(action: String, completion: String) {
                // Private file fixture calls the real service handler on main; production uses SAF content URIs.
                // Never send a file:// URI through Android IPC (FileUriExposedException).
                runOnMainSync { vmService.onStartCommand(Intent(targetContext, VmService::class.java).setAction(action).setData(Uri.fromFile(backup)), 0, 0) }
                await(180_000) { !vmService.transfer.value.busy && vmService.transfer.value.message == completion }
            }
            transfer(VmService.ACTION_EXPORT, "Disk exported successfully")
            check(backup.length() > 0)
            // Replace the current disk with a fresh seed, then restore the actual exported disk.
            check(File(targetContext.filesDir, "vm/default/system.raw").delete())
            transfer(VmService.ACTION_IMPORT, "Disk imported successfully")
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            await(300_000) { vmService.vm.status.value.state == VmState.RUNNING && "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            runBlocking { vmService.session.sendInput("[ \"\$(cat /etc/flyby-system-test)\" = $token ] && printf '\\nFLYBY_RESTORE_OK\\n'\n".toByteArray()) }
            await(15_000) { "\nFLYBY_RESTORE_OK\r\n" in vmService.session.transcript.value }
            runBlocking { vmService.vm.stop() }
            backup.delete()
            result = Activity.RESULT_OK
            report.putString("stream", "PASS: JNI validation, Start UI, Alpine shell, terminal IME, network=$checkNetwork, duplicate start, Activity recreate/background/return, session identity, persistent /root, Night settings UI, full system root, service export/import, restored /etc after restart and Stop\n")
        } catch (failure: Throwable) {
            report.putString("uiTree", uiTree)
            report.putString("guestOutput", service?.session?.transcript?.value.orEmpty())
            report.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
        } finally {
            service?.let { runBlocking { it.vm.forceStop() } }
            if (bound) targetContext.unbindService(connection)
            runOnMainSync { activity?.finish() }
        }
        finish(result, report)
    }
    private fun clickStart() = clickText("Start")
    private fun clickText(label: String) {
        await(10_000) {
            // Traverse virtual Compose nodes: framework text search may not enumerate them.
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null || nodes.size >= 256) return
                nodes += node
                for (i in 0 until node.childCount) visit(node.getChild(i))
            }
            visit(uiAutomation.rootInActiveWindow)
            uiTree = nodes.joinToString("\n") { "${it.packageName} ${it.className}: ${it.text} / ${it.contentDescription} enabled=${it.isEnabled} clickable=${it.isClickable}" }
            var clicked = false
            for (node in nodes) {
                if (node.text?.toString() != label) continue
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.isEnabled == true && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { clicked = true; break }
            }
            clicked
        }
    }
    private fun findTerminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findTerminal(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            observedService?.vm?.status?.value?.let { status ->
                check(status.state != VmState.ERROR) { "VM failed: ${status.error}" }
            }
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for guest output" }
            SystemClock.sleep(100)
        }
    }
}
