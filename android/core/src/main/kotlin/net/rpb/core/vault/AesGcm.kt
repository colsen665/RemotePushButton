package net.rpb.core.vault

import java.security.Key
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM as every blob at rest uses it (ADR 0028): `iv(12) | ciphertext | tag(16)`, with a random IV. */
object AesGcm {
    const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** The provider picks the IV, as an Android Keystore key requires. */
    fun seal(key: Key, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext)
    }

    fun open(key: Key, blob: ByteArray): ByteArray {
        if (blob.size < IV_BYTES + TAG_BITS / 8) throw AEADBadTagException("blob too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    fun seal(key: ByteArray, plaintext: ByteArray) = seal(SecretKeySpec(key, "AES"), plaintext)

    fun open(key: ByteArray, blob: ByteArray) = open(SecretKeySpec(key, "AES"), blob)
}
