package net.rpb.core.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.GeneralSecurityException

class EnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * End-to-end encryption for one device/controller pair.
 *
 * Wire format: `ver(1) | nonce(24) | ciphertext | tag(16)` using XChaCha20-Poly1305 with a random nonce.
 * AAD is `ver | deviceId | controllerId | direction`, so a message can't be replayed onto another
 * pair's topic or reflected back in the opposite direction.
 */
class Envelope(keys: SessionKeys, private val deviceId: ByteArray, private val controllerId: ByteArray) {
    private val cmdAead = XChaCha20Poly1305(keys.cmd)
    private val evtAead = XChaCha20Poly1305(keys.evt)
    private val evtKey = keys.evt

    fun seal(direction: Direction, plaintext: ByteArray): ByteArray {
        // Tink output is nonce(24) | ciphertext | tag(16).
        return byteArrayOf(VERSION) + aead(direction).encrypt(plaintext, aad(direction))
    }

    fun open(direction: Direction, message: ByteArray): ByteArray {
        if (message.size < 1 + NONCE_BYTES + TAG_BYTES) throw EnvelopeException("message too short")
        if (message[0] != VERSION) throw EnvelopeException("unsupported version ${message[0]}")
        return try {
            aead(direction).decrypt(message.copyOfRange(1, message.size), aad(direction))
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("authentication failed", e)
        }
    }

    /**
     * The device's side of a job with forward secrecy (ADR 0030): answers the controller's [controllerEpk] with a
     * fresh key pair, whose private half is erased before this returns.
     */
    fun deviceJob(controllerEpk: ByteArray): JobEnvelope {
        val pair = JobKeyPair.generate()
        try {
            val key = JobKeys.derive(evtKey, pair.private, controllerEpk, controllerEpk, pair.public)
            return JobEnvelope(key, pair.public, deviceId, controllerId)
        } finally {
            pair.destroy()
        }
    }

    /** The controller's side of a job, once [deviceEpk] has arrived in the header of the job's first event. */
    fun controllerJob(pair: JobKeyPair, deviceEpk: ByteArray): JobEnvelope {
        val key = JobKeys.derive(evtKey, pair.private, deviceEpk, pair.public, deviceEpk)
        return JobEnvelope(key, deviceEpk.copyOf(), deviceId, controllerId)
    }

    private fun aead(direction: Direction) = when (direction) {
        Direction.CMD -> cmdAead
        Direction.EVT -> evtAead
    }

    private fun aad(direction: Direction) = byteArrayOf(VERSION) + deviceId + controllerId + direction.code

    companion object {
        const val VERSION: Byte = 1
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16

        fun forPair(master: ByteArray, deviceId: ByteArray, controllerId: ByteArray) =
            Envelope(Keys.deriveSessionKeys(master, deviceId, controllerId), deviceId, controllerId)
    }
}
