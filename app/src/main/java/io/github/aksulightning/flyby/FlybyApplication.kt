package io.github.aksulightning.flyby

import android.app.Application
import android.util.Log
import io.github.aksulightning.flyby.vm.VmConfig
import io.github.aksulightning.flyby.vm.VmFiles
import io.github.aksulightning.flyby.vm.VmManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FlybyApplication : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // Phase 1 has no process backend. Do not install one before adding a foreground service.
    val vm = VmManager(scope, log = { Log.i("FlybyVM", it) })

    fun startVm() {
        scope.launch {
            vm.start(VmConfig(), VmFiles(File(filesDir, "vm/default"), File(applicationInfo.nativeLibraryDir)))
        }
    }

    fun stopVm() { scope.launch { vm.stop() } }
}
