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
 * Everything a controller needs, handed over once as a QR code shown on the device screen.
 * Physical access to the device screen is the authorisation to pair.
 */
@Serializable
class PairingCode(
    val v: Int = 1,
    @ByteString val deviceId: ByteArray,
    @ByteString val controllerId: ByteArray,
    @ByteString val key: ByteArray,
    val broker: BrokerConfig,
    val deviceName: String = "",
) {
    fun encode(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(cbor.encodeToByteArray(serializer(), this))

    fun envelope(): Envelope = Envelope.forPair(key, deviceId, controllerId)

    companion object {
        const val PREFIX = "rpb1:"
        private val cbor = Cbor { ignoreUnknownKeys = true }

        fun decode(text: String): PairingCode {
            require(text.startsWith(PREFIX)) { "not an RPB pairing code" }
            val code = cbor.decodeFromByteArray(serializer(), Base64.getUrlDecoder().decode(text.removePrefix(PREFIX)))
            require(code.v == 1) { "unsupported pairing code version ${code.v}" }
            require(code.deviceId.size == Keys.ID_BYTES && code.controllerId.size == Keys.ID_BYTES) { "bad id length" }
            require(code.key.size == Keys.MASTER_KEY_BYTES) { "bad key length" }
            return code
        }
    }
}
