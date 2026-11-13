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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpb.app.common.KeyScreen
import net.rpb.app.common.LockScreen
import net.rpb.app.common.Mode
import net.rpb.app.common.ScreenLockPrompt
import net.rpb.app.controller.ControllerScreen
import net.rpb.app.device.DeviceScreen
import net.rpb.app.ui.RpbTheme
import net.rpb.core.lock.PinLock
import net.rpb.core.vault.Vault
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private var locked by mutableStateOf(false)
    private lateinit var screenLock: ScreenLockPrompt
    private var opening by mutableStateOf(false)
    private var prompting = false
    private var keyMessage by mutableStateOf<String?>(null)

    /** The secrets were closed at start: whether the app PIN applies can only be read once they are open. */
    private var pinPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenLock = ScreenLockPrompt(this)
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
                            val vault by app.store.vault.collectAsStateWithLifecycle()
                            if (vault != Vault.OPEN) {
                                KeyScreen(vault, opening, keyMessage, onUnlock = ::openStore, onErase = app::wipeAndRestart)
                            } else if (locked) {
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
                                    Mode.CONTROLLER -> ControllerScreen(onSwitchMode = { app.applyMode(null) }, askScreenLock = screenLock::ask)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // PIN lock (ADR 0015): on start, and when coming back after more than a minute away. Locking clears the history
    // (ADR 0026) and, on a controller, forgets the data key (ADR 0028). The PIN lives in the secrets, so they open first.
    override fun onStart() {
        super.onStart()
        val app = rpb
        if (app.store.vault.value != Vault.OPEN) {
            pinPending = true
            if (!prompting && app.store.vault.value != Vault.LOST) openStore()
        } else if (app.store.pinLock.isSet && PinLock.needsUnlock(app.lastActive, SystemClock.elapsedRealtime())) {
            locked = true
            app.lock()
            if (app.store.mode.value == Mode.CONTROLLER) {
                app.store.close()
                openStore()
            }
        }
    }

    /** Unwraps the data key; if the Keystore wants the screen lock first, asks for it once and tries again. */
    private fun openStore() = openStore(prompt = true)

    private fun openStore(prompt: Boolean) {
        if (opening) return
        opening = true
        keyMessage = null
        lifecycleScope.launch {
            val app = rpb
            val vault = withContext(Dispatchers.IO) { app.store.open() }
            opening = false
            when {
                vault == Vault.OPEN -> onStoreOpened()
                vault == Vault.CLOSED && prompt -> {
                    prompting = true
                    screenLock.ask { passed ->
                        prompting = false
                        if (passed) openStore(prompt = false) else keyMessage = "The screen lock wasn't passed."
                    }
                }
                vault == Vault.CLOSED -> keyMessage = "The keys didn't open. Try again."
                else -> Unit
            }
        }
    }

    private fun onStoreOpened() {
        if (!pinPending) return
        pinPending = false
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
            "Device mode makes this app the phone's home screen, so it comes back after a reboot once the phone " +
                "is unlocked. Use it only on a dedicated phone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
