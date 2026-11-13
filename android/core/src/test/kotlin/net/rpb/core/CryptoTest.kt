package net.rpb.core

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.CommandSignatureException
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.JobKeys
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.UnsupportedPairingCode
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.vault.KeyProblem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIsNot
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.util.Base64

class CryptoTest {
    private val deviceId = Keys.random(16)
    private val controllerId = Keys.random(16)
    private val master = Keys.random(32)
    private val envelope = Envelope.forPair(master, deviceId, controllerId)
    private val plaintext = "press 123456".toByteArray()
    private val signer = SoftwareSigner()
    private val deviceSigner = SoftwareSigner()

    /** A command built by hand from the layout in ADR 0033, as someone holding K could build it. */
    private fun handBuilt(
        version: Byte = 3,
        signature: ByteArray? = null,
        signedDevice: ByteArray = deviceId,
        signedController: ByteArray = controllerId,
        body: ByteArray = plaintext,
        by: SoftwareSigner = signer,
    ): ByteArray {
        val sig = signature ?: CommandSignature.derToRaw(by.sign("rpb3 cmd".toByteArray() + signedDevice + signedController + body))
        val aead = XChaCha20Poly1305(Keys.deriveSessionKeys(master, deviceId, controllerId).cmd)
        return byteArrayOf(version) + aead.encrypt(sig + body, byteArrayOf(version) + deviceId + controllerId + Direction.CMD.code)
    }

    @Test
    fun `signed commands round-trip under version 3`() {
        val sealed = envelope.sealCommand(plaintext, signer)
        assertEquals(Envelope.COMMAND_VERSION, sealed[0])
        assertEquals(1 + 24 + 64 + plaintext.size + 16, sealed.size)
        assertContentEquals(plaintext, envelope.openCommand(sealed, signer.publicKey))
        // The layout in ADR 0033, built independently.
        assertContentEquals(plaintext, envelope.openCommand(handBuilt(), signer.publicKey))
    }

    @Test
    fun `the key to check with can come from the command itself`() {
        val sealed = envelope.sealCommand(plaintext, signer)
        var seen: ByteArray? = null
        assertContentEquals(plaintext, envelope.openCommand(sealed) { seen = it; signer.publicKey })
        assertContentEquals(plaintext, seen)
        assertFailsWith<CommandSignatureException> { envelope.openCommand(sealed) { null } }
    }

    @Test
    fun `ciphertext hides plaintext and nonces differ`() {
        val a = envelope.sealCommand(plaintext, signer)
        val b = envelope.sealCommand(plaintext, signer)
        assertFalse(a.contentEquals(b))
        assertFalse(String(a, Charsets.ISO_8859_1).contains("press"))
        val job = envelope.deviceJob(JobKeyPair.generate().public)
        assertFalse(String(job.seal(plaintext, job.sign("j", deviceSigner)), Charsets.ISO_8859_1).contains("press"))
    }

    @Test
    fun `a command signed by another key is refused`() {
        val sealed = envelope.sealCommand(plaintext, SoftwareSigner())
        assertFailsWith<CommandSignatureException> { envelope.openCommand(sealed, signer.publicKey) }
    }

    @Test
    fun `any flipped bit is rejected`() {
        val sealed = envelope.sealCommand(plaintext, signer)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<EnvelopeException>("byte $i") { envelope.openCommand(tampered, signer.publicKey) }
        }
    }

    @Test
    fun `a signature over another device or controller is refused`() {
        assertFailsWith<CommandSignatureException> { envelope.openCommand(handBuilt(signedDevice = Keys.random(16)), signer.publicKey) }
        assertFailsWith<CommandSignatureException> { envelope.openCommand(handBuilt(signedController = Keys.random(16)), signer.publicKey) }
    }

    @Test
    fun `a command for another pair, or under another key, is refused`() {
        val sealed = envelope.sealCommand(plaintext, signer)
        for (other in listOf(
            Envelope.forPair(Keys.random(32), deviceId, controllerId),
            Envelope.forPair(master, Keys.random(16), controllerId),
            Envelope.forPair(master, deviceId, Keys.random(16)),
        )) {
            val e = assertFailsWith<EnvelopeException> { other.openCommand(sealed, signer.publicKey) }
            assertIsNot<CommandSignatureException>(e)
        }
    }

    @Test
    fun `version 1 commands are refused`() {
        val aead = XChaCha20Poly1305(Keys.deriveSessionKeys(master, deviceId, controllerId).cmd)
        val v1 = byteArrayOf(1) + aead.encrypt(plaintext, byteArrayOf(1) + deviceId + controllerId + Direction.CMD.code)
        val e = assertFailsWith<EnvelopeException> { envelope.openCommand(v1, signer.publicKey) }
        assertIsNot<CommandSignatureException>(e)
        // Nor does relabelling it, or a signed command, as version 3 or 1 get through.
        assertFailsWith<EnvelopeException> { envelope.openCommand(v1.copyOf().also { it[0] = 3 }, signer.publicKey) }
        assertFailsWith<EnvelopeException> { envelope.openCommand(handBuilt(version = 1), signer.publicKey) }
        assertFailsWith<EnvelopeException> { envelope.openCommand(envelope.sealCommand(plaintext, signer).also { it[0] = 1 }, signer.publicKey) }
    }

    @Test
    fun `wrong direction is rejected (no reflection)`() {
        // Someone holding K seals an event under the command key's AAD layout but with the event key, and the other way round.
        val keys = Keys.deriveSessionKeys(master, deviceId, controllerId)
        val asEvent = byteArrayOf(3) + XChaCha20Poly1305(keys.evt).encrypt(ByteArray(64) + plaintext, byteArrayOf(3) + deviceId + controllerId + Direction.EVT.code)
        assertFailsWith<EnvelopeException> { envelope.openCommand(asEvent, signer.publicKey) }
        val withEventAad = byteArrayOf(3) + XChaCha20Poly1305(keys.cmd).encrypt(ByteArray(64) + plaintext, byteArrayOf(3) + deviceId + controllerId + Direction.EVT.code)
        assertFailsWith<EnvelopeException> { envelope.openCommand(withEventAad, signer.publicKey) }
    }

    @Test
    fun `a malformed signature from someone holding K is refused, not thrown`() {
        val n = "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551".hexToBytes()
        val one = ByteArray(31) + byteArrayOf(1)
        for (bad in listOf(ByteArray(64), Keys.random(64), ByteArray(64) { -1 }, n + one, one + n, ByteArray(32) + one, one + ByteArray(32))) {
            assertFailsWith<CommandSignatureException> { envelope.openCommand(handBuilt(signature = bad), signer.publicKey) }
        }
        val other = SoftwareSigner(SoftwareSigner.generate("secp384r1"))
        assertFailsWith<CommandSignatureException> { envelope.openCommand(handBuilt(signature = Keys.random(64)), other.publicKey) }
        assertFailsWith<CommandSignatureException> { envelope.openCommand(envelope.sealCommand(plaintext, signer), ByteArray(0)) }
    }

    @Test
    fun `a signer whose window has passed stops the command`() {
        val locked = SoftwareSigner().apply { authenticated = false }
        assertFailsWith<KeyProblem.NeedsAuthentication> { envelope.sealCommand(plaintext, locked) }
    }

    @Test
    fun `truncated message is rejected`() {
        assertFailsWith<EnvelopeException> { envelope.openCommand(ByteArray(10), signer.publicKey) }
        // Long enough for an event, too short to hold a signature.
        assertFailsWith<EnvelopeException> { envelope.openCommand(byteArrayOf(3) + ByteArray(24 + 63 + 16), signer.publicKey) }
    }

    @Test
    fun `pairing code round-trips at version 2 and yields a working envelope`() {
        val code = PairingCode(
            deviceId = deviceId,
            controllerId = controllerId,
            key = master,
            devicePub = deviceSigner.publicKey,
            broker = BrokerConfig("example.hivemq.cloud", 8883, true, "ctrl", "secret"),
            deviceName = "Lab token",
        )
        assertEquals(2, code.v)
        val text = code.encode()
        assert(text.startsWith("rpb1:"))
        val decoded = PairingCode.decode(text)
        assertEquals(2, decoded.v)
        assertContentEquals(deviceId, decoded.deviceId)
        assertContentEquals(controllerId, decoded.controllerId)
        assertContentEquals(master, decoded.key)
        assertContentEquals(deviceSigner.publicKey, decoded.devicePub)
        assertEquals(code.broker, decoded.broker)
        assertEquals("Lab token", decoded.deviceName)
        assertContentEquals(plaintext, envelope.openCommand(decoded.envelope().sealCommand(plaintext, signer), signer.publicKey))
    }

    @Test
    fun `version 1 pairing codes are refused as too old`() {
        // Version 1's layout, which had no device key.
        val old = OldPairingCode(1, deviceId, controllerId, master, BrokerConfig("example.hivemq.cloud"))
        val text = PairingCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Cbor.encodeToByteArray(OldPairingCode.serializer(), old))
        val e = assertFailsWith<UnsupportedPairingCode> { PairingCode.decode(text) }
        assertEquals(1, e.version)
        assertTrue(e.tooOld)
        val newer = PairingCode(v = 3, deviceId = deviceId, controllerId = controllerId, key = master, devicePub = ByteArray(0), broker = BrokerConfig("example.hivemq.cloud"))
        assertFalse(assertFailsWith<UnsupportedPairingCode> { PairingCode.decode(newer.encode()) }.tooOld)
    }

    @Serializable
    private class OldPairingCode(
        val v: Int,
        @ByteString val deviceId: ByteArray,
        @ByteString val controllerId: ByteArray,
        @ByteString val key: ByteArray,
        val broker: BrokerConfig,
    )

    @Test
    fun `a version 2 pairing code needs a P-256 device key`() {
        fun code(devicePub: ByteArray) =
            PairingCode(deviceId = deviceId, controllerId = controllerId, key = master, devicePub = devicePub, broker = BrokerConfig("example.hivemq.cloud")).encode()
        val good = deviceSigner.publicKey
        val offCurve = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val p384 = SoftwareSigner(SoftwareSigner.generate("secp384r1")).publicKey
        for (bad in listOf(ByteArray(0), offCurve, p384, good.copyOf(good.size - 1))) {
            assertFailsWith<IllegalArgumentException> { PairingCode.decode(code(bad)) }
        }
        // Left out altogether, as a version 2 code from before the device key had it.
        val missing = OldPairingCode(2, deviceId, controllerId, master, BrokerConfig("example.hivemq.cloud"))
        val text = PairingCode.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(Cbor.encodeToByteArray(OldPairingCode.serializer(), missing))
        assertFailsWith<IllegalArgumentException> { PairingCode.decode(text) }
        assertContentEquals(good, PairingCode.decode(code(good)).devicePub)
    }

    @Test
    fun `garbage pairing codes are rejected`() {
        assertFailsWith<IllegalArgumentException> { PairingCode.decode("https://example.com") }
        assertFailsWith<Exception> { PairingCode.decode("rpb1:AAAA") }
    }

    /** Seals [plaintext] as the device does for [job]: signed by the device's key over the job's key exchange. */
    private fun JobEnvelope.sealSigned(plaintext: ByteArray, job: String = "0123456789abcdef") = seal(plaintext, sign(job, deviceSigner))

    /** The transcript as ADR 0033 lays it out, built independently of [JobEnvelope.transcript]. */
    private fun transcript(controllerEpk: ByteArray, deviceEpk: ByteArray, job: String, device: ByteArray = deviceId, controller: ByteArray = controllerId) =
        "rpb3 job".toByteArray() + byteArrayOf(3) + device + controller + controllerEpk + deviceEpk + job.toByteArray()

    @Test
    fun `job envelopes agree on both sides, and the pairing key alone opens nothing`() {
        val controllerKeys = JobKeyPair.generate()
        val device = envelope.deviceJob(controllerKeys.public)
        val sealed = device.sealSigned(plaintext)
        assertEquals(JobEnvelope.VERSION, sealed[0])
        assertEquals(1 + 32 + 24 + 64 + plaintext.size + 16, sealed.size)
        val deviceEpk = JobEnvelope.deviceEpk(sealed)!!
        assertContentEquals(device.deviceEpk, deviceEpk)
        val controller = envelope.controllerJob(controllerKeys, deviceEpk)
        val opened = controller.open(sealed)
        assertContentEquals(plaintext, opened.cbor)
        assertTrue(controller.verify("0123456789abcdef", opened.signature, deviceSigner.publicKey))

        // Someone holding K and both public keys, but neither private key, after the job.
        val guess = JobKeyPair.generate()
        assertFailsWith<EnvelopeException> { envelope.controllerJob(guess, deviceEpk).open(sealed) }
        // The controller erases its private key when the job ends.
        controllerKeys.destroy()
        assertTrue(controllerKeys.private.all { it == 0.toByte() })
    }

    @Test
    fun `the device signs the transcript in ADR 0033, the same on both sides`() {
        val controllerKeys = JobKeyPair.generate()
        val device = envelope.deviceJob(controllerKeys.public)
        val controller = envelope.controllerJob(controllerKeys, device.deviceEpk)
        val expected = transcript(controllerKeys.public, device.deviceEpk, "0123456789abcdef")
        assertContentEquals(expected, device.transcript("0123456789abcdef"))
        assertContentEquals(expected, controller.transcript("0123456789abcdef"))
        val byHand = CommandSignature.derToRaw(deviceSigner.sign(expected))
        assertTrue(controller.verify("0123456789abcdef", byHand, deviceSigner.publicKey))
        assertTrue(controller.verify("0123456789abcdef", device.sign("0123456789abcdef", deviceSigner), deviceSigner.publicKey))
    }

    @Test
    fun `a job signature by another key, over another job, controller, device or swapped keys, is refused`() {
        val controllerKeys = JobKeyPair.generate()
        val device = envelope.deviceJob(controllerKeys.public)
        val controller = envelope.controllerJob(controllerKeys, device.deviceEpk)
        val job = "0123456789abcdef"
        val ctl = controllerKeys.public
        val dev = device.deviceEpk
        fun signed(message: ByteArray, by: SoftwareSigner = deviceSigner) = CommandSignature.derToRaw(by.sign(message))
        val forgeries = mapOf(
            "another key" to signed(transcript(ctl, dev, job), SoftwareSigner()),
            "another job" to signed(transcript(ctl, dev, "fedcba9876543210")),
            "another controller" to signed(transcript(ctl, dev, job, controller = Keys.random(16))),
            "another device" to signed(transcript(ctl, dev, job, device = Keys.random(16))),
            "swapped keys" to signed(transcript(dev, ctl, job)),
            "another device key" to signed(transcript(ctl, JobKeyPair.generate().public, job)),
            "the controller's command context" to signed("rpb3 cmd".toByteArray() + deviceId + controllerId + ctl + dev + job.toByteArray()),
            "unsigned" to ByteArray(64),
        )
        for ((what, signature) in forgeries) {
            assertFalse(controller.verify(job, signature, deviceSigner.publicKey), what)
            val opened = controller.open(device.seal(plaintext, signature))
            assertFalse(controller.verify(job, opened.signature, deviceSigner.publicKey), what)
        }
        assertTrue(controller.verify(job, signed(transcript(ctl, dev, job)), deviceSigner.publicKey))
    }

    @Test
    fun `version 2 job messages, without a signature, are refused`() {
        val controllerKeys = JobKeyPair.generate()
        val device = envelope.deviceJob(controllerKeys.public)
        // Version 2's layout (ADR 0030), by someone holding K who made the job key with its own ephemeral key.
        val evt = Keys.deriveSessionKeys(master, deviceId, controllerId).evt
        val own = JobKeyPair.generate()
        val key = JobKeys.derive(evt, own.private, controllerKeys.public, controllerKeys.public, own.public)
        val v2 = byteArrayOf(2) + own.public + XChaCha20Poly1305(key).encrypt(plaintext, byteArrayOf(2) + deviceId + controllerId + Direction.EVT.code)
        assertNull(JobEnvelope.deviceEpk(v2))
        val relabelled = v2.copyOf().also { it[0] = 3 }
        assertFailsWith<EnvelopeException> { envelope.controllerJob(controllerKeys, own.public).open(relabelled) }
        assertFailsWith<EnvelopeException> { envelope.controllerJob(controllerKeys, device.deviceEpk).open(relabelled) }
    }

    @Test
    fun `every job gets its own key`() {
        val controllerKeys = JobKeyPair.generate()
        val first = envelope.deviceJob(controllerKeys.public).sealSigned(plaintext)
        val second = envelope.deviceJob(controllerKeys.public).sealSigned(plaintext)
        assertFalse(JobEnvelope.deviceEpk(first)!!.contentEquals(JobEnvelope.deviceEpk(second)!!))
        val firstJob = envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(first)!!)
        assertFailsWith<EnvelopeException> { firstJob.open(second) }
    }

    @Test
    fun `the relay can't swap in its own device key`() {
        val controllerKeys = JobKeyPair.generate()
        // Without K the relay derives with another salt; the controller's derivation then disagrees.
        val forged = Envelope.forPair(Keys.random(32), deviceId, controllerId).deviceJob(controllerKeys.public).sealSigned(plaintext)
        assertFailsWith<EnvelopeException> { envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(forged)!!).open(forged) }
        // Nor can it move a job message to another pair's topic.
        val sealed = envelope.deviceJob(controllerKeys.public).sealSigned(plaintext)
        val otherPair = Envelope.forPair(master, deviceId, Keys.random(16))
        assertFailsWith<EnvelopeException> { otherPair.controllerJob(controllerKeys, JobEnvelope.deviceEpk(sealed)!!).open(sealed) }
    }

    @Test
    fun `any flipped bit in a job message is rejected`() {
        val controllerKeys = JobKeyPair.generate()
        val sealed = envelope.deviceJob(controllerKeys.public).sealSigned(plaintext)
        val job = envelope.controllerJob(controllerKeys, JobEnvelope.deviceEpk(sealed)!!)
        for (i in sealed.indices) {
            val tampered = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFailsWith<EnvelopeException>("byte $i") { job.open(tampered) }
        }
        assertFailsWith<EnvelopeException>("too short to hold a signature") { job.open(sealed.copyOf(1 + 32 + 24 + 63 + 16)) }
    }

    @Test
    fun `the job key is zeroed once its envelope holds it`() {
        val controllerKeys = JobKeyPair.generate()
        val sealed = envelope.deviceJob(controllerKeys.public).sealSigned(plaintext)
        val deviceEpk = JobEnvelope.deviceEpk(sealed)!!
        val evt = Keys.deriveSessionKeys(master, deviceId, controllerId).evt
        val key = JobKeys.derive(evt, controllerKeys.private, deviceEpk, controllerKeys.public, deviceEpk)
        val job = JobEnvelope(key, deviceEpk, controllerKeys.public, deviceId, controllerId)
        assertTrue(key.all { it == 0.toByte() })
        assertContentEquals(plaintext, job.open(sealed).cbor)
    }

    @Test
    fun `a bad ephemeral key is refused`() {
        assertFailsWith<EnvelopeException> { envelope.deviceJob(ByteArray(31)) }
        // All zeros is a low-order point: the shared secret would be zero whatever the private key.
        assertFailsWith<EnvelopeException> { envelope.deviceJob(ByteArray(32)) }
    }
}
