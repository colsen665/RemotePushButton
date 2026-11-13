package net.rpb.app.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.Signer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.SignatureException
import java.security.UnrecoverableKeyException
import java.security.spec.ECGenParameterSpec

/**
 * The device's signing key (ADR 0033): EC P-256 in the Keystore, in StrongBox where the phone has one (ADR 0029),
 * which signs each job's key exchange. Its public key is in every pairing code. It needs no user authentication and
 * no unlocked phone, as the device runs unattended. Keystore work, so off the main thread.
 */
object DeviceKey {
    // Outside SigningKeys' `rpb-sig-` prefix, so a controller's cleanup never takes it.
    private const val ALIAS = "rpb-device-sig"
    private val PROBE = "rpb device key check".encodeToByteArray()
    private const val KEPT = "device signing key missing, unreadable or wrong, and kept, as controllers are bound to it. " +
        "Restart the app; if it stays so, revoke every controller and pair them again"

    private var signer: Signer? = null

    /**
     * The key's signer, checked once per process. With no controller [bound], a key that is missing, unreadable or
     * doesn't verify is replaced: nothing trusts it yet. With one bound, only a key the Keystore calls permanently
     * invalidated is replaced, and every controller must then pair again, as it checks answers against the old key.
     * Any other failure throws and keeps the key: the Keystore reports one it can't reach for the moment (its service
     * busy or restarting) the same way as one that is gone, and a new key would cut off every controller for good.
     */
    @Synchronized
    fun signer(context: Context, bound: Boolean, log: (String) -> Unit): Signer {
        signer?.let { return it }
        val existing = try {
            load()
        } catch (e: Exception) {
            val gone = e is UnrecoverableKeyException || e is IllegalStateException
            if (gone && bound) log(KEPT)
            // Any other failure may pass too, so it is thrown rather than lose the key.
            if (!gone || bound) throw e
            null
        }
        when (existing?.let { probe(it, log) }) {
            Check.WORKS -> return existing.also { signer = it }
            Check.WRONG -> if (bound) {
                log(KEPT)
                throw IllegalStateException("device signing key doesn't verify")
            }
            Check.INVALIDATED, null -> Unit
        }
        if (keyStore().containsAlias(ALIAS)) {
            log("device signing key unusable, replaced")
            delete()
        }
        create(context)
        val created = load()
        log(if (bound) "new device signing key: every controller paired before must pair again" else "new device signing key")
        check(probe(created, log) == Check.WORKS) { "new device signing key doesn't verify" }
        return created.also { signer = it }
    }

    private enum class Check { WORKS, INVALIDATED, WRONG }

    /** Signs once and verifies, which logs where the key lives and how long a signature takes. */
    private fun probe(signer: KeystoreSigner, log: (String) -> Unit): Check {
        val start = SystemClock.elapsedRealtime()
        val signature = try {
            signer.sign(PROBE)
        } catch (e: SignatureException) {
            if (generateSequence<Throwable>(e) { it.cause }.any { it is KeyPermanentlyInvalidatedException }) return Check.INVALIDATED
            // Perhaps passing: keep the key, which every bound controller trusts. Each job logs its own failure.
            log("device signing key check failed: ${e.cause?.javaClass?.simpleName}")
            return Check.WORKS
        }
        val ms = SystemClock.elapsedRealtime() - start
        val raw = runCatching { CommandSignature.derToRaw(signature) }.getOrNull() ?: return Check.WRONG
        if (!CommandSignature.verify(signer.publicKey, PROBE, raw)) return Check.WRONG
        log("device signing key: ${storage(signer.key)}, signs in $ms ms")
        return Check.WORKS
    }

    private fun load(): KeystoreSigner {
        val keys = keyStore()
        val key = keys.getKey(ALIAS, null) as? PrivateKey ?: throw IllegalStateException("no device signing key")
        val publicKey = keys.getCertificate(ALIAS)?.publicKey?.encoded ?: throw IllegalStateException("no device public key")
        // The controller takes only the standard P-256 encoding, so a key it would refuse is replaced here.
        check(CommandSignature.publicKey(publicKey) != null) { "device public key in an unexpected encoding" }
        return KeystoreSigner(key, publicKey)
    }

    private fun create(context: Context) {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                generate(strongBox = true)
                return
            } catch (e: ProviderException) {
                // Thrown as is, or as the cause of a ProviderException, depending on the Android version.
                if (generateSequence<Throwable>(e) { it.cause }.none { it is StrongBoxUnavailableException }) throw e
                Log.i("RPB", "StrongBox unavailable, device signing key in the TEE")
            }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(true)
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
    }

    /** For a wipe (ADR 0015): the wipe destroys every pairing too, so no controller trusts it any more. */
    @Synchronized
    fun delete() {
        keyStore().run { if (containsAlias(ALIAS)) deleteEntry(ALIAS) }
        signer = null
    }

    private fun storage(key: PrivateKey): String = runCatching {
        storageOf(KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java))
    }.getOrDefault("unknown")

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class KeystoreSigner(val key: PrivateKey, override val publicKey: ByteArray) : Signer {
        override fun sign(message: ByteArray): ByteArray = try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(message)
                sign()
            }
        } catch (e: Exception) {
            if (e !is GeneralSecurityException && e !is ProviderException) throw e
            // A Keystore message can name the alias; logs show only the class.
            throw SignatureException("device signing key failed: ${e.javaClass.simpleName}", e)
        }
    }
}
