package net.rpb.core.ocr

import kotlinx.serialization.Serializable
import kotlin.math.hypot

/**
 * What calibration saves on the device: the reader config and the extra clockwise turn of every photo
 * (ADR 0014). Controllers send it with `set_calib` (ADR 0016).
 */
@Serializable
data class Calibration(val config: SevenSegConfig = SevenSegConfig(), val rotation: Int = 0) {
    /** Why the device must not save this, or null if it may. Out-of-range values could break every later read. */
    fun problem(): String? {
        val c = config
        val values = listOf(c.slant, c.onThreshold, c.minConfidence, c.digitGap, c.segmentThickness, c.alignSearch)
        return when {
            rotation !in ROTATIONS -> "rotation must be 0, 90, 180 or 270"
            c.corners.size != 4 -> "need 4 corners"
            c.corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x !in 0f..1f || it.y !in 0f..1f } -> "corners must lie in the photo"
            !values.all(Float::isFinite) -> "values must be finite"
            c.digits !in 1..MAX_DIGITS -> "digits must be 1 to $MAX_DIGITS"
            c.slant !in -0.5f..0.5f -> "slant out of range"
            c.onThreshold !in 0.05f..0.95f -> "on threshold out of range"
            c.minConfidence !in 0f..1f -> "minimum confidence out of range"
            c.digitGap !in 0f..0.8f -> "digit gap out of range"
            c.segmentThickness !in 0.05f..0.5f -> "segment thickness out of range"
            c.alignSearch !in 0f..0.3f -> "alignment search out of range"
            side(0, 1) < MIN_SIDE || side(3, 2) < MIN_SIDE || side(0, 3) < MIN_SIDE || side(1, 2) < MIN_SIDE -> "digit row too small"
            else -> null
        }
    }

    private fun side(a: Int, b: Int) = config.corners.let { hypot(it[a].x - it[b].x, it[a].y - it[b].y) }

    companion object {
        const val MAX_DIGITS = 12
        private val ROTATIONS = setOf(0, 90, 180, 270)
        /** Shortest side of the digit row, as a fraction of the photo. */
        private const val MIN_SIDE = 0.005f
    }
}
