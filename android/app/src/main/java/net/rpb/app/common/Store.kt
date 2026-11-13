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
import net.rpb.core.device.PairedController
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

/** A controller paired with this device; [publicKey] (Base64 SPKI) and [bindUntil] as in [PairedController] (ADR 0033). */
@Serializable
data class StoredController(
    val id: String,
    val name: String,
    val key: String,
    val createdAt: Long,
    val publicKey: String? = null,
    val bindUntil: Long? = null,
) {
    fun paired() = PairedController(
        id,
        name,
        Base64.decode(key, Base64.NO_WRAP),
        publicKey?.let { Base64.decode(it, Base64.NO_WRAP) },
        bindUntil,
    )

    companion object {
        /** A new controller, pending until [now] + [PairedController.BIND_WINDOW_MS]: its QR is shown from [now]. */
        fun pending(id: String, name: String, key: ByteArray, now: Long) =
            StoredController(id, name, Base64.encodeToString(key, Base64.NO_WRAP), now, bindUntil = now + PairedController.BIND_WINDOW_MS)
    }
}

@Serializable
data class StoredPairing(
    val code: String,
    val name: String,
    /** Wait between the press and the photo on this device (ADR 0019). */
    val delayMs: Int = ControllerClient.DEFAULT_DELAY_MS,
    /** The device bound this phone's signing key for the pairing (ADR 0033); false for pairings made before. */
    val bound: Boolean = false,
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
        val reopened = secrets.state.value != Vault.OPEN
        val state = secrets.open()
        secrets.value?.let(::publish)
        if (reopened && state == Vault.OPEN) {
            dropPendingControllers()
            if (_mode.value == Mode.CONTROLLER) dropUnpairedSigningKeys()
        }
        return state
    }

    /**
     * Removes every pending controller, those past their deadline among them (ADR 0033). Run as the secrets open:
     * the QR dialog shows only while they are open, so no pending controller's QR can be on screen yet.
     */
    private fun dropPendingControllers() {
        val pending = secrets.value?.controllers.orEmpty().filter { it.paired().pending }.map { it.id }.toSet()
        if (pending.isEmpty()) return
        update { s -> s.copy(controllers = s.controllers.filterNot { it.id in pending }) }
        Log.i("RPB", "removed ${pending.size} controller(s) never bound")
    }

    /**
     * Deletes the signing keys with no stored pairing (ADR 0033), left by an add cut short before its pairing was
     * saved, except one being bound now. Under the store's lock, so no pairing is saved meanwhile.
     */
    private fun dropUnpairedSigningKeys() {
        val pairings = secrets.value?.pairings ?: return
        // A bound pairing this app can't read (a later version's code) still has its key: delete nothing then.
        if (pairings.any { it.bound && SigningKeys.alias(it.code) == null }) return
        val paired = pairings.mapNotNull { SigningKeys.alias(it.code) }.toSet()
        runCatching { SigningKeys.deleteUnpaired(paired) }
            .onSuccess { if (it > 0) Log.i("RPB", "removed $it signing key(s) with no pairing") }
            .onFailure { Log.w("RPB", "signing keys not checked: ${it.javaClass.simpleName}") }
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

    // These two check and save under the store's lock, so a bind and the QR dialog closing can't both win (ADR 0033).

    /** Binds [publicKey] to controller [id] if it [PairedController.canBind] at [now]; false if it is gone, not pending, or late. */
    @Synchronized
    fun bindController(id: String, publicKey: ByteArray, now: Long): Boolean {
        val controller = secrets.value?.controllers?.firstOrNull { it.id == id } ?: return false
        if (!controller.paired().canBind(now)) return false
        val bound = controller.copy(publicKey = Base64.encodeToString(publicKey, Base64.NO_WRAP), bindUntil = null)
        return update { s -> s.copy(controllers = s.controllers.map { if (it.id == id) bound else it }) }
    }

    /** Removes controller [id] only if it is still pending; true if it was removed. */
    @Synchronized
    fun removePendingController(id: String): Boolean {
        val controller = secrets.value?.controllers?.firstOrNull { it.id == id } ?: return false
        return controller.paired().pending && removeController(id)
    }

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
     * Erases all app data (ADR 0015): secrets, preferences, controller history and the Keystore keys, the pairings'
     * signing keys and the device's among them (ADR 0033), so nothing left behind can be decrypted or sign. The
     * process must restart afterwards; in-memory state is not reset.
     */
    @SuppressLint("ApplySharedPref") // must reach the disk before the process exits
    @Synchronized
    fun wipe() {
        prefs.edit().clear().commit()
        appContext.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        appContext.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        keystore.deleteKeys()
        SigningKeys.deleteAll()
        DeviceKey.delete()
    }

    private companion object {
        const val SECRETS = "secrets"
        const val DATA_KEY = "data_key"
    }
}
