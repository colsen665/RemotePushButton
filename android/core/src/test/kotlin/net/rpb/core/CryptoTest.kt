package net.rpb.core

import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class CryptoTest {
    private val deviceId = Keys.random(16)
    private val controllerId = Keys.random(16)
    private val master = Keys.random(32)
    private val envelope = Envelope.forPair(master, deviceId, controllerId)
    private val plaintext = "press 123456".toByteArray()

    @Test
    fun `seal then open round-trips in both directions`() {
        for (dir in Direction.entries) {
            assertContentEquals(plaintext, envelope.open(dir, envelope.seal(dir, plaintext)))
        }
    }

    @Test
    fun `ciphertext hides plaintext and nonces differ`() {
        val a = envelope.seal(Direction.CMD, plaintext)
        val b = envelope.seal(Direction.CMD, plaintext)
        assertFalse(a.contentEquals(b))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("press"))
        assertEquals(1 + 24 + plaintext.size + 16, a.size)
    }

    @Test
    fun `any flipped bit is rejected`() {
        val sealed = envelope.seal(Direction.CMD, plaintext)
        // Skip the version byte (rejected separately); every other byte covers nonce, ciphertext and tag.
        for (i in 1 until sealed.size) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<EnvelopeException>("byte $i") { envelope.open(Direction.CMD, tampered) }
        }
        val badVersion = sealed.copyOf().also { it[0] = 9 }
        assertFailsWith<EnvelopeException> { envelope.open(Direction.CMD, badVersion) }
    }

    @Test
    fun `wrong direction is rejected (no reflection)`() {
        val sealed = envelope.seal(Direction.CMD, plaintext)
        assertFailsWith<EnvelopeException> { envelope.open(Direction.EVT, sealed) }
    }

    @Test
    fun `wrong key or other pair is rejected`() {
        val sealed = envelope.seal(Direction.CMD, plaintext)
        val otherKey = Envelope.forPair(Keys.random(32), deviceId, controllerId)
        val otherController = Envelope.forPair(master, deviceId, Keys.random(16))
        assertFailsWith<EnvelopeException> { otherKey.open(Direction.CMD, sealed) }
        assertFailsWith<EnvelopeException> { otherController.open(Direction.CMD, sealed) }
    }

    @Test
    fun `truncated message is rejected`() {
        assertFailsWith<EnvelopeException> { envelope.open(Direction.CMD, ByteArray(10)) }
    }

    @Test
    fun `pairing code round-trips and yields a working envelope`() {
        val code = PairingCode(
            deviceId = deviceId,
            controllerId = controllerId,
            key = master,
            broker = BrokerConfig("example.hivemq.cloud", 8883, true, "ctrl", "secret"),
            deviceName = "Lab token",
        )
        val text = code.encode()
        assert(text.startsWith("rpb1:"))
        val decoded = PairingCode.decode(text)
        assertContentEquals(deviceId, decoded.deviceId)
        assertContentEquals(controllerId, decoded.controllerId)
        assertContentEquals(master, decoded.key)
        assertEquals(code.broker, decoded.broker)
        assertEquals("Lab token", decoded.deviceName)
        assertContentEquals(plaintext, envelope.open(Direction.CMD, decoded.envelope().seal(Direction.CMD, plaintext)))
    }

    @Test
    fun `garbage pairing codes are rejected`() {
        assertFailsWith<IllegalArgumentException> { PairingCode.decode("https://example.com") }
        assertFailsWith<Exception> { PairingCode.decode("rpb1:AAAA") }
    }
}
