package net.rpb.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import net.rpb.app.common.Mode
import net.rpb.app.common.Store
import net.rpb.app.device.DeviceService
import net.rpb.core.controller.History
import java.io.File

class RpbApp : Application() {
    lateinit var store: Store
        private set

    /** `SystemClock.elapsedRealtime()` when the user last unlocked or left the app; null until the first unlock. */
    var lastActive: Long? = null

    /** Controller results, in memory for 5 minutes and cleared on lock (ADR 0026). */
    val history = History(SystemClock::elapsedRealtime)

    private val _locks = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val locks: SharedFlow<Unit> = _locks

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        // Builds before ADR 0026 kept the history on disk.
        File(filesDir, "history").deleteRecursively()
    }

    /** The PIN prompt is showing: forget every code the app holds. */
    fun lock() {
        history.clear()
        _locks.tryEmit(Unit)
    }

    /** Device mode registers the app as a HOME launcher so it is on screen after every boot (ADR 0009). */
    fun applyMode(mode: Mode?) {
        store.setMode(mode)
        val state = if (mode == Mode.DEVICE) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
            ComponentName(this, "$packageName.HomeAlias"),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** Three wrong PINs with wipe on (ADR 0015): stop the device service, erase everything and start fresh. */
    fun wipeAndRestart() {
        DeviceService.stop(this)
        applyMode(null)
        store.wipe()
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        // Nothing decrypted may stay in memory.
        Runtime.getRuntime().exit(0)
    }
}

val Context.rpb: RpbApp get() = applicationContext as RpbApp
