package net.rpb.core

import net.rpb.core.crypto.Signer
import net.rpb.core.vault.KeyProblem
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A P-256 signing key in software, where the app has one in the Keystore; [authenticated] false acts out the lapsed window. */
class SoftwareSigner(private val pair: KeyPair = generate()) : Signer {
    @Volatile
    var authenticated = true

    override val publicKey: ByteArray get() = pair.public.encoded

    override fun sign(message: ByteArray): ByteArray {
        if (!authenticated) throw KeyProblem.NeedsAuthentication()
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
    }

    companion object {
        fun generate(curve: String = "secp256r1"): KeyPair =
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    }
}
