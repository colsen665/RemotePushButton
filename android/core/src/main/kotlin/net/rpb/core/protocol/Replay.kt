package net.rpb.core.protocol

import kotlin.math.abs

/** Persists the highest accepted sequence number per controller. */
interface SeqStore {
    fun lastSeq(controllerId: String): Long
    fun setLastSeq(controllerId: String, seq: Long)
}

class InMemorySeqStore : SeqStore {
    private val map = HashMap<String, Long>()

    @Synchronized
    override fun lastSeq(controllerId: String) = map[controllerId] ?: 0L

    @Synchronized
    override fun setLastSeq(controllerId: String, seq: Long) {
        map[controllerId] = seq
    }
}

/**
 * Rejects replayed or stale commands: [Command.seq] must be strictly greater than the last accepted one
 * for that controller, and [Command.ts] must be within [maxSkewMs] of the device clock.
 */
class ReplayGuard(
    private val store: SeqStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxSkewMs: Long = 120_000,
) {
    @Synchronized
    fun accept(controllerId: String, command: Command): Boolean {
        if (abs(clock() - command.ts) > maxSkewMs) return false
        if (command.seq <= store.lastSeq(controllerId)) return false
        store.setLastSeq(controllerId, command.seq)
        return true
    }
}

/**
 * Controller-side sequence numbers. Using max(last + 1, now) keeps them increasing even if local
 * state is lost, as long as the clock is roughly right.
 */
class SeqCounter(private val store: SeqStore, private val key: String, private val clock: () -> Long = System::currentTimeMillis) {
    @Synchronized
    fun next(): Long {
        val seq = maxOf(store.lastSeq(key) + 1, clock())
        store.setLastSeq(key, seq)
        return seq
    }
}
