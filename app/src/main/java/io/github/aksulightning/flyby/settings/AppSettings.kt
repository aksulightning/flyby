package io.github.aksulightning.flyby.settings

import android.content.Context
import io.github.aksulightning.flyby.vm.DiskMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, DARK, LIGHT }
enum class CursorStyle { UNDERLINE, BLOCK, BAR }
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // DATA remains readable for legacy backups; new sessions use the system disk.
    val disk: DiskMode = DiskMode.SYSTEM,
    val memoryMiB: Int = 512,
    val diskGiB: Int = 1,
    val sharedTree: String? = null,
    val fontSize: Int = 14,
    val lineSpacing: Int = 100,
    val cursor: CursorStyle = CursorStyle.UNDERLINE,
    val extraKeys: Boolean = true,
    val keepScreenOn: Boolean = false,
)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val current = MutableStateFlow(Settings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        memoryMiB = prefs.getInt("memoryMiB", 512).coerceIn(128, 768),
        diskGiB = prefs.getInt("diskGiB", 1).coerceIn(1, 100),
        sharedTree = prefs.getString("sharedTree", null),
        fontSize = prefs.getInt("fontSize", 14).coerceIn(8, 32),
        lineSpacing = prefs.getInt("lineSpacing", 100).coerceIn(100, 160),
        cursor = runCatching { CursorStyle.valueOf(prefs.getString("cursor", "UNDERLINE")!!) }.getOrDefault(CursorStyle.UNDERLINE),
        extraKeys = prefs.getBoolean("extraKeys", true),
        keepScreenOn = prefs.getBoolean("keepScreenOn", false),
    ))
    val state = current.asStateFlow()
    fun theme(value: ThemeMode) { prefs.edit().putString("theme", value.name).apply(); current.value = current.value.copy(theme = value) }
    fun memory(value: Int) { require(value in 128..768); prefs.edit().putInt("memoryMiB", value).apply(); current.value = current.value.copy(memoryMiB = value) }
    fun diskSize(value: Int) { require(value in 1..100); prefs.edit().putInt("diskGiB", value).apply(); current.value = current.value.copy(diskGiB = value) }
    fun sharedTree(value: String?) { prefs.edit().putString("sharedTree", value).apply(); current.value = current.value.copy(sharedTree = value) }
    fun fontSize(value: Int) { require(value in 8..32); prefs.edit().putInt("fontSize", value).apply(); current.value = current.value.copy(fontSize = value) }
    fun lineSpacing(value: Int) { require(value in 100..160); prefs.edit().putInt("lineSpacing", value).apply(); current.value = current.value.copy(lineSpacing = value) }
    fun cursor(value: CursorStyle) { prefs.edit().putString("cursor", value.name).apply(); current.value = current.value.copy(cursor = value) }
    fun extraKeys(value: Boolean) { prefs.edit().putBoolean("extraKeys", value).apply(); current.value = current.value.copy(extraKeys = value) }
    fun keepScreenOn(value: Boolean) { prefs.edit().putBoolean("keepScreenOn", value).apply(); current.value = current.value.copy(keepScreenOn = value) }
}
