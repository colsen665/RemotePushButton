package net.rpb.core

import net.rpb.core.lock.PinCheck
import net.rpb.core.lock.PinLock
import net.rpb.core.lock.PinState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PinLockTest {
    private var stored: PinState? = null
    private val saves = ArrayList<PinState?>()
    private var now = 1_000_000L
    private val lock = PinLock({ stored }, { stored = it; saves += it }, { now }, iterations = 1_000)

    @Test
    fun `right PIN unlocks and resets the counter`() {
        lock.set("1234", wipeOnFailures = true)
        assertIs<PinCheck.Wrong>(lock.check("0000"))
        assertEquals(PinCheck.Ok, lock.check("1234"))
        assertEquals(0, stored!!.failures)
    }

    @Test
    fun `no PIN set means unlocked`() {
        assertFalse(lock.isSet)
        assertEquals(PinCheck.Ok, lock.check("anything"))
    }

    @Test
    fun `the PIN is stored salted and hashed`() {
        lock.set("1234", wipeOnFailures = false)
        val first = stored!!
        assertFalse(first.hash.contains("1234"))
        lock.set("1234", wipeOnFailures = false)
        assertNotEquals(first.salt, stored!!.salt)
        assertNotEquals(first.hash, stored!!.hash)
    }

    @Test
    fun `three wrong PINs ask for a wipe when it is on`() {
        lock.set("1234", wipeOnFailures = true)
        assertEquals(PinCheck.Wrong(attemptsLeft = 2, lockedUntil = 0), lock.check("1111"))
        assertEquals(PinCheck.Wrong(attemptsLeft = 1, lockedUntil = 0), lock.check("2222"))
        assertEquals(PinCheck.Wipe, lock.check("3333"))
        // Even the right PIN can't undo it if the app didn't wipe in time.
        assertEquals(PinCheck.Wipe, lock.check("4444"))
    }

    @Test
    fun `without wipe, attempts after the third wait longer each time`() {
        lock.set("1234", wipeOnFailures = false)
        assertEquals(PinCheck.Wrong(null, 0), lock.check("1111"))
        assertEquals(PinCheck.Wrong(null, 0), lock.check("2222"))
        assertEquals(PinCheck.Wrong(null, now + PinLock.FIRST_LOCKOUT_MS), lock.check("3333"))

        assertEquals(PinCheck.LockedOut(now + PinLock.FIRST_LOCKOUT_MS), lock.check("1234"))
        now += PinLock.FIRST_LOCKOUT_MS
        assertEquals(PinCheck.Wrong(null, now + 2 * PinLock.FIRST_LOCKOUT_MS), lock.check("4444"))
        now += 2 * PinLock.FIRST_LOCKOUT_MS
        assertEquals(PinCheck.Ok, lock.check("1234"))
        assertEquals(0L, stored!!.lockoutMs)
    }

    @Test
    fun `lockout is capped at an hour`() {
        lock.set("1234", wipeOnFailures = false)
        var last = 0L
        repeat(20) {
            val result = lock.check("0000")
            if (result is PinCheck.Wrong && result.lockedUntil > 0) {
                last = result.lockedUntil - now
                assertTrue(last <= PinLock.MAX_LOCKOUT_MS)
                now = result.lockedUntil
            }
        }
        assertEquals(PinLock.MAX_LOCKOUT_MS, last)
    }

    @Test
    fun `a clock that went backwards restarts the wait instead of ending it`() {
        lock.set("1234", wipeOnFailures = false)
        repeat(3) { lock.check("0000") }
        assertEquals(PinCheck.LockedOut(now + PinLock.FIRST_LOCKOUT_MS), lock.check("1234"))
        // A reboot: the clock counts from boot again, so the full wait runs from there.
        now = 5_000
        assertEquals(PinCheck.LockedOut(PinLock.FIRST_LOCKOUT_MS), lock.check("1234"))
        now = PinLock.FIRST_LOCKOUT_MS
        assertEquals(PinCheck.Ok, lock.check("1234"))
    }

    @Test
    fun `a failure is saved before the PIN is checked`() {
        lock.set("1234", wipeOnFailures = true)
        saves.clear()
        lock.check("1234")
        // First save counts the attempt; only then does the success reset it.
        assertEquals(listOf(1, 0), saves.map { it!!.failures })
    }

    @Test
    fun `only 4 to 8 digits are accepted`() {
        assertFailsWith<IllegalArgumentException> { lock.set("123", wipeOnFailures = false) }
        assertFailsWith<IllegalArgumentException> { lock.set("123456789", wipeOnFailures = false) }
        assertFailsWith<IllegalArgumentException> { lock.set("12a4", wipeOnFailures = false) }
        lock.set("12345678", wipeOnFailures = false)
        assertTrue(lock.isSet)
    }

    @Test
    fun `asks again after a minute away, or after a restart`() {
        assertTrue(PinLock.needsUnlock(backgroundedAt = null, now = 0))
        assertFalse(PinLock.needsUnlock(backgroundedAt = 0, now = PinLock.GRACE_MS))
        assertTrue(PinLock.needsUnlock(backgroundedAt = 0, now = PinLock.GRACE_MS + 1))
    }

    @Test
    fun `clearing removes the PIN`() {
        lock.set("1234", wipeOnFailures = true)
        lock.clear()
        assertFalse(lock.isSet)
        assertEquals(PinCheck.Ok, lock.check("0000"))
    }
}
