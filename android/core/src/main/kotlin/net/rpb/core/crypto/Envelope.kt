package net.rpb.core.crypto

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import java.security.GeneralSecurityException

open class EnvelopeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A command that opened under the pairing key but isn't signed by the key it must be (ADR 0033). */
class CommandSignatureException(message: String) : EnvelopeException(message)

/**
 * End-to-end encryption for one device/controller pair, XChaCha20-Poly1305 with a random nonce under a key per
 * direction. The AAD binds each message to its pair and direction, so it can't be replayed onto another pair's topic
 * or reflected back.
 *
 * Events go under a key per job, in a [JobEnvelope] signed by the device; nothing goes under the event key itself
 * (ADR 0033).
 *
 * Commands, version 3, signed then encrypted, so the relay never sees the signature (ADR 0033):
 * `ver(3) | nonce(24) | XChaCha20-Poly1305(sig(64) | cbor) | tag(16)`, AAD `ver | deviceId | controllerId | 0x01`,
 * where `sig` is the raw `r | s` of ECDSA P-256 / SHA-256 over `"rpb3 cmd" | deviceId | controllerId | cbor`.
 */
class Envelope(keys: SessionKeys, private val deviceId: ByteArray, private val controllerId: ByteArray) {
    private val cmdAead = XChaCha20Poly1305(keys.cmd)
    private val evtKey = keys.evt

    // Tink output is nonce(24) | ciphertext | tag(16).

    /** Signs [cbor] with [signer] and seals both. Throws what [Signer.sign] throws, such as a [net.rpb.core.vault.KeyProblem]. */
    fun sealCommand(cbor: ByteArray, signer: Signer): ByteArray {
        val signature = CommandSignature.derToRaw(signer.sign(signedPart(cbor)))
        return byteArrayOf(COMMAND_VERSION) + cmdAead.encrypt(signature + cbor, aad(COMMAND_VERSION, Direction.CMD))
    }

    /**
     * The command's CBOR, once it has opened under the pairing key and its signature checks out against the
     * SubjectPublicKeyInfo [publicKey] picks for it. [publicKey] sees the CBOR before the signature is checked, so that
     * a `bind` can name its own key; null means no key will do.
     *
     * @throws EnvelopeException if it doesn't open, [CommandSignatureException] if it opens but isn't signed by that key.
     */
    fun openCommand(message: ByteArray, publicKey: (cbor: ByteArray) -> ByteArray?): ByteArray {
        val plaintext = decrypt(cmdAead, COMMAND_VERSION, Direction.CMD, message, CommandSignature.RAW_BYTES)
        val signature = plaintext.copyOfRange(0, CommandSignature.RAW_BYTES)
        val cbor = plaintext.copyOfRange(CommandSignature.RAW_BYTES, plaintext.size)
        val key = publicKey(cbor) ?: throw CommandSignatureException("no key to check the command with")
        if (!CommandSignature.verify(key, signedPart(cbor), signature)) throw CommandSignatureException("bad command signature")
        return cbor
    }

    /** [openCommand] with a known key. */
    fun openCommand(message: ByteArray, publicKey: ByteArray): ByteArray = openCommand(message) { publicKey }

    /**
     * The device's side of a job with forward secrecy (ADR 0030): answers the controller's [controllerEpk] with a
     * fresh key pair, whose private half is erased before this returns. Throws [EnvelopeException] for an unusable
     * [controllerEpk]. The events it seals need the device's signature from [JobEnvelope.sign] (ADR 0033).
     */
    fun deviceJob(controllerEpk: ByteArray): JobEnvelope {
        val pair = JobKeyPair.generate()
        try {
            val key = JobKeys.derive(evtKey, pair.private, controllerEpk, controllerEpk, pair.public)
            return JobEnvelope(key, pair.public, controllerEpk.copyOf(), deviceId, controllerId)
        } finally {
            pair.destroy()
        }
    }

    /** The controller's side of a job, once [deviceEpk] has arrived in the header of the job's first event. */
    fun controllerJob(pair: JobKeyPair, deviceEpk: ByteArray): JobEnvelope {
        val key = JobKeys.derive(evtKey, pair.private, deviceEpk, pair.public, deviceEpk)
        return JobEnvelope(key, deviceEpk.copyOf(), pair.public.copyOf(), deviceId, controllerId)
    }

    private fun decrypt(aead: XChaCha20Poly1305, version: Byte, direction: Direction, message: ByteArray, minPlaintext: Int): ByteArray {
        if (message.size < 1 + NONCE_BYTES + minPlaintext + TAG_BYTES) throw EnvelopeException("message too short")
        if (message[0] != version) throw EnvelopeException("unsupported version ${message[0]}")
        return try {
            aead.decrypt(message.copyOfRange(1, message.size), aad(version, direction))
        } catch (e: GeneralSecurityException) {
            throw EnvelopeException("authentication failed", e)
        }
    }

    private fun aad(version: Byte, direction: Direction) = byteArrayOf(version) + deviceId + controllerId + direction.code

    private fun signedPart(cbor: ByteArray) = SIGNATURE_CONTEXT + deviceId + controllerId + cbor

    companion object {
        const val COMMAND_VERSION: Byte = 3
        const val NONCE_BYTES = 24
        const val TAG_BYTES = 16
        private val SIGNATURE_CONTEXT = "rpb3 cmd".toByteArray(Charsets.US_ASCII)

        fun forPair(master: ByteArray, deviceId: ByteArray, controllerId: ByteArray) =
            Envelope(Keys.deriveSessionKeys(master, deviceId, controllerId), deviceId, controllerId)
    }
}
