package net.rpb.app.common

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Base64
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
 * App state. Everything secret (broker passwords, pairing keys, the PIN hash) lives in one Keystore-encrypted
 * blob; only the mode and replay counters are stored in plain preferences.
 */
class Store(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("rpb", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _mode = MutableStateFlow(prefs.getString("mode", null)?.let { Mode.valueOf(it) })
    val mode: StateFlow<Mode?> = _mode

    private var secrets: Secrets = loadSecrets()
    // Saved back right away, so a field added since the last save (a random relayId) is fixed from now on.
    private val _device = MutableStateFlow((secrets.device ?: DeviceSettings()).also { saveSecrets(secrets.copy(device = it)) })
    val device: StateFlow<DeviceSettings> = _device
    private val _controllers = MutableStateFlow(secrets.controllers)
    val controllers: StateFlow<List<StoredController>> = _controllers
    private val _pairings = MutableStateFlow(secrets.pairings)
    val pairings: StateFlow<List<StoredPairing>> = _pairings

    // Lockouts run on the time since boot, which can't be set forward like the wall clock (ADR 0024).
    val pinLock = PinLock(load = { secrets.pin }, save = { pin -> synchronized(this) { saveSecrets(secrets.copy(pin = pin)) } }, clock = SystemClock::elapsedRealtime)

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

    @Synchronized
    fun updateDevice(transform: (DeviceSettings) -> DeviceSettings) {
        val updated = transform(_device.value)
        saveSecrets(secrets.copy(device = updated))
        _device.value = updated
    }

    @Synchronized
    fun addController(controller: StoredController) {
        val list = _controllers.value + controller
        saveSecrets(secrets.copy(controllers = list))
        _controllers.value = list
    }

    @Synchronized
    fun removeController(id: String) {
        val list = _controllers.value.filterNot { it.id == id }
        saveSecrets(secrets.copy(controllers = list))
        _controllers.value = list
    }

    @Synchronized
    fun addPairing(pairing: StoredPairing) {
        val list = _pairings.value.filterNot { it.code == pairing.code } + pairing
        saveSecrets(secrets.copy(pairings = list))
        _pairings.value = list
    }

    /** Replaces the pairing with the same code, keeping its place in the list. */
    @Synchronized
    fun updatePairing(pairing: StoredPairing) {
        val list = _pairings.value.map { if (it.code == pairing.code) pairing else it }
        saveSecrets(secrets.copy(pairings = list))
        _pairings.value = list
    }

    @Synchronized
    fun removePairing(code: String) {
        val list = _pairings.value.filterNot { it.code == code }
        saveSecrets(secrets.copy(pairings = list))
        _pairings.value = list
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
        KeystoreBox.deleteKey()
    }

    private fun loadSecrets(): Secrets {
        val stored = prefs.getString("secrets", null) ?: return Secrets()
        val plain = KeystoreBox.decrypt(Base64.decode(stored, Base64.NO_WRAP))
        return json.decodeFromString(Secrets.serializer(), plain.decodeToString())
    }

    private fun saveSecrets(updated: Secrets) {
        secrets = updated
        val blob = KeystoreBox.encrypt(json.encodeToString(Secrets.serializer(), updated).encodeToByteArray())
        prefs.edit().putString("secrets", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }
}
