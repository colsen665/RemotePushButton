package net.rpb.app.common

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpb.app.rpb
import net.rpb.app.ui.Amber
import net.rpb.app.ui.Bad
import net.rpb.core.lock.PinCheck
import net.rpb.core.lock.PinLock

/** Full-screen PIN prompt shown on start and after more than a minute away (ADR 0015). */
@Composable
fun LockScreen(lock: PinLock, onUnlocked: () -> Unit, onWipe: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var lockedUntil by remember { mutableLongStateOf(0L) }
    var checking by remember { mutableStateOf(false) }
    // The lock measures waits on the time since boot (ADR 0024).
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lockedUntil) {
        while (SystemClock.elapsedRealtime() < lockedUntil) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
        now = SystemClock.elapsedRealtime()
    }
    val waiting = now < lockedUntil

    fun submit() {
        checking = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { lock.check(pin) }
            checking = false
            pin = ""
            when (result) {
                PinCheck.Ok -> onUnlocked()
                PinCheck.Wipe -> onWipe()
                is PinCheck.LockedOut -> lockedUntil = result.until
                is PinCheck.Wrong -> {
                    lockedUntil = result.lockedUntil
                    message = wrongMessage(result.attemptsLeft)
                }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Enter PIN", style = MaterialTheme.typography.headlineSmall)
        if (lock.wipeOnFailures) Text("${PinLock.MAX_FAILURES} wrong PINs in a row erase all app data.", color = Amber)
        PinField(pin, "PIN", enabled = !waiting && !checking) { pin = it }
        message?.let { Text(it, color = Bad) }
        if (waiting) Text("Too many wrong PINs. Try again in ${(lockedUntil - now + 999) / 1000} s.", color = Amber)
        Button(onClick = ::submit, enabled = PinLock.isValid(pin) && !waiting && !checking, modifier = Modifier.fillMaxWidth()) {
            Text(if (checking) "Checking…" else "Unlock")
        }
    }
}

/**
 * Set, change or remove the PIN and choose whether wrong PINs erase the app. Every change to an existing PIN
 * needs the current one, and a wrong current PIN counts as a failed attempt.
 */
@Composable
fun PinSettings(lock: PinLock, onWipe: () -> Unit) {
    val scope = rememberCoroutineScope()
    var isSet by remember { mutableStateOf(lock.isSet) }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var wipe by remember { mutableStateOf(lock.wipeOnFailures) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reset(text: String) {
        current = ""
        new = ""
        confirm = ""
        isSet = lock.isSet
        wipe = lock.wipeOnFailures
        message = text
    }

    /** Runs [action] off the main thread (hashing takes a moment) and shows the message it returns. */
    fun inBackground(action: () -> String) {
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.Default) { action() }
            busy = false
            reset(text)
        }
    }

    /** Checks the current PIN, then runs [action] if it was right; both off the main thread. */
    fun withCurrent(action: () -> String) {
        busy = true
        scope.launch {
            var text = ""
            val result = withContext(Dispatchers.Default) { lock.check(current).also { if (it == PinCheck.Ok) text = action() } }
            busy = false
            when (result) {
                PinCheck.Ok -> reset(text)
                PinCheck.Wipe -> onWipe()
                is PinCheck.LockedOut -> message = "Too many wrong PINs. Wait and try again."
                is PinCheck.Wrong -> {
                    current = ""
                    message = wrongMessage(result.attemptsLeft)
                }
            }
        }
    }

    val newOk = new.isEmpty() || (PinLock.isValid(new) && new == confirm)
    // Where the Keystore key behind the screen lock lives (ADRs 0028, 0029).
    val context = LocalContext.current
    val keyStorage by produceState<String?>(null) { value = withContext(Dispatchers.IO) { context.rpb.store.keyStorage() } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        keyStorage?.let { Text("Key storage: $it", style = MaterialTheme.typography.bodySmall) }
        Text(
            if (isSet) "The app asks for the PIN on start and after more than a minute away." else "No PIN set.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (isSet) PinField(current, "Current PIN", enabled = !busy) { current = it }
        PinField(new, if (isSet) "New PIN (leave empty to keep)" else "New PIN (${PinLock.MIN_LENGTH}–${PinLock.MAX_LENGTH} digits)", enabled = !busy) { new = it }
        PinField(confirm, "Repeat new PIN", enabled = !busy) { confirm = it }
        if (new.isNotEmpty() && confirm.isNotEmpty() && new != confirm) Text("The new PINs differ.", color = Amber)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Erase all app data after ${PinLock.MAX_FAILURES} wrong PINs", Modifier.weight(1f))
            Switch(wipe, { wipe = it }, enabled = !busy)
        }
        if (wipe) {
            Text(
                "Erasing removes pairings, controllers, broker settings and the calibration. You'll have to set up and pair again.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!isSet) {
                Button(onClick = {
                    val pin = new
                    val wipeOn = wipe
                    inBackground {
                        lock.set(pin, wipeOn)
                        "PIN set."
                    }
                }, enabled = PinLock.isValid(new) && new == confirm && !busy) { Text("Set PIN") }
            } else {
                Button(onClick = {
                    val pin = new
                    val wipeOn = wipe
                    withCurrent {
                        if (pin.isNotEmpty()) lock.set(pin, wipeOn) else lock.setWipeOnFailures(wipeOn)
                        "Saved."
                    }
                }, enabled = PinLock.isValid(current) && newOk && !busy) { Text("Save") }
                OutlinedButton(onClick = {
                    withCurrent {
                        lock.clear()
                        "PIN removed."
                    }
                }, enabled = PinLock.isValid(current) && !busy) { Text("Remove PIN") }
            }
        }
    }
}

@Composable
private fun PinField(value: String, label: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value,
        { onChange(it.filter(Char::isDigit).take(PinLock.MAX_LENGTH)) },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun wrongMessage(attemptsLeft: Int?): String = when (attemptsLeft) {
    null -> "Wrong PIN."
    1 -> "Wrong PIN. 1 attempt left before all app data is erased."
    else -> "Wrong PIN. $attemptsLeft attempts left before all app data is erased."
}
