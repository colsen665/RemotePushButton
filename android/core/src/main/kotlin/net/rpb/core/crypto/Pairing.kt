package net.rpb.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import java.util.Base64

@Serializable
data class BrokerConfig(
    val host: String,
    val port: Int = 8883,
    val tls: Boolean = true,
    val username: String = "",
    val password: String = "",
)

/**
 * A pairing code of a version this app doesn't take. Version 1 comes from a device app older than ADR 0033, whose
 * pairings don't bind a signing key: [tooOld] says to update the device app.
 */
class UnsupportedPairingCode(val version: Int) : IllegalArgumentException("unsupported pairing code version $version") {
    val tooOld: Boolean get() = version < PairingCode.VERSION
}

/**
 * Everything a controller needs, handed over once as a QR code shown on the device screen.
 * Physical access to the device screen is the authorisation to pair, and the controller binds a signing key with its
 * first command (ADR 0033). [devicePub] is the SubjectPublicKeyInfo of the device's signing key, which signs every
 * job's key exchange, so that only the device can answer (ADR 0033).
 */
@Serializable
class PairingCode(
    val v: Int = VERSION,
    @ByteString val deviceId: ByteArray,
    @ByteString val controllerId: ByteArray,
    @ByteString val key: ByteArray,
    @ByteString val devicePub: ByteArray,
    val broker: BrokerConfig,
    val deviceName: String = "",
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(cbor.encodeToByteArray(serializer(), this))

    fun envelope(): Envelope = Envelope.forPair(key, deviceId, controllerId)

    @Serializable
    private class Version(val v: Int = VERSION)

    companion object {
        const val PREFIX = "rpb1:"
        /** Version 2 adds [devicePub], and says the device expects a bind (ADR 0033). */
        const val VERSION = 2
        private val cbor = Cbor { ignoreUnknownKeys = true }

        fun decode(text: String): PairingCode {
            require(text.startsWith(PREFIX)) { "not an RPB pairing code" }
            val bytes = Base64.getUrlDecoder().decode(text.removePrefix(PREFIX))
            // The version first: another version's fields may differ, and it deserves its own message.
            val version = cbor.decodeFromByteArray(Version.serializer(), bytes).v
            if (version != VERSION) throw UnsupportedPairingCode(version)
            val code = cbor.decodeFromByteArray(serializer(), bytes)
            require(code.deviceId.size == Keys.ID_BYTES && code.controllerId.size == Keys.ID_BYTES) { "bad id length" }
            require(code.key.size == Keys.MASTER_KEY_BYTES) { "bad key length" }
            require(CommandSignature.publicKey(code.devicePub) != null) { "bad device key" }
            return code
        }
    }
}
