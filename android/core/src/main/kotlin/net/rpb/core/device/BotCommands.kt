package net.rpb.core.device

import java.util.zip.CRC32

/**
 * SwitchBot Bot command bytes (ADRs 0003, 0025). With a password, the command byte gets `0x10` added and the CRC32
 * of the password follows it, big-endian. A blank password sends the plain command.
 */
object BotCommands {
    fun press(password: String): ByteArray = command(0x01, password, byteArrayOf(0x00))

    fun basicInfo(password: String): ByteArray = command(0x02, password)

    private fun command(action: Int, password: String, suffix: ByteArray = ByteArray(0)): ByteArray {
        if (password.isEmpty()) return byteArrayOf(PREFIX, action.toByte()) + suffix
        val crc = CRC32().apply { update(password.toByteArray(Charsets.UTF_8)) }.value
        val key = ByteArray(4) { (crc shr (24 - 8 * it)).toByte() }
        return byteArrayOf(PREFIX, (action or 0x10).toByte()) + key + suffix
    }

    private const val PREFIX: Byte = 0x57
}
