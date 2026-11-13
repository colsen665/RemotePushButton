package net.rpb.core.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * A signing key, EC P-256 with SHA-256, in the phone's Keystore on Android, in software in tests (ADR 0033): the
 * controller's for one pairing, which signs its commands, or the device's, which signs each job's key exchange.
 */
interface Signer {
    /** The public key as an X.509 SubjectPublicKeyInfo, DER. */
    val publicKey: ByteArray

    /**
     * ECDSA P-256 with SHA-256 over [message], DER encoded as JCA's `SHA256withECDSA` gives it.
     *
     * @throws net.rpb.core.vault.KeyProblem.NeedsAuthentication when the screen lock wasn't passed within the key's
     *   window: ask for it, then sign again. The device's key needs no authentication.
     */
    fun sign(message: ByteArray): ByteArray
}

/** ECDSA P-256 / SHA-256 signatures on commands and on job key exchanges (ADR 0033), on the wire as raw `r ‖ s`. */
object CommandSignature {
    const val RAW_BYTES = 64
    private const val SCALAR_BYTES = 32
    private const val ALGORITHM = "SHA256withECDSA"

    /** SubjectPublicKeyInfo of a P-256 key, up to its uncompressed point: id-ecPublicKey, prime256v1, BIT STRING, 0x04. */
    private val SPKI_PREFIX = "3059301306072a8648ce3d020106082a8648ce3d03010703420004".hexToBytes()
    const val SPKI_BYTES = 27 + 2 * SCALAR_BYTES

    private val p = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val a = p - BigInteger.valueOf(3)
    private val b = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val n = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)

    /**
     * [spki] as a P-256 public key, or null unless it is one in the standard encoding (named curve, uncompressed
     * point) with its point on the curve. One key has one encoding, so it has one [fingerprint].
     */
    fun publicKey(spki: ByteArray): ECPublicKey? {
        if (spki.size != SPKI_BYTES || !spki.copyOfRange(0, SPKI_PREFIX.size).contentEquals(SPKI_PREFIX)) return null
        val x = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size, SPKI_PREFIX.size + SCALAR_BYTES))
        val y = BigInteger(1, spki.copyOfRange(SPKI_PREFIX.size + SCALAR_BYTES, SPKI_BYTES))
        if (x >= p || y >= p) return null
        // P-256 has cofactor 1: any point on the curve but infinity, which has no affine encoding, is a valid key.
        if (y.multiply(y).mod(p) != x.multiply(x).add(a).multiply(x).add(b).mod(p)) return null
        return try {
            (KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) as ECPublicKey).takeIf { it.params.order == n }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Whether [raw] is a signature over [message] by [spki]. False, never an exception, for anything malformed: a key
     * that isn't P-256, a signature of the wrong length, r or s out of range.
     */
    fun verify(spki: ByteArray, message: ByteArray, raw: ByteArray): Boolean {
        val key = publicKey(spki) ?: return false
        if (raw.size != RAW_BYTES) return false
        val r = BigInteger(1, raw.copyOfRange(0, SCALAR_BYTES))
        val s = BigInteger(1, raw.copyOfRange(SCALAR_BYTES, RAW_BYTES))
        if (r.signum() == 0 || s.signum() == 0 || r >= n || s >= n) return false
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(message)
                verify(rawToDer(raw))
            }
        } catch (e: Exception) {
            false
        }
    }

    /** A DER `SEQUENCE { INTEGER r, INTEGER s }` as raw `r ‖ s`, 32 bytes each. Throws [IllegalArgumentException] if malformed. */
    fun derToRaw(der: ByteArray): ByteArray {
        require(der.size >= 2 && der[0] == 0x30.toByte()) { "not a DER sequence" }
        val length = der[1].toInt() and 0xff
        require(length < 0x80 && length == der.size - 2) { "bad DER length" }
        var at = 2
        fun integer(): ByteArray {
            require(at + 2 <= der.size && der[at] == 0x02.toByte()) { "not a DER integer" }
            val size = der[at + 1].toInt() and 0xff
            at += 2
            require(size in 1..SCALAR_BYTES + 1 && at + size <= der.size) { "bad DER integer length" }
            val bytes = der.copyOfRange(at, at + size)
            at += size
            require(bytes[0] >= 0) { "negative DER integer" }
            require(size == 1 || bytes[0] != 0.toByte() || bytes[1] < 0) { "non-minimal DER integer" }
            val value = if (bytes[0] == 0.toByte() && size > 1) bytes.copyOfRange(1, size) else bytes
            require(value.size <= SCALAR_BYTES) { "DER integer too large" }
            return ByteArray(SCALAR_BYTES - value.size) + value
        }
        val r = integer()
        val s = integer()
        require(at == der.size) { "trailing bytes after DER signature" }
        return r + s
    }

    /** Raw `r ‖ s` as DER `SEQUENCE { INTEGER r, INTEGER s }`. Throws [IllegalArgumentException] if not 64 bytes. */
    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == RAW_BYTES) { "raw signature must be $RAW_BYTES bytes" }
        fun integer(half: ByteArray): ByteArray {
            val start = half.indexOfFirst { it != 0.toByte() }.let { if (it < 0) half.size - 1 else it }
            val value = half.copyOfRange(start, half.size)
            val body = if (value[0] < 0) byteArrayOf(0) + value else value
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val body = integer(raw.copyOfRange(0, SCALAR_BYTES)) + integer(raw.copyOfRange(SCALAR_BYTES, RAW_BYTES))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    /** What both phones show to compare a bound key: the first 8 bytes of SHA-256 of [spki], as `xxxx xxxx xxxx xxxx`. */
    fun fingerprint(spki: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(spki).copyOf(8).toHex().chunked(4).joinToString(" ")
}
