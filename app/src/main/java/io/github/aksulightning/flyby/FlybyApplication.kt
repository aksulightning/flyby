package io.github.aksulightning.flyby

import android.app.Application

/** Runtime ownership is in VmService; Application has no Activity or native handles. */
class FlybyApplication : Application() {
    val settings by lazy { io.github.aksulightning.flyby.settings.AppSettings(this) }
}
