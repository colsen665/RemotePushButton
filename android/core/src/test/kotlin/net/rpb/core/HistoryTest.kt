package net.rpb.core

import net.rpb.core.controller.History
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistoryTest {
    private var now = 1_000_000L
    private val history = History({ now })

    private fun add(text: String, jpeg: ByteArray? = null) = history.add(1_792_336_210_000L, "rig", text, true, 0.9f, jpeg)

    @Test
    fun `entries last five minutes`() {
        val entry = add("1234", byteArrayOf(1, 2, 3))
        now += History.TTL_MS - 1
        history.expire()
        assertEquals(listOf(entry), history.entries.value)
        assertContentEquals(byteArrayOf(1, 2, 3), history.photo(entry))

        now += 1
        history.expire()
        assertTrue(history.entries.value.isEmpty())
        assertNull(history.photo(entry))
    }

    @Test
    fun `older entries expire first`() {
        add("1111")
        now += 60_000
        val newer = add("2222")
        now += History.TTL_MS - 60_000
        history.expire()
        assertEquals(listOf(newer), history.entries.value)
    }

    @Test
    fun `clear removes everything`() {
        val entry = add("1234", byteArrayOf(1))
        history.clear()
        assertTrue(history.entries.value.isEmpty())
        assertNull(history.photo(entry))
    }

    @Test
    fun `newest first, capped`() {
        val small = History({ now }, keep = 2)
        val texts = listOf("1", "2", "3").map { small.add(0, "rig", it, true, 1f, null).text }
        assertEquals(texts.reversed().take(2), small.entries.value.map { it.text })
    }
}
