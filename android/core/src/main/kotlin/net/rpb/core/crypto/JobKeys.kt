package net.rpb.core.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.GeneralSecurityException

/** An ephemeral X25519 key pair for one job (ADR 0030). */
class JobKeyPair private constructor(val private: ByteArray, val public: ByteArray) {
    fun destroy() = private.fill(0)

    companion object {
        fun generate(): JobKeyPair {
            val private = X25519.generatePrivateKey()
            return JobKeyPair(private, X25519.publicFromPrivate(private))
        }
    }
}

object JobKeys {
    const val PUBLIC_KEY_BYTES = 32
    private val INFO = "rpb2 job".toByteArray(Charsets.US_ASCII)

    /**
     * `HKDF-SHA256(ikm = X25519(private, peerPublic), salt = K_evt, info = "rpb2 job" ‖ controllerEpk ‖ deviceEpk)`.
     * The salt ties the job key to the pairing, so whoever relays the device's public key can't swap in their own;
     * the shared secret is what the pairing key alone can't recompute once both private keys are gone.
     */
    fun derive(evtKey: ByteArray, private: ByteArray, peerPublic: ByteArray, controllerEpk: ByteArray, deviceEpk: ByteArray): ByteArray {
        if (peerPublic.size != PUBLIC_KEY_BYTES) throw EnvelopeException("bad ephemeral key length ${peerPublic.size}")
        val shared = try {
            X25519.computeSharedSecret(private, peerPublic)
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("bad ephemeral key", e)
        }
        try {
            if (shared.all { it == 0.toByte() }) throw EnvelopeException("low-order ephemeral key")
            return Hkdf.computeHkdf("HMACSHA256", shared, evtKey, INFO + controllerEpk + deviceEpk, 32)
        } finally {
            shared.fill(0)
        }
    }
}

/**
 * Events of one job under its own key (ADR 0030).
 *
 * Wire format: `ver(2) | deviceEpk(32) | nonce(24) | ciphertext | tag(16)`, AAD as in [Envelope] with version 2 and
 * the event direction. The device's ephemeral public key travels in the clear; the job ID doesn't, the controller
 * finds the job by which key opens the message.
 *
 * Takes [key] over and zeroes it once Tink has its own copy, which is only dropped with this envelope, not erased.
 */
class JobEnvelope internal constructor(key: ByteArray, val deviceEpk: ByteArray, deviceId: ByteArray, controllerId: ByteArray) {
    private val aead = try {
        XChaCha20Poly1305(key)
    } finally {
        key.fill(0)
    }
    private val aad = byteArrayOf(VERSION) + deviceId + controllerId + Direction.EVT.code

    fun seal(plaintext: ByteArray): ByteArray = byteArrayOf(VERSION) + deviceEpk + aead.encrypt(plaintext, aad)

    fun open(message: ByteArray): ByteArray {
        val epk = deviceEpk(message) ?: throw EnvelopeException("not a job message")
        if (!epk.contentEquals(deviceEpk)) throw EnvelopeException("another job's key")
        if (message.size < HEADER_BYTES + Envelope.NONCE_BYTES + Envelope.TAG_BYTES) throw EnvelopeException("message too short")
        return try {
            aead.decrypt(message.copyOfRange(HEADER_BYTES, message.size), aad)
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("authentication failed", e)
        }
    }

    companion object {
        const val VERSION: Byte = 2
        private const val HEADER_BYTES = 1 + JobKeys.PUBLIC_KEY_BYTES

        /** The device's ephemeral public key from a job message's header; null if [message] isn't one. */
        fun deviceEpk(message: ByteArray): ByteArray? =
            if (message.size >= HEADER_BYTES && message[0] == VERSION) message.copyOfRange(1, HEADER_BYTES) else null
    }
}
