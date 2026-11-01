package net.rpb.core

import net.rpb.core.crypto.toHex
import net.rpb.core.device.BotCommands
import kotlin.test.Test
import kotlin.test.assertEquals

class BotCommandsTest {
    @Test
    fun `no password sends the plain commands`() {
        assertEquals("570100", BotCommands.press("").toHex())
        assertEquals("5702", BotCommands.basicInfo("").toHex())
    }

    @Test
    fun `password adds the flag and its CRC32`() {
        // binascii.crc32(b"secret") == 0x5ca2e8e5
        assertEquals("57115ca2e8e500", BotCommands.press("secret").toHex())
        assertEquals("57125ca2e8e5", BotCommands.basicInfo("secret").toHex())
    }
}
