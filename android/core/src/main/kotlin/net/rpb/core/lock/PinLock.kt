package net.rpb.core.lock

import kotlinx.serialization.Serializable
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.crypto.toHex
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Stored PIN (salted PBKDF2 hash) and the attempt counter; lives with the other secrets (ADR 0015). */
@Serializable
data class PinState(
    val salt: String,
    val hash: String,
    val iterations: Int,
    val wipeOnFailures: Boolean,
    val failures: Int = 0,
    /** How long the current lockout lasts, in ms; 0 when none applies. */
    val lockoutMs: Long = 0,
    /** The clock reading when the lockout started. */
    val lockedAt: Long = 0,
)

sealed interface PinCheck {
    data object Ok : PinCheck

    /** [attemptsLeft] before the app erases itself, or null if wipe is off; [lockedUntil] is 0 when no wait applies. */
    data class Wrong(val attemptsLeft: Int?, val lockedUntil: Long) : PinCheck

    data class LockedOut(val until: Long) : PinCheck

    /** The last allowed attempt failed with wipe on: the caller must erase all app data now. */
    data object Wipe : PinCheck
}

/**
 * App PIN (ADR 0015). After [MAX_FAILURES] wrong PINs in a row the app erases itself if wipe is on; otherwise each
 * further attempt waits, starting at [FIRST_LOCKOUT_MS] and doubling up to [MAX_LOCKOUT_MS]. A failure is counted
 * before the PIN is checked, so killing the app mid-check doesn't reset the counter.
 *
 * [clock] should count from boot rather than follow the wall clock, which whoever holds the phone can set forward.
 * The times in [PinCheck] are on that clock. A clock that went backwards (a reboot) restarts the full wait.
 */
class PinLock(
    private val load: () -> PinState?,
    private val save: (PinState?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val iterations: Int = ITERATIONS,
) {
    val isSet: Boolean get() = load() != null

    val wipeOnFailures: Boolean get() = load()?.wipeOnFailures ?: false

    fun set(pin: String, wipeOnFailures: Boolean) {
        require(isValid(pin)) { "PIN must be $MIN_LENGTH–$MAX_LENGTH digits" }
        val salt = Keys.random(SALT_BYTES)
        save(PinState(salt.toHex(), hash(pin, salt, iterations).toHex(), iterations, wipeOnFailures))
    }

    fun clear() = save(null)

    fun setWipeOnFailures(enabled: Boolean) {
        val state = load() ?: return
        save(state.copy(wipeOnFailures = enabled))
    }

    fun check(pin: String): PinCheck {
        val state = load() ?: return PinCheck.Ok
        val now = clock()
        lockedUntil(state, now)?.let { return PinCheck.LockedOut(it) }

        val failures = state.failures + 1
        val wait = lockout(failures, state.wipeOnFailures) ?: 0
        save(state.copy(failures = failures, lockoutMs = wait, lockedAt = if (wait > 0) now else 0))
        val expected = state.hash.hexToBytes()
        if (MessageDigest.isEqual(hash(pin, state.salt.hexToBytes(), state.iterations), expected)) {
            save(state.copy(failures = 0, lockoutMs = 0, lockedAt = 0))
            return PinCheck.Ok
        }
        if (state.wipeOnFailures && failures >= MAX_FAILURES) return PinCheck.Wipe
        val left = if (state.wipeOnFailures) MAX_FAILURES - failures else null
        return PinCheck.Wrong(left, if (wait > 0) now + wait else 0)
    }

    /** When the current lockout ends, or null if none applies. After a reboot the whole wait runs again from boot. */
    private fun lockedUntil(state: PinState, now: Long): Long? {
        if (state.lockoutMs <= 0) return null
        val start = if (now < state.lockedAt) 0 else state.lockedAt
        return (start + state.lockoutMs).takeIf { now < it }
    }

    private fun lockout(failures: Int, wipe: Boolean): Long? {
        if (wipe || failures < MAX_FAILURES) return null
        val doublings = (failures - MAX_FAILURES).coerceAtMost(MAX_DOUBLINGS)
        return (FIRST_LOCKOUT_MS shl doublings).coerceAtMost(MAX_LOCKOUT_MS)
    }

    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        const val MAX_FAILURES = 3
        const val FIRST_LOCKOUT_MS = 30_000L
        const val MAX_LOCKOUT_MS = 3_600_000L
        private const val MAX_DOUBLINGS = 7

        /** The app may stay in the background this long without asking for the PIN again. */
        const val GRACE_MS = 60_000L
        const val ITERATIONS = 120_000
        private const val SALT_BYTES = 16

        fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

        /** True when the app comes back after more than [GRACE_MS] away, or starts for the first time ([backgroundedAt] null). */
        fun needsUnlock(backgroundedAt: Long?, now: Long) = backgroundedAt == null || now - backgroundedAt > GRACE_MS

        private fun hash(pin: String, salt: ByteArray, iterations: Int): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
            try {
                return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}
