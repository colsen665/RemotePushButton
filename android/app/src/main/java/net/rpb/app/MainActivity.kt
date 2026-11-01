package net.rpb.app

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.rpb.app.common.LockScreen
import net.rpb.app.common.Mode
import net.rpb.app.controller.ControllerScreen
import net.rpb.app.device.DeviceScreen
import net.rpb.app.ui.RpbTheme
import net.rpb.core.lock.PinLock
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Keeps codes and pairing QRs out of Recents thumbnails and screenshots (ADR 0020).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val app = rpb
        setContent {
            RpbTheme {
                Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        BuildStamp()
                        Box(Modifier.weight(1f)) {
                            val mode by app.store.mode.collectAsStateWithLifecycle()
                            if (locked) {
                                LockScreen(
                                    app.store.pinLock,
                                    onUnlocked = {
                                        app.lastActive = SystemClock.elapsedRealtime()
                                        locked = false
                                    },
                                    onWipe = app::wipeAndRestart,
                                )
                            } else {
                                when (mode) {
                                    null -> ModeChooser(onPick = app::applyMode)
                                    Mode.DEVICE -> DeviceScreen(onSwitchMode = { app.applyMode(null) })
                                    Mode.CONTROLLER -> ControllerScreen(onSwitchMode = { app.applyMode(null) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // PIN lock (ADR 0015): on start, and when coming back after more than a minute away. Locking clears the history (ADR 0026).
    override fun onStart() {
        super.onStart()
        val app = rpb
        if (app.store.pinLock.isSet && PinLock.needsUnlock(app.lastActive, SystemClock.elapsedRealtime())) {
            locked = true
            app.lock()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !locked) rpb.lastActive = SystemClock.elapsedRealtime()
    }

    // As the HOME launcher in Device mode, Back must not leave the app.
    @Deprecated("Back is intentionally swallowed in Device mode")
    override fun onBackPressed() {
        if (rpb.store.mode.value != Mode.DEVICE) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}

/** Which build is installed: build number (commits), commit with "+" for uncommitted changes, and build time. */
@Composable
private fun BuildStamp() {
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(BuildConfig.BUILD_TIME)) }
    Text(
        "Build ${BuildConfig.BUILD_NUMBER} · ${BuildConfig.BUILD_COMMIT} · $time",
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ModeChooser(onPick: (Mode) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("What is this phone?", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = { onPick(Mode.CONTROLLER) }, modifier = Modifier.fillMaxWidth()) {
            Text("Controller — my phone, I press the button from here")
        }
        OutlinedButton(onClick = { onPick(Mode.DEVICE) }, modifier = Modifier.fillMaxWidth()) {
            Text("Device — the phone mounted next to the SwitchBot")
        }
        Text(
            "Device mode makes this app the phone's home screen so it restarts by itself after a reboot. " +
                "Use it only on a dedicated phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
