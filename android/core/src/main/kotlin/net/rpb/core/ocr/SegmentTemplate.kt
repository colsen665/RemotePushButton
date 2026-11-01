package net.rpb.core.ocr

/** A segment's area in digit-local coordinates: u across (0 = left), v down (0 = top). */
data class SegRect(val u0: Float, val u1: Float, val v0: Float, val v1: Float)

/**
 * Standard 7-segment layout and digit patterns.
 *
 * ```
 *    aaa
 *   f   b
 *    ggg
 *   e   c
 *    ddd
 * ```
 * Bit i of a pattern is segment "abcdefg"[i].
 */
object SegmentTemplate {
    const val NAMES = "abcdefg"

    /**
     * @param thickness vertical-segment width as a fraction of digit width
     * @param aspect digit width / digit height, used to keep horizontal segments equally thick in pixels
     */
    fun rects(thickness: Float, aspect: Float): List<SegRect> {
        val t = thickness
        val tv = thickness * aspect
        val mid = 0.5f
        return listOf(
            SegRect(t, 1 - t, 0f, tv),                       // a
            SegRect(1 - t, 1f, tv, mid - tv / 2),            // b
            SegRect(1 - t, 1f, mid + tv / 2, 1 - tv),        // c
            SegRect(t, 1 - t, 1 - tv, 1f),                   // d
            SegRect(0f, t, mid + tv / 2, 1 - tv),            // e
            SegRect(0f, t, tv, mid - tv / 2),                // f
            SegRect(t, 1 - t, mid - tv / 2, mid + tv / 2),   // g
        )
    }

    private val patterns: Map<Int, Char> = mapOf(
        0x3F to '0',
        0x06 to '1',
        0x5B to '2',
        0x4F to '3',
        0x66 to '4',
        0x6D to '5',
        0x7D to '6', 0x7C to '6', // with and without the top bar
        0x07 to '7', 0x27 to '7', // with and without the upper-left bar
        0x7F to '8',
        0x6F to '9', 0x67 to '9', // with and without the bottom bar
    )

    fun digitFor(pattern: Int): Char? = patterns[pattern]

    /** Every pattern that reads as [digit]. */
    fun patternsOf(digit: Char): List<Int> = patterns.filterValues { it == digit }.keys.toList()

    /** [pattern] turned 180°: a↔d, b↔e, c↔f, g stays. */
    fun turned(pattern: Int): Int {
        fun bit(s: Int) = (pattern shr s) and 1
        return (bit(3) shl 0) or (bit(4) shl 1) or (bit(5) shl 2) or (bit(0) shl 3) or (bit(1) shl 4) or (bit(2) shl 5) or (bit(6) shl 6)
    }

    /**
     * What [digit] looks like turned 180°, or null if that isn't a digit: 1, 3, 4 and 7 aren't, so they show which
     * way up a display is.
     */
    fun upsideDown(digit: Char): Char? = digitFor(turned(patternFor(digit)))

    /** Canonical pattern for rendering a digit (test fixtures, previews). */
    fun patternFor(digit: Char): Int = when (digit) {
        '0' -> 0x3F
        '1' -> 0x06
        '2' -> 0x5B
        '3' -> 0x4F
        '4' -> 0x66
        '5' -> 0x6D
        '6' -> 0x7D
        '7' -> 0x07
        '8' -> 0x7F
        '9' -> 0x6F
        else -> throw IllegalArgumentException("not a digit: $digit")
    }
}
