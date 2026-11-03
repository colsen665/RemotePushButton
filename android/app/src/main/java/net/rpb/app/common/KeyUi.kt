package net.rpb.app.common

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import net.rpb.app.ui.Amber
import net.rpb.core.vault.Vault

/**
 * Asks for the phone's screen lock, which opens the Keystore key for 30 s (ADR 0028): `BiometricPrompt` allowing
 * the device credential on API 30 and later, the keyguard's confirm-credential screen before. Create it in
 * `onCreate`.
 */
class ScreenLockPrompt(private val activity: ComponentActivity) {
    private var pending: ((Boolean) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pending?.invoke(result.resultCode == Activity.RESULT_OK)
        pending = null
    }

    /** [onResult] gets whether the screen lock was passed. */
    fun ask(onResult: (Boolean) -> Unit) {
        val title = "Unlock RemotePushButton"
        val description = "The app's keys open only with the phone's screen lock."
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setDescription(description)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(
                CancellationSignal(),
                activity.mainExecutor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onResult(true)

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onResult(false)
                },
            )
        } else {
            @Suppress("DEPRECATION")
            val intent = activity.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, description)
            if (intent == null) {
                onResult(false)
                return
            }
            pending = onResult
            launcher.launch(intent)
        }
    }
}

/** Shown instead of the app while the secrets can't be read (ADR 0028). */
@Composable
fun KeyScreen(vault: Vault, busy: Boolean, message: String?, onUnlock: () -> Unit, onErase: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        when (vault) {
            Vault.CLOSED -> {
                Text("Unlock the app", style = MaterialTheme.typography.headlineSmall)
                Text("The app's keys open only right after the phone's screen lock (PIN, pattern, password or fingerprint).")
                Button(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Opening…" else "Unlock") }
            }
            Vault.NO_SCREEN_LOCK -> {
                Text("Set a screen lock first", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "This app keeps its keys behind the phone's screen lock. Set a PIN, pattern or password in the phone's " +
                        "settings, then come back. Removing the screen lock later erases the app's pairings.",
                )
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Open security settings")
                }
                OutlinedButton(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            Vault.LOST -> {
                Text("The app's data can't be opened", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "The key that protected the pairings and settings is gone, which happens when the phone's screen lock " +
                        "is removed, or the stored data is damaged. They can't be recovered. Erase them to set the app up " +
                        "again; every controller has to pair again.",
                )
                Button(onClick = onErase, modifier = Modifier.fillMaxWidth()) { Text("Erase and start again") }
                // A Keystore error can be passing.
                OutlinedButton(onClick = onUnlock, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
            // MainActivity shows the app instead.
            Vault.OPEN -> Unit
        }
        message?.let { Text(it, color = Amber) }
    }
}
