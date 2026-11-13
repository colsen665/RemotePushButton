package net.rpb.core

import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.crypto.toHex
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandSignatureTest {
    private val signer = SoftwareSigner()
    private val message = "rpb3 cmd and a command".toByteArray()
    private val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    private fun raw(r: BigInteger, s: BigInteger) = scalar(r) + scalar(s)
    private fun scalar(v: BigInteger) = v.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }

    @Test
    fun `a signature checks out against its key and message only`() {
        val raw = CommandSignature.derToRaw(signer.sign(message))
        assertEquals(64, raw.size)
        assertTrue(CommandSignature.verify(signer.publicKey, message, raw))
        assertFalse(CommandSignature.verify(signer.publicKey, message + 0, raw))
        assertFalse(CommandSignature.verify(SoftwareSigner().publicKey, message, raw))
        for (i in raw.indices) {
            val flipped = raw.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            assertFalse(CommandSignature.verify(signer.publicKey, message, flipped), "byte $i")
        }
    }

    @Test
    fun `DER and raw convert both ways`() {
        repeat(300) {
            val der = signer.sign(Keys.random(16))
            assertContentEquals(der, CommandSignature.rawToDer(CommandSignature.derToRaw(der)))
        }
        // Short, high-bit and largest values.
        for ((r, s) in listOf(BigInteger.ONE to n - BigInteger.ONE, BigInteger.ONE.shiftLeft(255) to BigInteger.valueOf(0x80), BigInteger.valueOf(0x7f) to BigInteger.ONE.shiftLeft(256) - BigInteger.ONE)) {
            val raw = raw(r, s)
            val der = CommandSignature.rawToDer(raw)
            assertContentEquals(raw, CommandSignature.derToRaw(der))
        }
        assertContentEquals("3006020101020101".hexToBytes(), CommandSignature.rawToDer(raw(BigInteger.ONE, BigInteger.ONE)))
        assertContentEquals("300702010102020080".hexToBytes(), CommandSignature.rawToDer(raw(BigInteger.ONE, BigInteger.valueOf(0x80))))
    }

    @Test
    fun `malformed DER is refused`() {
        val good = signer.sign(message)
        for (bad in listOf(
            ByteArray(0),
            byteArrayOf(0x30),
            good.copyOf().also { it[0] = 0x31 },
            good.copyOf().also { it[1] = (it[1] + 1).toByte() },
            good + 0,
            good.copyOf(good.size - 1),
            "3081060201010201 01".replace(" ", "").hexToBytes(),
            "300602010102 01ff".replace(" ", "").hexToBytes(),
            "30070201010202 0001".replace(" ", "").hexToBytes(),
            "30050201010200".hexToBytes(),
            "3006020101 0301 01".replace(" ", "").hexToBytes(),
            byteArrayOf(0x30, 38, 0x02, 33, 0x01) + ByteArray(32) + byteArrayOf(0x02, 1, 1),
            byteArrayOf(0x30, 39, 0x02, 34, 0x00, 0x00) + ByteArray(32) { -1 } + byteArrayOf(0x02, 1, 1),
        )) {
            assertFailsWith<IllegalArgumentException>(bad.toHex()) { CommandSignature.derToRaw(bad) }
        }
        assertFailsWith<IllegalArgumentException> { CommandSignature.rawToDer(ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { CommandSignature.rawToDer(ByteArray(65)) }
    }

    @Test
    fun `signatures of the wrong length or out of range are refused`() {
        val raw = CommandSignature.derToRaw(signer.sign(message))
        val key = signer.publicKey
        for (bad in listOf(ByteArray(0), raw.copyOf(63), raw + 0, signer.sign(message))) {
            assertFalse(CommandSignature.verify(key, message, bad), "length ${bad.size}")
        }
        val r = BigInteger(1, raw.copyOf(32))
        val s = BigInteger(1, raw.copyOfRange(32, 64))
        for ((badR, badS) in listOf(BigInteger.ZERO to s, r to BigInteger.ZERO, r + n to s, r to s + n, n to s, r to n)) {
            if (badR.bitLength() > 256 || badS.bitLength() > 256) continue
            assertFalse(CommandSignature.verify(key, message, raw(badR, badS)))
        }
    }

    @Test
    fun `only P-256 keys in the standard encoding with their point on the curve are taken`() {
        val key = signer.publicKey
        assertNotNull(CommandSignature.publicKey(key))
        assertEquals(CommandSignature.SPKI_BYTES, key.size)
        val raw = CommandSignature.derToRaw(signer.sign(message))

        val p384 = SoftwareSigner(SoftwareSigner.generate("secp384r1"))
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public.encoded
        val offCurve = key.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val xTooLarge = key.copyOf(27) + ByteArray(32) { -1 } + key.copyOfRange(59, 91)
        val compressed = key.copyOf(26).also { it[1] = 0x39; it[24] = 0x22 } + byteArrayOf(2) + key.copyOfRange(27, 59)
        for (bad in listOf(ByteArray(0), key.copyOf(90), key + 0, offCurve, xTooLarge, compressed, p384.publicKey, rsa, Keys.random(91))) {
            assertNull(CommandSignature.publicKey(bad), bad.toHex())
            assertFalse(CommandSignature.verify(bad, message, raw))
        }
    }

    @Test
    fun `the fingerprint is the first 8 bytes of SHA-256 of the key, in four groups`() {
        val fingerprint = CommandSignature.fingerprint(signer.publicKey)
        assertTrue(Regex("[0-9a-f]{4}( [0-9a-f]{4}){3}").matches(fingerprint), fingerprint)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(signer.publicKey).copyOf(8).toHex(), fingerprint.replace(" ", ""))
        assertNotEquals(fingerprint, CommandSignature.fingerprint(SoftwareSigner().publicKey))
        assertEquals("e3b0 c442 98fc 1c14", CommandSignature.fingerprint(ByteArray(0)))
    }
}
