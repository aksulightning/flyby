package io.github.aksulightning.flyby

import android.app.Activity
import android.app.Instrumentation
import android.content.*
import android.os.*
import io.github.aksulightning.flyby.nativebridge.NativeBridge
import io.github.aksulightning.flyby.service.VmService
import io.github.aksulightning.flyby.vm.VmState
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** SDK-only on-device integration runner; no emulator or mocked engine.
 * adb shell am instrument -w io.github.aksulightning.flyby.test/io.github.aksulightning.flyby.VmInstrumentation
 */
class VmInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        var activity: Activity? = null
        var service: VmService? = null
        var bound = false
        val connected = CountDownLatch(1)
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
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            await(300_000) { "FLYBY_ALPINE_READY" in vmService.session.transcript.value }
            val terminal = vmService.session.emulator
            check(vmService.vm.status.value.state == VmState.RUNNING)
            // Duplicate Start must retain the exact same terminal and running guest.
            targetContext.startForegroundService(Intent(targetContext, VmService::class.java).setAction(VmService.ACTION_START))
            val monitor = addMonitor(MainActivity::class.java.name, null, false)
            runOnMainSync { activity?.recreate() }
            activity = checkNotNull(monitor.waitForActivityWithTimeout(10_000)) { "Activity did not recreate" }
            removeMonitor(monitor)
            runOnMainSync { activity?.moveTaskToBack(true) }
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
            check(vmService.vm.status.value.state == VmState.RUNNING)
            check(vmService.session.emulator === terminal)
            runBlocking { vmService.vm.stop() }
            check(vmService.vm.status.value.state == VmState.STOPPED)
            result = Activity.RESULT_OK
            report.putString("stream", "PASS: JNI validation, Alpine shell, duplicate start, Activity recreate/background/return, session identity and Stop\n")
        } catch (failure: Throwable) {
            report.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
        } finally {
            service?.let { runBlocking { it.vm.forceStop() } }
            if (bound) targetContext.unbindService(connection)
            runOnMainSync { activity?.finish() }
        }
        finish(result, report)
    }
    private fun await(timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for guest output" }
            SystemClock.sleep(100)
        }
    }
}
