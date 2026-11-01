package net.rpb.core.controller

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** [ts] is the wall-clock time, for display; [at] is on the [History] clock and decides expiry. */
data class HistoryEntry(
    val id: Long,
    val ts: Long,
    val at: Long,
    val device: String,
    val text: String,
    val valid: Boolean,
    val confidence: Float,
    val hasPhoto: Boolean,
)

/**
 * Recent results, in memory only: codes and photos never reach the disk (ADR 0026). Each entry lasts [ttlMs];
 * the app calls [expire] while visible and [clear] when it locks.
 *
 * [clock] should count from boot, so setting the wall clock back can't keep entries alive.
 */
class History(
    private val clock: () -> Long,
    private val ttlMs: Long = TTL_MS,
    private val keep: Int = 50,
) {
    private class Item(val entry: HistoryEntry, val jpeg: ByteArray?)

    private var items = listOf<Item>()
    private var nextId = 0L
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<HistoryEntry>> = _entries

    @Synchronized
    fun add(ts: Long, device: String, text: String, valid: Boolean, confidence: Float, jpeg: ByteArray?): HistoryEntry {
        val entry = HistoryEntry(nextId++, ts, clock(), device, text, valid, confidence, jpeg != null)
        publish((listOf(Item(entry, jpeg)) + items).take(keep))
        return entry
    }

    @Synchronized
    fun photo(entry: HistoryEntry): ByteArray? = items.firstOrNull { it.entry.id == entry.id }?.jpeg

    @Synchronized
    fun expire() {
        val now = clock()
        val kept = items.filterNot { isExpired(it.entry.at, now, ttlMs) }
        if (kept.size != items.size) publish(kept)
    }

    @Synchronized
    fun clear() = publish(emptyList())

    private fun publish(updated: List<Item>) {
        items = updated
        _entries.update { updated.map { it.entry } }
    }

    companion object {
        const val TTL_MS = 5 * 60_000L

        fun isExpired(at: Long, now: Long, ttlMs: Long = TTL_MS) = now - at >= ttlMs
    }
}
