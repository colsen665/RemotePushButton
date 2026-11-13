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
import net.rpb.core.vault.AesGcm
import net.rpb.core.vault.KeyProblem
import net.rpb.core.vault.KeyWrapper
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * The Android Keystore key that wraps the data key (ADR 0028). It is usable for [AUTH_WINDOW_S] seconds after the
 * screen lock was passed, and lives in StrongBox where the phone has one (ADR 0029).
 */
class KeystoreBox(private val context: Context) : KeyWrapper {
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun isScreenLockSet() = context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /**
     * Creates the wrapping key: StrongBox if the phone has one, the TEE otherwise (ADR 0029). A key under the alias
     * can only be left from a first start that failed before its wrapped data key was saved, perhaps dead since,
     * so it is replaced.
     */
    override fun createKey() {
        if (!isScreenLockSet()) throw KeyProblem.NoScreenLock()
        delete(ALIAS)
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        if (strongBox) {
            try {
                generate(strongBox = true)
                return
            } catch (e: StrongBoxUnavailableException) {
                Log.i("RPB", "StrongBox unavailable, key in the TEE")
            }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            // Adding a fingerprint must not lose the pairings.
            .setInvalidatedByBiometricEnrollment(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            spec.setUserAuthenticationParameters(AUTH_WINDOW_S, KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_S)
        }
        if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) spec.setIsStrongBoxBacked(true)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec.build()) }.generateKey()
        Log.i("RPB", "Keystore key created")
    }

    override fun wrap(dataKey: ByteArray): ByteArray = withKey { AesGcm.seal(it, dataKey) }

    override fun unwrap(wrapped: ByteArray): ByteArray = withKey { AesGcm.open(it, wrapped) }

    private fun <T> withKey(use: (SecretKey) -> T): T {
        val key = keyStore().getKey(ALIAS, null) as? SecretKey ?: throw KeyProblem.Invalidated()
        try {
            return use(key)
        } catch (e: GeneralSecurityException) {
            throw KeyProblem.of(e, { it is KeyPermanentlyInvalidatedException }, { it is UserNotAuthenticatedException }) ?: e
        }
    }

    override fun legacyDecrypt(blob: ByteArray): ByteArray? {
        val key = keyStore().getKey(LEGACY_ALIAS, null) as? SecretKey ?: return null
        return AesGcm.open(key, blob)
    }

    override fun deleteLegacyKey() = delete(LEGACY_ALIAS)

    /** Makes everything wrapped so far unreadable. */
    fun deleteKeys() {
        delete(ALIAS)
        delete(LEGACY_ALIAS)
    }

    private fun delete(alias: String) {
        keyStore().run { if (containsAlias(alias)) deleteEntry(alias) }
    }

    /**
     * Where the key lives, for the PIN lock dialog: "StrongBox", "hardware", "software", or "unknown" when the
     * Keystore doesn't say; null if there is no key.
     */
    fun storage(): String? = keyInfo()?.let(::storageOf)

    override fun describe(): String {
        val info = keyInfo() ?: return "no key"
        return "${storage()}, screen lock enforced by secure hardware: ${info.isUserAuthenticationRequirementEnforcedBySecureHardware}"
    }

    private fun keyInfo(): KeyInfo? {
        val key = runCatching { keyStore().getKey(ALIAS, null) as? SecretKey }.getOrNull() ?: return null
        return runCatching {
            SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
        }.getOrNull()
    }

    companion object {
        /** The key of builds before ADR 0028, with no screen-lock binding; read once to migrate, then deleted. */
        private const val LEGACY_ALIAS = "rpb-box"
        private const val ALIAS = "rpb-wrap"
        const val AUTH_WINDOW_S = 30
    }
}

/** Where a Keystore key lives: "StrongBox", "hardware", "software", or "unknown" when the Keystore doesn't say. */
internal fun storageOf(info: KeyInfo): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT, KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> "hardware"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "unknown"
        }
    } else {
        @Suppress("DEPRECATION")
        if (info.isInsideSecureHardware) "hardware" else "software"
    }
