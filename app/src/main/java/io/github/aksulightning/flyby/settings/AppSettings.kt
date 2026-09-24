package io.github.aksulightning.flyby.settings

import android.content.Context
import io.github.aksulightning.flyby.vm.DiskMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }
data class Settings(val theme: ThemeMode = ThemeMode.SYSTEM, val disk: DiskMode = DiskMode.DATA)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(Settings(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        runCatching { DiskMode.valueOf(prefs.getString("disk", "DATA")!!) }.getOrDefault(DiskMode.DATA)))
    val state = current.asStateFlow()
    fun theme(value: ThemeMode) { prefs.edit().putString("theme", value.name).apply(); current.value = current.value.copy(theme = value) }
    fun disk(value: DiskMode) { prefs.edit().putString("disk", value.name).apply(); current.value = current.value.copy(disk = value) }
}
