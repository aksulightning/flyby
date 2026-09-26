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
import io.github.aksulightning.flyby.display.DisplayView
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
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                "am start -W -n io.github.aksulightning.flyby.test/io.github.aksulightning.flyby.SharedTestSetupActivity")).use { it.readBytes() }
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            bound = targetContext.bindService(Intent(targetContext, VmService::class.java), connection, Context.BIND_AUTO_CREATE)
            check(connected.await(10, TimeUnit.SECONDS)) { "Service bind timed out" }
            val vmService = checkNotNull(service)
            observedService = vmService
            waitForIdleSync()
            val sharedUri = android.provider.DocumentsContract.buildTreeDocumentUri("io.github.aksulightning.flyby.test.shared", "root")
            check(io.github.aksulightning.flyby.shared.AndroidSharedTree(targetContext.contentResolver, sharedUri).root().id == "root") { "Fixture root document ID must be stable" }
            runOnMainSync { check(vmService.setMemory(128)); check(vmService.setSharedTree(null)) }
            // A real picker stops MainActivity and returns its result before the service reconnects.
            // Exercise that order: retain the selected URI while disconnected, then apply on rebind.
            runOnMainSync { checkNotNull(activity).moveTaskToBack(true) }
            await(10_000) {
                var stopped = false
                runOnMainSync { stopped = (activity as MainActivity).lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED }
                stopped
            }
            val pickerSettings = (targetContext.applicationContext as FlybyApplication).settings
            runOnMainSync {
                (activity as MainActivity).selectSharedFolder(sharedUri)
                (activity as MainActivity).selectSharedFolder(null) // Cancellation does not erase a pending selection.
                check(pickerSettings.state.value.sharedTree == null) { "Selection must wait for the Activity's service connection" }
            }
            check(targetContext.contentResolver.persistedUriPermissions.any { it.uri == sharedUri && it.isReadPermission && it.isWritePermission })
            targetContext.startActivity(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            await(10_000) { pickerSettings.state.value.sharedTree == sharedUri.toString() }
            waitForIdleSync()
            clickStart()
            await(300_000) { "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            await(30_000) { "FLYBY_SHARED_READY" in vmService.session.transcript.value }
            check("PRETTY_NAME=\"Alpine Linux edge\"" in vmService.session.transcript.value)
            runBlocking { vmService.session.sendInput((
                "grep -qxF 'https://dl-cdn.alpinelinux.org/alpine/edge/main' /etc/apk/repositories && " +
                "grep -qxF 'https://dl-cdn.alpinelinux.org/alpine/edge/community' /etc/apk/repositories && " +
                "[ \"\$(cat /shared/from-android)\" = android-data ] && " +
                "echo linux-data > /shared/from-linux && mkdir /shared/sub && " +
                "echo nested > /shared/sub/old && mv /shared/sub/old /shared/sub/renamed && " +
                "echo replacement > /shared/sub/old && [ \"\$(cat /shared/sub/renamed)\" = nested ] && " +
                "[ \"\$(cat /shared/sub/old)\" = replacement ] && rm /shared/sub/renamed /shared/sub/old && rmdir /shared/sub && " +
                "printf '\\nFLYBY_SHARED_IO_OK\\n'\n").toByteArray()) }
            await(30_000) { "\nFLYBY_SHARED_IO_OK\r\n" in vmService.session.transcript.value }
            val sharedBackend = io.github.aksulightning.flyby.shared.AndroidSharedTree(targetContext.contentResolver, sharedUri)
            val files = sharedBackend.children(sharedBackend.root().id)
            val written = files.single { it.name == "from-linux" }
            check(sharedBackend.read(written.id, 0, 100).toString(Charsets.UTF_8).trim() == "linux-data")
            check(files.none { it.name == "sub" })
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
            check(preferences.state.value.disk == DiskMode.SYSTEM)
            clickText("Service Alpine", scroll = true)
            clickText("Create Service Alpine disk", scroll = true)
            clickText("Cancel")
            runOnMainSync {
                vmService.onStartCommand(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_CREATE).putExtra(VmService.EXTRA_DISK_GIB, 2).putExtra(VmService.EXTRA_IMAGE, "SERVICE"), 0, 0)
            }
            await(180_000) { !vmService.transfer.value.busy }
            check(File(targetContext.filesDir, "vm/default/system.raw").length() == 2L * 1024 * 1024 * 1024)
            check(preferences.state.value.diskGiB == 2)
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            await(300_000) { vmService.vm.status.value.state == VmState.RUNNING && "FLYBY_SYSTEM_READY" in vmService.session.transcript.value && "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            runBlocking { vmService.session.sendInput("[ \"\$(cat /proc/1/comm)\" = openrc-init ] && rc-service flyby-control status && printf '\\nFLYBY_OPENRC_OK\\n'\n".toByteArray()) }
            await(15_000) { "\nFLYBY_OPENRC_OK\r\n" in vmService.session.transcript.value }
            runOnMainSync { check(!vmService.setMemory(768)); check(!vmService.setSharedTree(null)) }
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
            // Exercise the new image and Android view/IME against the real guest.
            runOnMainSync {
                check(vmService.setMemory(512))
                vmService.onStartCommand(Intent(targetContext, VmService::class.java)
                    .setAction(VmService.ACTION_CREATE).putExtra(VmService.EXTRA_DISK_GIB, 1)
                    .putExtra(VmService.EXTRA_IMAGE, "WAYLAND"), 0, 0)
            }
            await(180_000) { !vmService.transfer.value.busy }
            check(vmService.transfer.value.message?.startsWith("Minimal Alpine Wayland disk created") == true)
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) // Settings -> Main.
            clickStart()
            await(300_000) { "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            runBlocking { vmService.session.sendInput((
                "i=0; until rc-service flyby-wayland-terminal status >/dev/null 2>&1; do " +
                "i=\$((i+1)); [ \$i -lt 60 ] || break; sleep 1; done; " +
                "rc-service flyby-wayland-terminal status && printf '\\nFLYBY_WAYLAND_READY\\n'\n").toByteArray()) }
            await(120_000) { "\nFLYBY_WAYLAND_READY\r\n" in vmService.session.transcript.value }
            val frame = IntArray(800 * 600)
            await(30_000) { runBlocking { vmService.vm.displayFrame(frame) } && frame.toSet().size > 16 }
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) // Terminal -> Main.
            clickText("Display")
            var display: DisplayView? = null
            await(10_000) {
                var ready = false
                runOnMainSync {
                    display = findDisplay(checkNotNull(activity).window.decorView)
                    ready = display?.input != null
                }
                ready
            }
            runOnMainSync {
                val view = checkNotNull(display)
                view.requestFocus()
                val input = view.onCreateInputConnection(EditorInfo())
                input.setComposingText("echo android", 1)
                input.commitText("echo android > /root/wayland-android-test\n", 1)
            }
            runBlocking { vmService.session.sendInput((
                "i=0; until grep -qx android /root/wayland-android-test 2>/dev/null; do " +
                "i=\$((i+1)); [ \$i -lt 30 ] || break; sleep 1; done; " +
                "grep -qx android /root/wayland-android-test && printf '\\nFLYBY_DISPLAY_IME_OK\\n'\n").toByteArray()) }
            await(45_000) { "\nFLYBY_DISPLAY_IME_OK\r\n" in vmService.session.transcript.value }
            // Recreate while Display is selected; retain the VM and restore the view.
            val displayMonitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity?.recreate() }
            activity = checkNotNull(displayMonitor.waitForActivityWithTimeout(10_000))
            removeMonitor(displayMonitor)
            await(10_000) {
                var ready = false
                runOnMainSync { ready = findDisplay(checkNotNull(activity).window.decorView)?.input != null }
                ready
            }
            check(vmService.vm.status.value.state == VmState.RUNNING)
            runBlocking { vmService.vm.stop() }
            check(!runBlocking { vmService.vm.displayFrame(frame) })
            result = Activity.RESULT_OK
            report.putString("stream", "PASS: JNI validation, shared-folder result before service rebind, Start UI, 128 MiB Alpine boot, SAF /shared read/write/create/rename/recreate/remove, terminal IME, network=$checkNetwork, duplicate start, Activity recreate/background/return, session identity, persistent /root, Night settings UI, 2 GiB Disk Creator, full system root, service export/import, restored /etc after restart, Wayland 800x600 frame, Display IME, Display recreation and Stop\n")
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
    private fun clickText(label: String, scroll: Boolean = false) {
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
            if (!clicked && scroll) {
                // Full-page accessibility scrolling can skip partially visible controls.
                // Drag a third of the visible viewport, then hold to avoid a fling.
                nodes.firstOrNull { it.isScrollable }?.let { node ->
                    val bounds = android.graphics.Rect()
                    node.getBoundsInScreen(bounds)
                    val x = bounds.centerX().toFloat()
                    val startY = bounds.top + bounds.height() * 0.75f
                    val endY = bounds.top + bounds.height() * 0.4f
                    val down = SystemClock.uptimeMillis()
                    fun touch(action: Int, y: Float) {
                        val event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                        event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        try { check(uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                    touch(android.view.MotionEvent.ACTION_DOWN, startY)
                    for (step in 1..8) {
                        SystemClock.sleep(30)
                        touch(android.view.MotionEvent.ACTION_MOVE, startY + (endY - startY) * step / 8)
                    }
                    SystemClock.sleep(200)
                    touch(android.view.MotionEvent.ACTION_UP, endY)
                }
                SystemClock.sleep(250)
                waitForIdleSync()
            }
            clicked
        }
    }
    private fun findTerminal(view: View): TerminalView? {
        if (view is TerminalView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findTerminal(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun findDisplay(view: View): DisplayView? {
        if (view is DisplayView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findDisplay(view.getChildAt(i))?.let { return it }
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
