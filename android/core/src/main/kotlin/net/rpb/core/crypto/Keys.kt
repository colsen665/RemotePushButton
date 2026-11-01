package net.rpb.core.crypto

import com.google.crypto.tink.subtle.Hkdf
import java.security.SecureRandom

/** Which way a message travels. Each direction has its own key and is bound into the AAD. */
enum class Direction(val code: Byte) {
    /** Controller → device. */
    CMD(1),

    /** Device → controller. */
    EVT(2),
}

class SessionKeys(val cmd: ByteArray, val evt: ByteArray) {
    fun forDirection(direction: Direction): ByteArray = when (direction) {
        Direction.CMD -> cmd
        Direction.EVT -> evt
    }
}

object Keys {
    const val ID_BYTES = 16
    const val MASTER_KEY_BYTES = 32

    private val random = SecureRandom()

    fun random(bytes: Int): ByteArray = ByteArray(bytes).also(random::nextBytes)

    /**
     * Derives one key per direction from the pairing master key with HKDF-SHA256.
     * The salt binds the keys to this device/controller pair.
     */
    fun deriveSessionKeys(master: ByteArray, deviceId: ByteArray, controllerId: ByteArray): SessionKeys {
        require(master.size == MASTER_KEY_BYTES) { "master key must be $MASTER_KEY_BYTES bytes" }
        val salt = deviceId + controllerId
        fun derive(label: String) =
            Hkdf.computeHkdf("HMACSHA256", master, salt, label.toByteArray(Charsets.US_ASCII), 32)
        return SessionKeys(cmd = derive("rpb1 cmd"), evt = derive("rpb1 evt"))
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex string" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
