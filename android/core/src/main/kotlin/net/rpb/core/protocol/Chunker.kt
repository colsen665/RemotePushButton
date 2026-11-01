package net.rpb.core.protocol

/** Photos are split so every MQTT message stays well under any broker's size limit. */
object Chunker {
    const val DEFAULT_CHUNK_BYTES = 64 * 1024

    fun split(bytes: ByteArray, chunkBytes: Int = DEFAULT_CHUNK_BYTES): List<ByteArray> {
        require(chunkBytes > 0)
        if (bytes.isEmpty()) return listOf(ByteArray(0))
        return (bytes.indices step chunkBytes).map { start ->
            bytes.copyOfRange(start, minOf(start + chunkBytes, bytes.size))
        }
    }
}

/** Reassembles chunks per job; tolerates duplicates and out-of-order delivery (QoS 1). */
class ChunkAssembler {
    private class Partial(val n: Int) {
        val parts = arrayOfNulls<ByteArray>(n)
        var received = 0
    }

    private val partials = HashMap<String, Partial>()

    /** Returns the whole payload once the last missing chunk arrives, otherwise null. */
    @Synchronized
    fun add(job: String, i: Int, n: Int, bytes: ByteArray): ByteArray? {
        if (n <= 0 || i !in 0 until n) return null
        val partial = partials.getOrPut(job) { Partial(n) }
        if (partial.n != n) return null
        if (partial.parts[i] == null) {
            partial.parts[i] = bytes
            partial.received++
        }
        if (partial.received < n) return null
        partials.remove(job)
        val out = ByteArray(partial.parts.sumOf { it!!.size })
        var offset = 0
        for (part in partial.parts) {
            part!!.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    @Synchronized
    fun progress(job: String): Pair<Int, Int>? = partials[job]?.let { it.received to it.n }

    @Synchronized
    fun discard(job: String) {
        partials.remove(job)
    }
}
