package net.rpb.app.common

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.Signer
import net.rpb.core.crypto.toHex
import net.rpb.core.vault.KeyProblem
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.ProviderException
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The controller's signing key for each pairing (ADR 0033): EC P-256 in the Keystore, in StrongBox where the phone
 * has one (ADR 0029), usable for [AUTH_WINDOW_S] seconds after the screen lock was passed, and only while the phone
 * is unlocked (API 28+). Keystore work, so off the main thread.
 */
object SigningKeys {
    private const val PREFIX = "rpb-sig-"
    private val PAIRING_ALIAS = Regex("$PREFIX[0-9a-f]+-[0-9a-f]+")
    const val AUTH_WINDOW_S = 300

    /** Aliases whose pairing is being added and bound in this process, which [deleteUnpaired] keeps. */
    private val binding = mutableSetOf<String>()

    fun alias(code: PairingCode) = "$PREFIX${code.deviceId.toHex()}-${code.controllerId.toHex()}"

    /** The alias of a stored pairing code, or null if this app can't read it (a version 1 code never had a key). */
    fun alias(storedCode: String): String? = runCatching { alias(PairingCode.decode(storedCode)) }.getOrNull()

    /**
     * Creates the key under [alias] unless it exists, which only a bind cut short by the app stopping leaves: the
     * device may have bound it already, and binding it again is safe.
     */
    fun ensure(context: Context, alias: String) {
        if (keyStore().containsAlias(alias)) return
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                generate(alias, strongBox = true)
                checkEncoding(context, alias)
                return
            } catch (e: ProviderException) {
                // Thrown as is, or as the cause of a ProviderException, depending on the Android version.
                if (generateSequence<Throwable>(e) { it.cause }.none { it is StrongBoxUnavailableException }) throw e
                Log.i("RPB", "StrongBox unavailable, signing key in the TEE")
            }
        }
        generate(alias, strongBox = false)
        checkEncoding(context, alias)
    }

    private fun generate(alias: String, strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Adding a fingerprint must not lose the pairings.
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(AUTH_WINDOW_S, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_S)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Root on a locked phone can't sign within the window either.
            spec.setUnlockedDeviceRequired(true)
            if (strongBox) spec.setIsStrongBoxBacked(true)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec.build()) }.generateKeyPair()
        Log.i("RPB", "signing key created: ${describe(alias)}")
    }

    /** The device takes only the standard P-256 encoding, so a key it would refuse fails here, not as a bind timeout. */
    private fun checkEncoding(context: Context, alias: String) {
        if (CommandSignature.publicKey(KeystoreSigner(context, alias).publicKey) == null) {
            delete(alias)
            throw IllegalStateException("the Keystore made a signing key in an unexpected encoding")
        }
    }

    fun signer(context: Context, alias: String): Signer = KeystoreSigner(context.applicationContext, alias)

    fun delete(alias: String) {
        keyStore().run { if (containsAlias(alias)) deleteEntry(alias) }
    }

    /** Keeps [alias] from [deleteUnpaired] until [release]: from before its key is made until its bind ends. */
    fun hold(alias: String) = synchronized(binding) { binding += alias }

    fun release(alias: String) = synchronized(binding) { binding -= alias }

    /** Deletes every pairing's key whose alias is neither in [paired] nor being bound; how many it deleted. */
    fun deleteUnpaired(paired: Set<String>): Int = synchronized(binding) {
        val keys = keyStore()
        keys.aliases().toList().filter { PAIRING_ALIAS.matches(it) && it !in paired && it !in binding }.onEach(keys::deleteEntry).size
    }

    /** Every pairing's key, for a wipe (ADR 0015). */
    fun deleteAll() {
        keyStore().run { aliases().toList().filter { it.startsWith(PREFIX) }.forEach(::deleteEntry) }
    }

    private fun describe(alias: String): String {
        val key = keyStore().getKey(alias, null) as? PrivateKey ?: return "no key"
        val info = runCatching {
            KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        }.getOrNull() ?: return "unknown"
        return "${storageOf(info)}, screen lock enforced by secure hardware: ${info.isUserAuthenticationRequirementEnforcedBySecureHardware}"
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private class KeystoreSigner(private val context: Context, private val alias: String) : Signer {
        override val publicKey: ByteArray by lazy {
            keyStore().getCertificate(alias)?.publicKey?.encoded ?: throw KeyProblem.Invalidated()
        }

        override fun sign(message: ByteArray): ByteArray {
            try {
                val key = keyStore().getKey(alias, null) as? PrivateKey ?: throw KeyProblem.Invalidated()
                return Signature.getInstance("SHA256withECDSA").run {
                    initSign(key)
                    update(message)
                    sign()
                }
            } catch (e: GeneralSecurityException) {
                throw KeyProblem.of(e, { it is KeyPermanentlyInvalidatedException }, ::needsUnlock)
                    ?: if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) KeyProblem.NeedsAuthentication(e) else e
            }
        }

        /** The window has passed or the phone is locked. Before API 33 a locked phone's error can't be read, so the lock is checked instead. */
        private fun needsUnlock(e: Throwable) = e is UserNotAuthenticatedException ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && e is android.security.KeyStoreException && e.requiresUserAuthentication()
    }
}
