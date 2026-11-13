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

/** A job message's plaintext: the device's [signature] over the job's key exchange, then the event's [cbor]. */
class JobMessage(val signature: ByteArray, val cbor: ByteArray)

/**
 * Events of one job under its own key (ADR 0030), each carrying the device's signature over the job's key exchange
 * (ADR 0033).
 *
 * Wire format, version 3: `ver(3) | deviceEpk(32) | nonce(24) | XChaCha20-Poly1305(sig(64) | cbor) | tag(16)`, AAD
 * as in [Envelope] with version 3 and the event direction. `sig` is the raw `r | s` of the device's ECDSA P-256 /
 * SHA-256 signature over [transcript], made once per job and the same in every event of it. The device's ephemeral
 * public key travels in the clear; the job ID doesn't, the controller finds the job by which key opens the message.
 *
 * Takes [key] over and zeroes it once Tink has its own copy, which is only dropped with this envelope, not erased.
 */
class JobEnvelope internal constructor(
    key: ByteArray,
    val deviceEpk: ByteArray,
    private val controllerEpk: ByteArray,
    private val deviceId: ByteArray,
    private val controllerId: ByteArray,
) {
    private val aead = try {
        XChaCha20Poly1305(key)
    } finally {
        key.fill(0)
    }
    private val aad = byteArrayOf(VERSION) + deviceId + controllerId + Direction.EVT.code

    /**
     * What the device signs for [job]: `"rpb3 job" | ver(3) | deviceId | controllerId | controllerEpk | deviceEpk | job`,
     * the job ID in UTF-8 and last, as the only field whose length varies.
     */
    fun transcript(job: String): ByteArray =
        TRANSCRIPT_CONTEXT + VERSION + deviceId + controllerId + controllerEpk + deviceEpk + job.toByteArray(Charsets.UTF_8)

    /** The device's signature over [job]'s [transcript], raw `r | s`. Throws what [Signer.sign] throws. */
    fun sign(job: String, signer: Signer): ByteArray = CommandSignature.derToRaw(signer.sign(transcript(job)))

    /** Whether [signature] is the device's, [devicePublicKey], over [job]'s [transcript]. */
    fun verify(job: String, signature: ByteArray, devicePublicKey: ByteArray): Boolean =
        CommandSignature.verify(devicePublicKey, transcript(job), signature)

    /** Seals [plaintext] with the job's [signature] in front of it. */
    fun seal(plaintext: ByteArray, signature: ByteArray): ByteArray {
        require(signature.size == CommandSignature.RAW_BYTES) { "signature must be ${CommandSignature.RAW_BYTES} bytes" }
        return byteArrayOf(VERSION) + deviceEpk + aead.encrypt(signature + plaintext, aad)
    }

    /** Opens [message]; its signature isn't checked here. Throws [EnvelopeException] if it doesn't open. */
    fun open(message: ByteArray): JobMessage {
        val epk = deviceEpk(message) ?: throw EnvelopeException("not a job message")
        if (!epk.contentEquals(deviceEpk)) throw EnvelopeException("another job's key")
        if (message.size < HEADER_BYTES + Envelope.NONCE_BYTES + CommandSignature.RAW_BYTES + Envelope.TAG_BYTES) {
            throw EnvelopeException("message too short")
        }
        val plaintext = try {
            aead.decrypt(message.copyOfRange(HEADER_BYTES, message.size), aad)
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("authentication failed", e)
        }
        return JobMessage(plaintext.copyOfRange(0, CommandSignature.RAW_BYTES), plaintext.copyOfRange(CommandSignature.RAW_BYTES, plaintext.size))
    }

    companion object {
        /** Version 2 (ADR 0030) had no signature, and is refused. */
        const val VERSION: Byte = 3
        private const val HEADER_BYTES = 1 + JobKeys.PUBLIC_KEY_BYTES
        private val TRANSCRIPT_CONTEXT = "rpb3 job".toByteArray(Charsets.US_ASCII)

        /** The device's ephemeral public key from a job message's header; null if [message] isn't a version 3 one. */
        fun deviceEpk(message: ByteArray): ByteArray? =
            if (message.size >= HEADER_BYTES && message[0] == VERSION) message.copyOfRange(1, HEADER_BYTES) else null
    }
}
