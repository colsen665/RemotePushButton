package net.rpb.core.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the digit row in a photo, given the code the display shows (ADR 0016), so calibration needs no corners
 * placed by hand. It works in the order orientation, crop, read:
 * 1. **Orientation:** a matched filter built from the segment template scans a reduced copy of the photo, along
 *    each axis, over position, digit height, cell width and tilt, in both polarities. Each placement is scored
 *    for the code upright and turned 180° (the code reversed, each digit's segments turned), from the same ink
 *    measurements; the better fit tells which way up the display is. A 1, 3, 4 or 7 settles it; a code without
 *    one reads the same both ways, and the turn listed first wins.
 * 2. **Crop:** the best candidates in that orientation give the digit row at full size.
 * 3. **Read:** Refine (ADR 0013) fits the row, and the first fit that reads the code wins. A tilted candidate
 *    that doesn't read is fitted again, made level.
 */
object SevenSegLocator {
    class Located(
        /** Clockwise turn (0, 90, 180 or 270) that makes the digits upright; [config] belongs to the turned photo. */
        val turn: Int,
        val config: SevenSegConfig,
        val result: SevenSegResult,
        /**
         * The code reads the same turned 180° (it has no 1, 3, 4 or 7), so the opposite turn would read it too.
         * The turn listed first wins.
         */
        val upsideDownToo: Boolean,
    )

    /**
     * [base] supplies the starting shape (slant, gap, thickness) and the polarity tried first; its corners
     * and digit count are replaced. [turns] give the preference, so the likely one should come first: its axis
     * is searched first, and it wins when the code can't tell. About a second per axis searched, plus about as
     * long for each fit.
     */
    fun locate(img: GrayImage, code: String, base: SevenSegConfig = SevenSegConfig(), turns: List<Int> = TURNS): Located? {
        require(code.isNotEmpty() && code.all(Char::isDigit)) { "code must be digits" }
        val order = (turns.map { Math.floorMod(it, 360) } + TURNS).distinct()
        require(order.all { it % 90 == 0 }) { "turns must be quarter turns" }
        val polarities = listOf(base.polarity) + Polarity.entries.filter { it != base.polarity }
        val factor = max(1, ceil(max(img.width, img.height).toFloat() / SEARCH_SIDE).toInt())
        val upright = code.map(SegmentTemplate::patternsOf)
        val upsideDown = code.reversed().map { d -> SegmentTemplate.patternsOf(d).map(SegmentTemplate::turned) }
        val symmetric = code.reversed().map(SegmentTemplate::upsideDown).joinToString("") == code
        val photos = HashMap<Int, GrayImage>()
        fun photo(turn: Int) = photos.getOrPut(turn) { img.rotated(turn) }

        fun fitFrom(c: Candidate): Located? {
            val full = photo(c.turn)
            val corners = c.corners(code.length).map { Point(it.x * factor / full.width, it.y * factor / full.height) }
            if (corners.any { it.x !in 0f..1f || it.y !in 0f..1f }) return null
            val rough = base.copy(corners = corners, digits = code.length, polarity = c.polarity, slant = c.slant)
            val fitted = SevenSegCalibrator.refine(listOf(full), rough, expected = listOf(code))
            val result = SevenSegReader(fitted).read(full)
            return if (result.valid && result.text == code) Located(c.turn, fitted, result, symmetric) else null
        }

        // Faint digits at one end hardly pin the tilt, and Refine can't climb out of a wrong one; rigs sit near level.
        fun fit(c: Candidate): Located? = fitFrom(c) ?: if (c.tilt != 0f) fitFrom(c.levelled(code.length)) else null

        val weak = ArrayList<Candidate>()
        for (axis in order.map { it % 180 }.distinct()) {
            val small = photo(axis).downscaled(factor)
            // Turned 180°, the row is found in the axis photo with the code turned; a symmetric code can't tell.
            val ways = if (symmetric) listOf(order.first { it % 180 == axis }) else listOf(axis, axis + 180)
            val patterns = ways.map { if (it == axis) upright else upsideDown }
            val found = polarities.flatMap { search(small, patterns, it) }
                .map { c -> if (ways[c.way] == axis) c.copy(turn = axis) else c.flipped(code.length, photo(axis), factor).copy(turn = axis + 180) }
            val (strong, rest) = found.partition { it.score >= STRONG_SCORE }
            for (c in strong.sortedByDescending { it.score }.take(MAX_FITS)) fit(c)?.let { return it }
            weak += rest
        }
        // No clear match on either axis (a dim or unusual display): fit the best of the rest.
        for (c in weak.sortedByDescending { it.score }.take(MAX_FITS)) fit(c)?.let { return it }
        return null
    }

    /**
     * A digit row in the reduced photo turned by [turn]: top-left corner, digit height, cell width / digit height,
     * vertical drop per unit of width, and slant as in [SevenSegConfig.slant]. [way] indexes the pattern lists
     * searched.
     */
    private data class Candidate(
        val polarity: Polarity,
        val x: Float,
        val y: Float,
        val h: Float,
        val aspect: Float,
        val tilt: Float,
        val slant: Float = 0f,
        val score: Float = 0f,
        val way: Int = 0,
        val turn: Int = 0,
    ) {
        fun corners(digits: Int): List<Point> {
            val w = digits * aspect * h
            return listOf(Point(x, y), Point(x + w, y + w * tilt), Point(x + w, y + w * tilt + h), Point(x, y + h))
        }

        /** The same row made level about its centre. */
        fun levelled(digits: Int) = copy(y = y + digits * aspect * h * tilt / 2, tilt = 0f)

        /** The same row with the photo turned 180°, [full] being the photo reduced by [factor]. Slant and tilt keep their sign. */
        fun flipped(digits: Int, full: GrayImage, factor: Int): Candidate {
            val br = corners(digits)[2]
            return copy(x = full.width.toFloat() / factor - br.x, y = full.height.toFloat() / factor - br.y)
        }
    }

    private fun search(img: GrayImage, patterns: List<List<List<Int>>>, polarity: Polarity): List<Candidate> {
        val n = patterns[0].size
        val gray = Integral.of(img.width, img.height) { img.pixels[it].toInt() and 0xFF }
        val found = ArrayList<Candidate>()
        var h = MIN_HEIGHT
        while (h <= img.height * MAX_HEIGHT_SHARE && n * COARSE_ASPECTS.first() * h <= img.width) {
            val ink = inkMask(img, gray, polarity, (h * WINDOW).roundToInt())
            // The coarse grid would step right over the narrow peak of thin strokes; dilated ink widens it.
            val coarse = Filter(dilate(ink, max(1, (h * DILATE).roundToInt())), patterns, bands = false)
            val filter = Filter(ink, patterns, bands = true)
            val step = max(1f, h / COARSE_STEPS)
            for (aspect in COARSE_ASPECTS) for (tilt in COARSE_TILTS) {
                val best = List(patterns.size) { ArrayList<Candidate>() }
                val rowW = n * aspect * h
                val drop = rowW * tilt
                var y = max(0f, -drop)
                while (y + h + max(0f, drop) <= img.height) {
                    var x = 0f
                    while (x + rowW <= img.width) {
                        val scores = coarse.scores(x, y, h, aspect, tilt, 0f)
                        for (w in scores.indices) {
                            if (scores[w] > MIN_SCORE) best[w] += Candidate(polarity, x, y, h, aspect, tilt, score = scores[w], way = w)
                        }
                        x += step
                    }
                    y += step
                }
                // Per cell width and tilt, so a near miss in one doesn't hide a better start in another.
                for (list in best) found += suppress(list, n, PER_SHAPE).map { climb(filter, it.copy(score = filter.score(it))) }
            }
            h *= SCALE_STEP
        }
        return suppress(found, n, PER_POLARITY * patterns.size)
    }

    /** Keeps the best candidates, dropping any whose row centre lies within half a digit height of a better one's. */
    private fun suppress(candidates: List<Candidate>, digits: Int, keep: Int): List<Candidate> {
        val kept = ArrayList<Candidate>()
        fun centre(c: Candidate) = c.corners(digits).let { p -> Point((p[0].x + p[2].x) / 2, (p[0].y + p[2].y) / 2) }
        for (c in candidates.sortedByDescending { it.score }) {
            val cc = centre(c)
            val near = kept.any { k ->
                val kc = centre(k)
                val r = min(c.h, k.h) / 2
                (cc.x - kc.x) * (cc.x - kc.x) + (cc.y - kc.y) * (cc.y - kc.y) < r * r
            }
            if (!near) kept += c
            if (kept.size == keep) break
        }
        return kept
    }

    /** Hill-climbs position, height, cell width, tilt and slant. */
    private fun climb(filter: Filter, from: Candidate): Candidate {
        var best = from
        for (frac in CLIMB_STEPS) {
            var improved = true
            while (improved) {
                improved = false
                val d = best.h * frac
                val moves = listOf(
                    best.copy(x = best.x + d), best.copy(x = best.x - d),
                    best.copy(y = best.y + d), best.copy(y = best.y - d),
                    best.copy(h = best.h + d), best.copy(h = best.h - d, y = best.y + d),
                    best.copy(aspect = best.aspect * (1 + frac)), best.copy(aspect = best.aspect * (1 - frac)),
                    best.copy(tilt = best.tilt + frac), best.copy(tilt = best.tilt - frac),
                    best.copy(slant = (best.slant + frac).coerceAtMost(MAX_SLANT)), best.copy(slant = (best.slant - frac).coerceAtLeast(-MAX_SLANT)),
                )
                for (m in moves) {
                    val s = filter.score(m)
                    if (s > best.score + EPSILON) {
                        best = m.copy(score = s)
                        improved = true
                    }
                }
            }
        }
        return best
    }

    /** Pixels darker (or, for LED, lighter) than the mean of a [window]-sized square around them by [INK_CONTRAST]. */
    private fun inkMask(img: GrayImage, gray: Integral, polarity: Polarity, window: Int): Integral {
        val r = max(1, window / 2)
        return Integral.of(img.width, img.height) { i ->
            val x = i % img.width
            val y = i / img.width
            val mean = gray.mean(x - r, y - r, x + r + 1, y + r + 1)
            val v = img.pixels[i].toInt() and 0xFF
            val ink = if (polarity == Polarity.DARK_ON_LIGHT) v < mean - INK_CONTRAST else v > mean + INK_CONTRAST
            if (ink) 1 else 0
        }
    }

    private fun dilate(ink: Integral, r: Int): Integral = Integral.of(ink.width, ink.height) { i ->
        val x = i % ink.width
        val y = i / ink.width
        if (ink.sum(x - r, y - r, x + r + 1, y + r + 1) > 0) 1 else 0
    }

    /**
     * Scores how well each list of digit patterns in [ways] fits the ink at a placement: per digit, with whichever
     * of its patterns fits best (a 6 with or without its top bar), the mean ink of the segments that should be on
     * minus the mean ink of those that should be off and of the background: the two counters inside an 8 and,
     * with [bands], thin bands right above and below the digit, which pin its height. The ink is measured once
     * for all ways. 1 is a perfect fit; solid ink or blank background score about 0.
     */
    private class Filter(val ink: Integral, val ways: List<List<List<Int>>>, val bands: Boolean) {
        fun score(c: Candidate) = scores(c.x, c.y, c.h, c.aspect, c.tilt, c.slant)[c.way]

        fun scores(x: Float, y: Float, h: Float, aspect: Float, tilt: Float, slant: Float): FloatArray {
            val n = ways[0].size
            val cellW = aspect * h
            val digitW = cellW * (1 - GAP)
            val rects = SegmentTemplate.rects(THICKNESS, digitW / h)
            val t = THICKNESS
            val tv = THICKNESS * digitW / h
            val counters = listOf(SegRect(t, 1 - t, tv, 0.5f - tv / 2), SegRect(t, 1 - t, 0.5f + tv / 2, 1 - tv))
            val background = if (!bands) counters else counters + listOf(
                SegRect(t, 1 - t, -0.6f * tv, -0.1f * tv),
                SegRect(t, 1 - t, 1 + 0.1f * tv, 1 + 0.6f * tv),
            )
            val totals = FloatArray(ways.size)
            for (d in 0 until n) {
                val left = x + d * cellW + (cellW - digitW) / 2
                val top = y + (d + 0.5f) * cellW * tilt
                fun cover(r: SegRect): Float {
                    val mu = (r.u1 - r.u0) * MARGIN
                    val mv = (r.v1 - r.v0) * MARGIN
                    val lean = left + slant * (0.5f - (r.v0 + r.v1) / 2) * h
                    return ink.meanF(lean + (r.u0 + mu) * digitW, top + (r.v0 + mv) * h, lean + (r.u1 - mu) * digitW, top + (r.v1 - mv) * h)
                }
                val segments = rects.map(::cover)
                val backgroundInk = background.sumOf { cover(it).toDouble() }.toFloat()
                for ((w, way) in ways.withIndex()) {
                    totals[w] += way[d].maxOf { pattern ->
                        var on = 0f
                        var onCount = 0
                        var off = backgroundInk
                        var offCount = background.size
                        segments.forEachIndexed { s, c ->
                            if (pattern and (1 shl s) != 0) { on += c; onCount++ } else { off += c; offCount++ }
                        }
                        on / onCount - off / offCount
                    }
                }
            }
            for (w in totals.indices) totals[w] /= n
            return totals
        }
    }

    /** Summed-area table, so any rectangle's sum takes four lookups. */
    private class Integral(val width: Int, val height: Int, val sums: IntArray) {
        fun sum(x0: Int, y0: Int, x1: Int, y1: Int): Int {
            val a = x0.coerceIn(0, width)
            val b = y0.coerceIn(0, height)
            val c = x1.coerceIn(0, width)
            val d = y1.coerceIn(0, height)
            if (c <= a || d <= b) return 0
            val w = width + 1
            return sums[d * w + c] - sums[b * w + c] - sums[d * w + a] + sums[b * w + a]
        }

        fun mean(x0: Int, y0: Int, x1: Int, y1: Int): Float {
            val area = (x1.coerceIn(0, width) - x0.coerceIn(0, width)) * (y1.coerceIn(0, height) - y0.coerceIn(0, height))
            return if (area <= 0) 0f else sum(x0, y0, x1, y1).toFloat() / area
        }

        /** Mean over the pixels whose centres fall in the rectangle; at least one pixel. */
        fun meanF(x0: Float, y0: Float, x1: Float, y1: Float): Float {
            val a = x0.roundToInt()
            val b = y0.roundToInt()
            return mean(a, b, max(a + 1, x1.roundToInt()), max(b + 1, y1.roundToInt()))
        }

        companion object {
            fun of(width: Int, height: Int, value: (Int) -> Int): Integral {
                val w = width + 1
                val sums = IntArray(w * (height + 1))
                for (y in 0 until height) {
                    var row = 0
                    for (x in 0 until width) {
                        row += value(y * width + x)
                        sums[(y + 1) * w + x + 1] = sums[y * w + x + 1] + row
                    }
                }
                return Integral(width, height, sums)
            }
        }
    }

    private val TURNS = listOf(0, 90, 270, 180)

    /** Long side of the reduced search image, in pixels. */
    private const val SEARCH_SIDE = 800
    private const val MIN_HEIGHT = 28f
    private const val MAX_HEIGHT_SHARE = 0.6f
    private const val SCALE_STEP = 1.12f
    /** Coarse positions per digit height. */
    private const val COARSE_STEPS = 3f
    private val COARSE_ASPECTS = listOf(0.5f, 0.62f, 0.75f)
    private val COARSE_TILTS = listOf(-0.1f, 0f, 0.1f)
    private val CLIMB_STEPS = listOf(0.08f, 0.04f, 0.02f)
    /** The ink mask's averaging window, in digit heights. */
    private const val WINDOW = 1f
    /** Dilation of the ink for the coarse scan, in digit heights. */
    private const val DILATE = 0.06f
    private const val INK_CONTRAST = 6
    private const val MIN_SCORE = 0.2f
    private const val GAP = 0.16f
    private const val THICKNESS = 0.2f
    private const val MARGIN = 0.2f
    private const val PER_SHAPE = 2
    private const val PER_POLARITY = 4
    /** Candidates scoring at least this are fitted before other turns are searched; true rows score 0.8 and more. */
    private const val STRONG_SCORE = 0.7f
    private const val MAX_FITS = 3
    private const val MAX_SLANT = 0.3f
    private const val EPSILON = 1e-4f
}
