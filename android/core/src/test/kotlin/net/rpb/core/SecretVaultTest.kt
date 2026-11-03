package net.rpb.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.toHex
import net.rpb.core.vault.AesGcm
import net.rpb.core.vault.KeyProblem
import net.rpb.core.vault.KeyWrapper
import net.rpb.core.vault.SecretVault
import net.rpb.core.vault.Vault
import net.rpb.core.vault.VaultFiles
import java.security.ProviderException
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@Serializable
private data class Box(val names: List<String> = emptyList(), val id: String = Keys.random(4).toHex())

/** The Keystore as the vault sees it: a key bound to the screen lock, and the key of builds before ADR 0028. */
private class FakeKeystore : KeyWrapper {
    var screenLock = true
    var authenticated = true
    var key: SecretKeySpec? = null
    var keyAlive = true
    var legacy: SecretKeySpec? = null
    var failure: Exception? = null
    var created = 0
    var described = 0
    val unwrapped = ArrayList<ByteArray>()

    override fun isScreenLockSet() = screenLock

    override fun createKey() {
        if (!screenLock) throw KeyProblem.NoScreenLock()
        key = SecretKeySpec(Keys.random(32), "AES")
        keyAlive = true
        created++
    }

    override fun wrap(dataKey: ByteArray) = use { AesGcm.seal(it, dataKey) }

    override fun unwrap(wrapped: ByteArray) = use { AesGcm.open(it, wrapped) }.also { unwrapped += it }

    private fun <T> use(f: (SecretKeySpec) -> T): T {
        val k = key?.takeIf { keyAlive } ?: throw KeyProblem.Invalidated()
        failure?.let { throw it }
        if (!authenticated) throw KeyProblem.NeedsAuthentication()
        return f(k)
    }

    override fun legacyDecrypt(blob: ByteArray) = legacy?.let { AesGcm.open(it, blob) }

    override fun deleteLegacyKey() {
        legacy = null
    }

    override fun describe() = "test key".also { described++ }
}

private class FakeFiles : VaultFiles {
    var wrapped: ByteArray? = null
    var blob: ByteArray? = null
    var commitFails = false
    var writes = 0

    override fun wrappedKey() = wrapped

    override fun blob() = blob

    override fun commit(wrappedKey: ByteArray, blob: ByteArray): Boolean {
        if (commitFails) return false
        wrapped = wrappedKey
        this.blob = blob
        writes++
        return true
    }

    override fun saveBlob(blob: ByteArray) {
        this.blob = blob
        writes++
    }
}

class SecretVaultTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keystore = FakeKeystore()
    private val files = FakeFiles()
    private val logs = ArrayList<String>()

    /** A vault as a new process opens it. */
    private fun vault() = SecretVault(
        keystore,
        files,
        encode = { json.encodeToString(Box.serializer(), it).encodeToByteArray() },
        decode = { plain -> plain?.let { json.decodeFromString(Box.serializer(), it.decodeToString()) } ?: Box() },
        log = { logs += it },
    )

    /** A first start that saved [names]; returns the data key, as the Keystore unwraps it. */
    private fun setUp(vararg names: String): ByteArray {
        val first = vault()
        assertEquals(Vault.OPEN, first.open())
        assertNotNull(first.update { it.copy(names = names.toList()) })
        return keystore.unwrap(files.wrapped!!)
    }

    @Test
    fun `a first start makes a key, and the next process reads the secrets back`() {
        setUp("kitchen")
        assertEquals(1, keystore.created)
        val next = vault()
        assertEquals(Vault.OPEN, next.open())
        assertEquals(listOf("kitchen"), next.value!!.names)
        assertEquals(Vault.OPEN, next.state.value)
    }

    @Test
    fun `close zeroes the data key and drops the secrets, and open reads them again`() {
        setUp("kitchen")
        val vault = vault()
        vault.open()
        val dataKey = keystore.unwrapped.last()
        vault.close()
        assertTrue(dataKey.all { it == 0.toByte() })
        assertNull(vault.value)
        assertEquals(Vault.CLOSED, vault.state.value)
        assertEquals(Vault.OPEN, vault.open())
        assertEquals(listOf("kitchen"), vault.value!!.names)
    }

    @Test
    fun `nothing is saved while the vault isn't open`() {
        setUp("kitchen")
        val vault = vault()
        val writes = files.writes
        assertNull(vault.update { it.copy(names = listOf("garage")) })
        vault.open()
        vault.close()
        assertNull(vault.update { it.copy(names = listOf("garage")) })
        assertEquals(writes, files.writes)
        assertEquals(Vault.OPEN, vault.open())
        assertEquals(listOf("kitchen"), vault.value!!.names)
    }

    @Test
    fun `the screen lock must be passed first`() {
        setUp("kitchen")
        keystore.authenticated = false
        val vault = vault()
        assertEquals(Vault.CLOSED, vault.open())
        assertNull(vault.value)
        keystore.authenticated = true
        assertEquals(Vault.OPEN, vault.open())
    }

    @Test
    fun `a phone without a screen lock gets no key`() {
        keystore.screenLock = false
        assertEquals(Vault.NO_SCREEN_LOCK, vault().open())
        assertEquals(0, keystore.created)
        assertNull(files.wrapped)
    }

    @Test
    fun `removing the screen lock or losing the key loses the secrets`() {
        setUp("kitchen")
        keystore.screenLock = false
        assertEquals(Vault.LOST, vault().open())
        keystore.screenLock = true
        keystore.keyAlive = false
        assertEquals(Vault.LOST, vault().open())
    }

    @Test
    fun `damaged data is lost rather than a crash, and the log never quotes it`() {
        val dataKey = setUp("kitchen")
        val good = files.blob!!
        files.blob = good.copyOf(5)
        assertEquals(Vault.LOST, vault().open())
        files.blob = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertEquals(Vault.LOST, vault().open())
        files.blob = AesGcm.seal(dataKey, "{\"names\": [\"secret-name\"".toByteArray())
        assertEquals(Vault.LOST, vault().open())
        assertTrue(logs.none { "secret-name" in it }, logs.toString())

        files.blob = good
        val wrapped = files.wrapped!!
        files.wrapped = wrapped.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals(Vault.LOST, vault().open())
        files.wrapped = wrapped
        assertEquals(Vault.OPEN, vault().open())
    }

    @Test
    fun `an unexpected Keystore error is lost for now, and a retry can open`() {
        setUp("kitchen")
        keystore.failure = ProviderException("busy")
        val vault = vault()
        assertEquals(Vault.LOST, vault.open())
        keystore.failure = null
        assertEquals(Vault.OPEN, vault.open())
    }

    @Test
    fun `a first start that failed before its key was saved makes a new key`() {
        keystore.authenticated = false
        val vault = vault()
        assertEquals(Vault.CLOSED, vault.open())
        assertEquals(1, keystore.created)
        assertNull(files.wrapped)
        // The screen lock went away and came back: the key left under the alias is dead.
        keystore.keyAlive = false
        keystore.authenticated = true
        assertEquals(Vault.OPEN, vault.open())
        assertEquals(2, keystore.created)
    }

    @Test
    fun `a blob from before ADR 0028 moves to the new key, and the old key goes only after the commit`() {
        val legacy = SecretKeySpec(Keys.random(32), "AES")
        keystore.legacy = legacy
        val old = AesGcm.seal(legacy, json.encodeToString(Box.serializer(), Box(listOf("kitchen"), "abcd")).encodeToByteArray())
        files.blob = old

        keystore.authenticated = false
        val vault = vault()
        assertEquals(Vault.CLOSED, vault.open())
        assertSame(legacy, keystore.legacy)
        assertSame(old, files.blob)

        keystore.authenticated = true
        files.commitFails = true
        assertEquals(Vault.LOST, vault.open())
        assertSame(legacy, keystore.legacy)
        assertSame(old, files.blob)

        files.commitFails = false
        assertEquals(Vault.OPEN, vault.open())
        assertEquals(Box(listOf("kitchen"), "abcd"), vault.value)
        assertNull(keystore.legacy)
        assertEquals(Box(listOf("kitchen"), "abcd"), vault().apply { open() }.value)
    }

    @Test
    fun `an old blob whose key is gone is lost`() {
        files.blob = AesGcm.seal(Keys.random(32), "{}".toByteArray())
        assertEquals(Vault.LOST, vault().open())
        assertEquals(0, keystore.created)
    }

    @Test
    fun `opening saves back only what a default filled in`() {
        val dataKey = setUp("kitchen")
        var writes = files.writes
        vault().open()
        assertEquals(writes, files.writes, "an up-to-date blob isn't written again")

        // Saved before the random id existed.
        files.blob = AesGcm.seal(dataKey, "{\"names\":[\"kitchen\"]}".toByteArray())
        val first = vault().apply { open() }.value!!
        assertEquals(writes + 1, files.writes)
        writes = files.writes
        assertEquals(first.id, vault().apply { open() }.value!!.id)
        assertEquals(writes, files.writes)
    }

    @Test
    fun `the key is described once per process`() {
        setUp()
        keystore.described = 0
        val vault = vault()
        vault.open()
        vault.close()
        vault.open()
        assertEquals(1, keystore.described)
        assertEquals("test key", vault.keyDescription)
    }

    private class Invalid(cause: Throwable? = null) : Exception(cause)

    private class NoAuth(cause: Throwable? = null) : Exception(cause)

    @Test
    fun `a key problem is found anywhere in the cause chain, outermost first`() {
        fun of(e: Throwable) = KeyProblem.of(e, { it is Invalid }, { it is NoAuth })
        val deep = RuntimeException(Exception(Invalid()))
        val problem = of(deep)
        assertIs<KeyProblem.Invalidated>(problem)
        assertSame(deep, problem.cause)
        assertIs<KeyProblem.NeedsAuthentication>(of(Exception(NoAuth())))
        assertIs<KeyProblem.NeedsAuthentication>(of(NoAuth(Invalid())))
        assertNull(of(Exception("other", IllegalStateException())))
        assertFalse(of(Invalid()) is KeyProblem.NeedsAuthentication)
    }
}
