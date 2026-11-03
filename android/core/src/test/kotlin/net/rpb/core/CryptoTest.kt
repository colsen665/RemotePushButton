package net.rpb.core

import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.JobKeys
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `job envelopes agree on both sides, and the pairing key alone opens nothing`() {
        val controllerKeys = JobKeyPair.generate()
        val device = envelope.deviceJob(controllerKeys.public)
        val sealed = device.seal(plaintext)
        assertEquals(JobEnvelope.VERSION, sealed[0])
        assertEquals(1 + 32 + 24 + plaintext.size + 16, sealed.size)
        val deviceEpk = JobEnvelope.deviceEpk(sealed)!!
        assertContentEquals(device.deviceEpk, deviceEpk)
        assertContentEquals(plaintext, envelope.controllerJob(controllerKeys, deviceEpk).open(sealed))

        // Someone holding K and both public keys, but neither private key, after the job.
        assertFailsWith<EnvelopeException> { envelope.open(Direction.EVT, sealed) }
        val guess = JobKeyPair.generate()
        assertFailsWith<EnvelopeException> { envelope.controllerJob(guess, deviceEpk).open(sealed) }
        // The controller erases its private key when the job ends.
        controllerKeys.destroy()
        assertTrue(controllerKeys.private.all { it == 0.toByte() })
    }

    @Test
    fun `every job gets its own key`() {
        val controllerKeys = JobKeyPair.generate()
        val first = envelope.deviceJob(controllerKeys.public).seal(plaintext)
        val second = envelope.deviceJob(controllerKeys.public).seal(plaintext)
        assertFalse(JobEnvelope.deviceEpk(first)!!.contentEquals(JobEnvelope.deviceEpk(second)!!))
        val firstJob = envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(first)!!)
        assertFailsWith<EnvelopeException> { firstJob.open(second) }
    }

    @Test
    fun `the relay can't swap in its own device key`() {
        val controllerKeys = JobKeyPair.generate()
        // Without K the relay derives with another salt; the controller's derivation then disagrees.
        val forged = Envelope.forPair(Keys.random(32), deviceId, controllerId).deviceJob(controllerKeys.public).seal(plaintext)
        assertFailsWith<EnvelopeException> { envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(forged)!!).open(forged) }
        // Nor can it move a job message to another pair's topic.
        val sealed = envelope.deviceJob(controllerKeys.public).seal(plaintext)
        val otherPair = Envelope.forPair(master, deviceId, Keys.random(16))
        assertFailsWith<EnvelopeException> { otherPair.controllerJob(controllerKeys, JobEnvelope.deviceEpk(sealed)!!).open(sealed) }
    }

    @Test
    fun `any flipped bit in a job message is rejected`() {
        val controllerKeys = JobKeyPair.generate()
        val sealed = envelope.deviceJob(controllerKeys.public).seal(plaintext)
        val job = envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(sealed)!!)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<EnvelopeException>("byte $i") { job.open(tampered) }
        }
        assertNull(JobEnvelope.deviceEpk(envelope.seal(Direction.EVT, plaintext)), "version 1 has no device key")
    }

    @Test
    fun `the job key is zeroed once its envelope holds it`() {
        val controllerKeys = JobKeyPair.generate()
        val sealed = envelope.deviceJob(controllerKeys.public).seal(plaintext)
        val deviceEpk = JobEnvelope.deviceEpk(sealed)!!
        val evt = Keys.deriveSessionKeys(master, deviceId, controllerId).evt
        val key = JobKeys.derive(evt, controllerKeys.private, deviceEpk, controllerKeys.public, deviceEpk)
        val job = JobEnvelope(key, deviceEpk, deviceId, controllerId)
        assertTrue(key.all { it == 0.toByte() })
        assertContentEquals(plaintext, job.open(sealed))
    }

    @Test
    fun `a bad ephemeral key is refused`() {
        assertFailsWith<EnvelopeException> { envelope.deviceJob(ByteArray(31)) }
        // All zeros is a low-order point: the shared secret would be zero whatever the private key.
        assertFailsWith<EnvelopeException> { envelope.deviceJob(ByteArray(32)) }
    }
}
