package net.rpb.core.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.rpb.core.crypto.Keys

/** Whether the secrets can be read (ADR 0028). */
enum class Vault {
    /** The data key isn't in memory: not unwrapped in this process yet, or forgotten when the controller locked. */
    CLOSED,
    OPEN,

    /** The phone has no screen lock, so the wrapping key can't be made or used. */
    NO_SCREEN_LOCK,

    /** The secrets can't be read again: the wrapping key is gone (the screen lock was removed), or they are damaged. */
    LOST,
}

/** The platform key that wraps the data key: in the app, an Android Keystore key bound to the screen lock. */
interface KeyWrapper {
    fun isScreenLockSet(): Boolean

    /** Makes a new wrapping key, replacing any key a failed first start left behind. */
    fun createKey()

    /** Throws [KeyProblem] when the key can't be used. */
    fun wrap(dataKey: ByteArray): ByteArray

    /** Throws [KeyProblem] when the key can't be used. */
    fun unwrap(wrapped: ByteArray): ByteArray

    /** Decrypts a blob written before ADR 0028; null if the old key is gone. */
    fun legacyDecrypt(blob: ByteArray): ByteArray?

    fun deleteLegacyKey()

    /** For the log: where the key lives and what enforces the screen lock. Never anything secret. */
    fun describe(): String
}

/** Where the wrapped data key and the encrypted blob are kept. */
interface VaultFiles {
    fun wrappedKey(): ByteArray?

    fun blob(): ByteArray?

    /** Writes both, to disk, before returning; false if that failed. */
    fun commit(wrappedKey: ByteArray, blob: ByteArray): Boolean

    /** Writes the blob; it may reach the disk after this returns. */
    fun saveBlob(blob: ByteArray)
}

/**
 * Secrets of type [T] in one blob encrypted with a random data key, which [keys] wraps (ADR 0028). [open] unwraps
 * the data key, which the platform allows only shortly after the screen lock, and keeps it in memory until
 * [close]; every save in between uses the data key alone. A blob from before ADR 0028 moves to a new wrapping key
 * on the first open.
 *
 * [decode] gets null when there is no blob yet. Whatever [decode] fills in with a default is saved back, so a
 * random default (a new ID) stays the same from then on.
 */
class SecretVault<T : Any>(
    private val keys: KeyWrapper,
    private val files: VaultFiles,
    private val encode: (T) -> ByteArray,
    private val decode: (ByteArray?) -> T,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(Vault.CLOSED)
    val state: StateFlow<Vault> = _state

    private var dataKey: ByteArray? = null

    /** The secrets while [state] is [Vault.OPEN], null otherwise. */
    @Volatile
    var value: T? = null
        private set

    /** [KeyWrapper.describe] from the first open in this process. */
    @Volatile
    var keyDescription: String? = null
        private set

    /**
     * Unwraps the data key and reads the secrets. Returns [Vault.CLOSED] when the screen lock must be passed
     * first, and [Vault.LOST] for anything that a retry won't fix, rather than throwing. Slow on a phone: call it
     * off the main thread.
     */
    @Synchronized
    fun open(): Vault {
        if (_state.value == Vault.OPEN) return Vault.OPEN
        val state = try {
            unlock()
            Vault.OPEN
        } catch (e: KeyProblem.NeedsAuthentication) {
            Vault.CLOSED
        } catch (e: KeyProblem.NoScreenLock) {
            Vault.NO_SCREEN_LOCK
        } catch (e: Exception) {
            // Exception names only: a parser's message can quote the decrypted blob.
            log("secrets can't be read: ${generateSequence<Throwable>(e) { it.cause }.take(8).joinToString(" ← ") { it.javaClass.name }}")
            Vault.LOST
        }
        _state.value = state
        return state
    }

    private fun unlock() {
        val wrapped = files.wrappedKey()
        val stored = files.blob()
        val screenLock = keys.isScreenLockSet()
        // Removing the screen lock invalidates the key for good.
        if (wrapped != null && !screenLock) throw KeyProblem.Invalidated()
        if (!screenLock) throw KeyProblem.NoScreenLock()

        val key = if (wrapped != null) keys.unwrap(wrapped) else Keys.random(DATA_KEY_BYTES)
        try {
            val loaded = if (wrapped != null) read(key, stored) else migrate(key, stored)
            if (keyDescription == null) keyDescription = keys.describe().also { log("Keystore key: $it") }
            dataKey = key
            value = loaded
        } catch (e: Throwable) {
            key.fill(0)
            throw e
        }
    }

    private fun read(key: ByteArray, stored: ByteArray?): T {
        val plain = stored?.let { AesGcm.open(key, it) }
        try {
            val loaded = decode(plain)
            val again = encode(loaded)
            if (plain == null || !again.contentEquals(plain)) files.saveBlob(AesGcm.seal(key, again))
            again.fill(0)
            return loaded
        } finally {
            plain?.fill(0)
        }
    }

    /** First start, or the first since the update to ADR 0028: the blob, if any, is under the old key. */
    private fun migrate(key: ByteArray, stored: ByteArray?): T {
        val plain = stored?.let { keys.legacyDecrypt(it) ?: throw KeyProblem.Invalidated() }
        try {
            val loaded = decode(plain)
            keys.createKey()
            val encoded = encode(loaded)
            val blob = AesGcm.seal(key, encoded)
            encoded.fill(0)
            check(files.commit(keys.wrap(key), blob)) { "could not save the wrapped key" }
            // Only once the new blob is on disk: until then a retry reads the old one again.
            keys.deleteLegacyKey()
            log(if (stored != null) "secrets moved to the new Keystore key" else "new Keystore key")
            return loaded
        } finally {
            plain?.fill(0)
        }
    }

    /** Zeroes the data key and drops the secrets; [open] reads them again through the wrapping key. */
    @Synchronized
    fun close() {
        if (_state.value != Vault.OPEN) return
        dataKey?.fill(0)
        dataKey = null
        value = null
        _state.value = Vault.CLOSED
    }

    /** Saves [transform] of the secrets and returns it; null, with nothing saved, while the vault isn't open. */
    @Synchronized
    fun update(transform: (T) -> T): T? {
        val key = dataKey ?: return null
        val updated = transform(value ?: return null)
        val encoded = encode(updated)
        files.saveBlob(AesGcm.seal(key, encoded))
        encoded.fill(0)
        value = updated
        return updated
    }

    private companion object {
        const val DATA_KEY_BYTES = 32
    }
}
