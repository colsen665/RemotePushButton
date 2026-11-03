package net.rpb.app.common

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.controller.ControllerClient
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.toHex
import net.rpb.core.lock.PinLock
import net.rpb.core.lock.PinState
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.protocol.SeqStore
import net.rpb.core.vault.SecretVault
import net.rpb.core.vault.Vault
import net.rpb.core.vault.VaultFiles

enum class Mode { DEVICE, CONTROLLER }

@Serializable
data class DeviceSettings(
    val deviceId: String = Keys.random(Keys.ID_BYTES).toHex(),
    /** Suffix of the MQTT client ID. Random and never shared, so nobody can take the device's session over (ADR 0024). */
    val relayId: String = Keys.random(RELAY_ID_BYTES).toHex(),
    val name: String = "Remote button",
    /** Credentials the device phone uses on the broker. */
    val deviceBroker: BrokerConfig = BrokerConfig(host = ""),
    /** Credentials handed to controllers inside the pairing QR. */
    val controllerBroker: BrokerConfig = BrokerConfig(host = ""),
    val botMac: String = "",
    /** The Bot's optional password, blank for none; sent as its CRC32, which can be sniffed (ADR 0025). */
    val botPassword: String = "",
    /** Torch on while the camera is open for a job; the rig keeps the display otherwise dark (ADR 0014). */
    val torch: Boolean = true,
    /** Extra clockwise turn of every photo, in degrees, so the display reads upright (ADR 0014). */
    val photoRotation: Int = 0,
    val ocr: SevenSegConfig = SevenSegConfig(),
    /** What the device follows the display by, made at Save on the device or the first valid read after (ADRs 0018, 0022). */
    val anchor: DisplayAnchor? = null,
    /** Keep full-size photos and job calibrations in app storage, for pulling with adb (ADR 0023). */
    val saveDiagnostics: Boolean = false,
) {
    /**
     * Whether the running service must restart for [updated] to apply: it builds its relay from these at start,
     * and reads every other setting when it uses it (ADR 0032).
     */
    fun needsRestart(updated: DeviceSettings) =
        updated.deviceBroker != deviceBroker || updated.deviceId != deviceId || updated.relayId != relayId

    companion object {
        const val RELAY_ID_BYTES = 8
    }
}

@Serializable
data class StoredController(val id: String, val name: String, val key: String, val createdAt: Long)

@Serializable
data class StoredPairing(
    val code: String,
    val name: String,
    /** Wait between the press and the photo on this device (ADR 0019). */
    val delayMs: Int = ControllerClient.DEFAULT_DELAY_MS,
)

@Serializable
private data class Secrets(
    val device: DeviceSettings? = null,
    val controllers: List<StoredController> = emptyList(),
    val pairings: List<StoredPairing> = emptyList(),
    val pin: PinState? = null,
)

/**
 * App state. Everything secret (broker passwords, pairing keys, the PIN hash) lives in a [SecretVault]: one blob
 * encrypted with a random data key, which the Keystore key wraps (ADR 0028). Only the mode and replay counters are
 * stored in plain preferences.
 */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("rpb", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keystore = KeystoreBox(appContext)

    private val secrets = SecretVault(
        keys = keystore,
        files = object : VaultFiles {
            override fun wrappedKey() = prefs.getString(DATA_KEY, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            override fun blob() = prefs.getString(SECRETS, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
            override fun commit(wrappedKey: ByteArray, blob: ByteArray) = prefs.edit()
                .putString(DATA_KEY, Base64.encodeToString(wrappedKey, Base64.NO_WRAP))
                .putString(SECRETS, Base64.encodeToString(blob, Base64.NO_WRAP))
                .commit()
            override fun saveBlob(blob: ByteArray) = prefs.edit().putString(SECRETS, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
        },
        encode = { json.encodeToString(Secrets.serializer(), it).encodeToByteArray() },
        // A device setting saved before a field was added gets its default (a random relayId), which is saved back.
        decode = { plain ->
            val loaded = plain?.let { json.decodeFromString(Secrets.serializer(), it.decodeToString()) } ?: Secrets()
            loaded.copy(device = loaded.device ?: DeviceSettings())
        },
        log = { Log.i("RPB", it) },
    )

    /** Whether the secrets can be read; the flows below hold placeholders until they are [Vault.OPEN]. */
    val vault: StateFlow<Vault> = secrets.state

    private val _mode = MutableStateFlow(prefs.getString("mode", null)?.let { Mode.valueOf(it) })
    val mode: StateFlow<Mode?> = _mode

    // Placeholders until the secrets are open; nothing is saved while they are closed.
    private val _device = MutableStateFlow(DeviceSettings())
    val device: StateFlow<DeviceSettings> = _device
    private val _controllers = MutableStateFlow<List<StoredController>>(emptyList())
    val controllers: StateFlow<List<StoredController>> = _controllers
    private val _pairings = MutableStateFlow<List<StoredPairing>>(emptyList())
    val pairings: StateFlow<List<StoredPairing>> = _pairings

    // Lockouts run on the time since boot, which can't be set forward like the wall clock (ADR 0024).
    val pinLock = PinLock(
        load = { secrets.value?.pin },
        // Only reachable while the secrets are open: the PIN prompt and settings show on top of them.
        save = { pin -> synchronized(this) { update { it.copy(pin = pin) } } },
        clock = SystemClock::elapsedRealtime,
    )

    val seqStore: SeqStore = object : SeqStore {
        override fun lastSeq(controllerId: String) = prefs.getLong("seq_$controllerId", 0L)
        override fun setLastSeq(controllerId: String, seq: Long) {
            prefs.edit().putLong("seq_$controllerId", seq).apply()
        }
    }

    fun setMode(mode: Mode?) {
        prefs.edit().putString("mode", mode?.name).apply()
        _mode.value = mode
    }

    /** Opens the secrets ([SecretVault.open]): Keystore work, so off the main thread. */
    @Synchronized
    fun open(): Vault {
        val state = secrets.open()
        secrets.value?.let(::publish)
        return state
    }

    /** [KeystoreBox.describe] as logged when the secrets first opened in this process. */
    val keyDescription: String? get() = secrets.keyDescription

    /** Where the Keystore key lives, for the PIN lock dialog (ADR 0029). Keystore work, so off the main thread. */
    fun keyStorage(): String? = keystore.storage()

    /** Forgets the data key and the secrets (ADR 0028); [open] reads them again through the Keystore. */
    @Synchronized
    fun close() {
        secrets.close()
        _pairings.value = emptyList()
        _controllers.value = emptyList()
        _device.value = DeviceSettings()
    }

    // The mutators save and return true while the secrets are open; while closed they change nothing and return
    // false, since a UI callback can still arrive after a controller locked (ADR 0028).

    @Synchronized
    fun updateDevice(transform: (DeviceSettings) -> DeviceSettings) = update { it.copy(device = transform(checkNotNull(it.device))) }

    @Synchronized
    fun addController(controller: StoredController) = update { it.copy(controllers = it.controllers + controller) }

    @Synchronized
    fun removeController(id: String) = update { s -> s.copy(controllers = s.controllers.filterNot { it.id == id }) }

    @Synchronized
    fun addPairing(pairing: StoredPairing) = update { s -> s.copy(pairings = s.pairings.filterNot { it.code == pairing.code } + pairing) }

    /** Replaces the pairing with the same code, keeping its place in the list. */
    @Synchronized
    fun updatePairing(pairing: StoredPairing) = update { s -> s.copy(pairings = s.pairings.map { if (it.code == pairing.code) pairing else it }) }

    @Synchronized
    fun removePairing(code: String) = update { s -> s.copy(pairings = s.pairings.filterNot { it.code == code }) }

    private fun update(transform: (Secrets) -> Secrets): Boolean {
        val updated = secrets.update(transform) ?: return false
        publish(updated)
        return true
    }

    private fun publish(value: Secrets) {
        _device.value = checkNotNull(value.device)
        _controllers.value = value.controllers
        _pairings.value = value.pairings
    }

    /**
     * Erases all app data (ADR 0015): secrets, preferences, controller history and the Keystore key, so nothing
     * left behind can be decrypted. The process must restart afterwards; in-memory state is not reset.
     */
    @SuppressLint("ApplySharedPref") // must reach the disk before the process exits
    @Synchronized
    fun wipe() {
        prefs.edit().clear().commit()
        appContext.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        keystore.deleteKeys()
    }

    private companion object {
        const val SECRETS = "secrets"
        const val DATA_KEY = "data_key"
    }
}
