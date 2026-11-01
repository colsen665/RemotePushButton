package net.rpb.core.ocr

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

@Serializable
enum class Polarity {
    /** LCD: dark segments on a light background. */
    DARK_ON_LIGHT,

    /** LED / backlit: light segments on a dark background. */
    LIGHT_ON_DARK,
}

/**
 * Calibration for one display. Corners are normalised (0..1) image coordinates around the digit row,
 * TL, TR, BR, BL, hugging the top and bottom of the digits.
 */
@Serializable
data class SevenSegConfig(
    val corners: List<Point> = listOf(Point(0.2f, 0.4f), Point(0.8f, 0.4f), Point(0.8f, 0.6f), Point(0.2f, 0.6f)),
    val digits: Int = 6,
    /** Horizontal lean of the top relative to the middle, as a fraction of digit height (italic displays ≈ 0.1). */
    val slant: Float = 0f,
    val polarity: Polarity = Polarity.DARK_ON_LIGHT,
    /** Fraction of a segment area that must be ink for the segment to count as on. */
    val onThreshold: Float = 0.5f,
    /** Every digit must reach this confidence for the result to be valid. */
    val minConfidence: Float = 0.35f,
    /** Gap between digits as a fraction of each digit cell. */
    val digitGap: Float = 0.16f,
    /** Segment stroke width as a fraction of digit width. */
    val segmentThickness: Float = 0.2f,
    /** Multiplexed LED displays: combine lit segments across frames instead of picking the best frame. */
    val ledMultiplex: Boolean = false,
    /** How far each read may move the digit row to re-align it, as a fraction of digit height; 0 turns it off. */
    val alignSearch: Float = 0.06f,
)

data class DigitRead(val digit: Char?, val confidence: Float, val coverage: List<Float>, val pattern: Int)

data class SevenSegResult(
    val digits: List<DigitRead>,
    val valid: Boolean,
    val confidence: Float,
    /** How far the alignment search moved the digit row, as a fraction of digit height (x right, y down). */
    val shift: Point = Point(0f, 0f),
) {
    /** Unknown digits are shown as '?'. */
    val text: String get() = digits.joinToString("") { (it.digit ?: '?').toString() }
}

/** A segment's outline in source-image pixels, for drawing the calibration overlay. */
data class SegmentOverlay(val digit: Int, val segment: Int, val points: List<Point>)

/**
 * Template-based 7-segment decoder. Works because the camera is rigidly mounted: once the digit
 * row is calibrated, each segment is always in the same place, so we just measure how much "ink"
 * each segment area contains. Each read may shift the row slightly to absorb mount drift (ADR 0013).
 */
class SevenSegReader(val config: SevenSegConfig) {

    fun read(img: GrayImage): SevenSegResult = readInk(prepare(img))

    /**
     * Reads several frames of the same display. LED mode ORs the ink across frames (multiplexed
     * digits may be dark in any single frame); LCD mode keeps the best single frame.
     */
    fun readFrames(frames: List<GrayImage>): SevenSegResult {
        require(frames.isNotEmpty())
        if (frames.size == 1) return read(frames[0])
        if (config.ledMultiplex) return readInk(frames.map(::prepare).reduce(Ink::or))
        return frames.map(::read).maxWith(compareBy<SevenSegResult> { it.valid }.thenBy { it.confidence })
    }

    fun overlay(width: Int, height: Int): List<SegmentOverlay> {
        val ink = Ink.geometry(config, width, height)
        val template = Template(config, ink)
        val out = ArrayList<SegmentOverlay>()
        for (d in 0 until config.digits) {
            template.rects.forEachIndexed { s, r ->
                val pts = listOf(r.u0 to r.v0, r.u1 to r.v0, r.u1 to r.v1, r.u0 to r.v1).map { (u, v) ->
                    val p = template.toRow(d, u, v)
                    ink.homography.map(p.x, p.y)
                }
                out += SegmentOverlay(d, s, pts)
            }
        }
        return out
    }

    /**
     * Rectifies and thresholds the calibrated row plus a margin, so placements can be measured without re-warping.
     * A row taller than [WARP_HEIGHT] in the image (a full-size crop, ADR 0017) is averaged over several samples
     * per pixel instead of skipping most of them.
     */
    internal fun prepare(img: GrayImage): Ink {
        val geo = Ink.geometry(config, img.width, img.height)
        val k = max(1, (geo.sourceHeight / geo.rowH).toInt())
        val warped = GrayImage(geo.width, geo.height)
        for (y in 0 until geo.height) {
            for (x in 0 until geo.width) {
                var sum = 0f
                for (j in 0 until k) for (i in 0 until k) {
                    val p = geo.homography.map(geo.rowX(x + (i + 0.5f) / k), geo.rowY(y + (j + 0.5f) / k))
                    sum += img.sample(p.x, p.y)
                }
                warped[x, y] = (sum / (k * k)).roundToInt()
            }
        }
        val blurred = warped.boxBlur3()
        val mask = BooleanArray(blurred.pixels.size)
        val thresholds = thresholds(blurred, geo) ?: return geo.withMask(mask)
        val cellW = geo.rowW.toFloat() / config.digits
        for (x in 0 until geo.width) {
            // Interpolate between digit-cell centres so the threshold follows uneven light smoothly.
            val c = ((x - geo.margin + 0.5f) / cellW - 0.5f).coerceIn(0f, config.digits - 1f)
            val i = c.toInt().coerceAtMost(config.digits - 2).coerceAtLeast(0)
            val t = if (config.digits == 1) thresholds[0].toFloat() else thresholds[i] + (thresholds[i + 1] - thresholds[i]) * (c - i)
            for (y in 0 until geo.height) {
                val v = blurred[x, y]
                mask[y * geo.width + x] = if (config.polarity == Polarity.DARK_ON_LIGHT) v <= t else v > t
            }
        }
        return geo.withMask(mask)
    }

    /**
     * Otsu threshold per digit cell, so glare across part of the display doesn't wash out those digits. A cell
     * whose split doesn't look like segments on background (too little contrast, or an ink share outside
     * [CELL_INK_SHARE], as in a blank digit or a shadowed edge) uses the whole row's. Thresholds come from the
     * calibrated row only, since the margin may include the display's frame. Null if the row is flat (blank or
     * out of focus): then there is no ink at all.
     */
    private fun thresholds(blurred: GrayImage, geo: Ink): IntArray? {
        fun strip(x0: Int, x1: Int): OtsuResult {
            val img = GrayImage(x1 - x0, geo.rowH)
            for (y in 0 until geo.rowH) for (x in x0 until x1) img[x - x0, y] = blurred[x + geo.margin, y + geo.margin]
            return Otsu.compute(img)
        }
        val row = strip(0, geo.rowW)
        if (row.contrast < MIN_CONTRAST) return null
        return IntArray(config.digits) { d ->
            val cell = strip(d * geo.rowW / config.digits, (d + 1) * geo.rowW / config.digits)
            val inkShare = if (config.polarity == Polarity.DARK_ON_LIGHT) cell.lowFraction else 1 - cell.lowFraction
            if (cell.contrast >= MIN_CONTRAST && inkShare in CELL_INK_SHARE) cell.threshold else row.threshold
        }
    }

    private fun readInk(ink: Ink): SevenSegResult {
        val template = Template(config, ink)
        val placement = if (config.alignSearch > 0f) align(ink, template) else Placement.identity(config.digits)
        val result = rejectAmbiguous(decode(List(config.digits) { d -> coverages(ink, template, placement, d, SAMPLE_MARGIN) }), ink, template, placement)
        val shift = placement.corners[0].let { Point(it.x * ink.rowW / ink.rowH, it.y) }
        return result.copy(shift = shift)
    }

    /**
     * Misalignment loses segments rather than adding them, and the search favours clean reads, so a half-covered
     * segment can drop out and turn a 9 into a 3. A digit with ink right beside a segment it reads as off is
     * marked unknown instead.
     */
    private fun rejectAmbiguous(result: SevenSegResult, ink: Ink, template: Template, placement: Placement): SevenSegResult {
        val digits = result.digits.mapIndexed { d, read ->
            if (read.digit != null && inkBesideOff(ink, template, placement, d, read.pattern)) read.copy(digit = null, confidence = 0f) else read
        }
        val valid = digits.all { it.digit != null && it.confidence >= config.minConfidence }
        return result.copy(digits = digits, valid = valid && result.valid, confidence = digits.minOf { it.confidence })
    }

    /** Ink right beside a segment read as off means the segment slid out of its template area. */
    private fun inkBesideOff(ink: Ink, template: Template, placement: Placement, digit: Int, pattern: Int): Boolean =
        template.rects.withIndex().any { (s, r) ->
            if (pattern and (1 shl s) != 0) return@any false
            val vertical = (r.u1 - r.u0) * template.digitW < (r.v1 - r.v0) * ink.rowH
            // Widen across the stroke, trim along it so the joints with neighbouring segments don't count.
            val cov = if (vertical) {
                val w = (r.u1 - r.u0) * BESIDE_WIDTH
                coverage(ink, template, placement, digit, r.copy(u0 = r.u0 - w, u1 = r.u1 + w), 0f, SAMPLE_MARGIN)
            } else {
                val h = (r.v1 - r.v0) * BESIDE_WIDTH
                coverage(ink, template, placement, digit, r.copy(v0 = r.v0 - h, v1 = r.v1 + h), SAMPLE_MARGIN, 0f)
            }
            cov >= BESIDE_INK
        }

    /**
     * Best whole-row shift within [SevenSegConfig.alignSearch], then up to one pixel per digit, chosen by that
     * digit's confidence first. Ties keep the calibration.
     */
    private fun align(ink: Ink, template: Template): Placement {
        val r = (config.alignSearch * ink.rowH).toInt()
        val identity = Placement.identity(config.digits)
        if (r == 0) return identity
        val dx = 1f / ink.rowW
        val dy = 1f / ink.rowH
        val offsets = (-r..r).flatMap { x -> (-r..r).map { y -> x to y } }
            .filter { (x, y) -> x * x + y * y <= r * r }
            .sortedBy { (x, y) -> x * x + y * y }
        var best = identity
        var bestScore = Float.NEGATIVE_INFINITY
        fun consider(p: Placement) {
            val s = (0 until config.digits).sumOf { alignScore(ink, template, p, it).toDouble() }.toFloat()
            if (s > bestScore + SCORE_EPSILON) {
                best = p
                bestScore = s
            }
        }
        for ((x, y) in offsets) consider(identity.shifted(x * dx, y * dy))
        val nudges = NUDGES.flatMap { x -> NUDGES.map { y -> x to y } }.sortedBy { (x, y) -> x * x + y * y }
        val shifts = MutableList(config.digits) { Point(0f, 0f) }
        for (d in 0 until config.digits) {
            var digitBest = Float.NEGATIVE_INFINITY
            for ((x, y) in nudges) {
                val p = best.copy(digitShifts = best.digitShifts.toMutableList().also { it[d] = Point(x * dx, y * dy) })
                val conf = decode(listOf(coverages(ink, template, p, d, SAMPLE_MARGIN))).confidence
                val s = conf * DIGIT_BONUS + alignScore(ink, template, p, d)
                if (s > digitBest + SCORE_EPSILON) {
                    digitBest = s
                    shifts[d] = Point(x * dx, y * dy)
                }
            }
        }
        return best.copy(digitShifts = shifts)
    }

    /**
     * How cleanly a digit's segments split into on and off at this placement; higher is better. Only a
     * known digit pattern (the [expected] one, if given) earns the bonus, so blank background (every
     * segment clearly off) doesn't win.
     */
    internal fun alignScore(ink: Ink, template: Template, placement: Placement, digit: Int, expected: Char? = null): Float {
        val inner = coverages(ink, template, placement, digit, SAMPLE_MARGIN)
        val full = coverages(ink, template, placement, digit, 0f)
        var score = 0f
        var pattern = 0
        for (s in 0 until 7) {
            score += margin(inner[s]) + margin(full[s])
            if (inner[s] >= config.onThreshold) pattern = pattern or (1 shl s)
        }
        val read = SegmentTemplate.digitFor(pattern)
        val known = if (expected == null) read != null else read == expected
        return if (known) score + DIGIT_BONUS else score
    }

    private fun margin(c: Float): Float {
        val thr = config.onThreshold
        return if (c >= thr) (c - thr) / (1 - thr) else (thr - c) / thr
    }

    private fun decode(coverages: List<FloatArray>): SevenSegResult {
        val thr = config.onThreshold
        val digits = coverages.map { cov ->
            var pattern = 0
            var conf = 1f
            cov.forEachIndexed { s, c ->
                if (c >= thr) pattern = pattern or (1 shl s)
                conf = minOf(conf, margin(c).coerceIn(0f, 1f))
            }
            val digit = SegmentTemplate.digitFor(pattern)
            DigitRead(digit, if (digit == null) 0f else conf, cov.toList(), pattern)
        }
        val confidence = digits.minOfOrNull { it.confidence } ?: 0f
        val valid = digits.size == config.digits && digits.all { it.digit != null && it.confidence >= config.minConfidence }
        return SevenSegResult(digits, valid, confidence)
    }

    /** Ink coverage (0..1) of each segment of one digit; [sampleMargin] trims each segment's sides. */
    private fun coverages(ink: Ink, template: Template, placement: Placement, digit: Int, sampleMargin: Float) =
        FloatArray(7) { s -> coverage(ink, template, placement, digit, template.rects[s], sampleMargin) }

    private fun coverage(
        ink: Ink,
        template: Template,
        placement: Placement,
        digit: Int,
        rect: SegRect,
        marginU: Float,
        marginV: Float = marginU,
    ): Float {
        // Sampling only the inner part of the segment keeps small misalignments and the joints between
        // segments from bleeding in.
        val mu = (rect.u1 - rect.u0) * marginU
        val mv = (rect.v1 - rect.v0) * marginV
        val u0 = rect.u0 + mu
        val u1 = rect.u1 - mu
        val v0 = rect.v0 + mv
        val v1 = rect.v1 - mv
        val stepsU = maxOf(3, ((u1 - u0) * template.digitW).roundToInt())
        val stepsV = maxOf(3, ((v1 - v0) * ink.rowH).roundToInt())
        val shift = placement.digitShifts[digit]
        var hits = 0
        for (j in 0 until stepsV) {
            val v = v0 + (v1 - v0) * (j + 0.5f) / stepsV
            for (i in 0 until stepsU) {
                val u = u0 + (u1 - u0) * (i + 0.5f) / stepsU
                val r = template.toRow(digit, u, v)
                val p = placement.map(r.x + shift.x, r.y + shift.y)
                if (ink.at(p.x, p.y)) hits++
            }
        }
        return hits.toFloat() / (stepsU * stepsV)
    }

    companion object {
        const val WARP_HEIGHT = 96
        const val MIN_CONTRAST = 20f
        const val SAMPLE_MARGIN = 0.2f

        /** Margin around the calibrated row in the prepared image, as a fraction of digit height. */
        const val INK_MARGIN = 0.25f
        private const val SCORE_EPSILON = 1e-4f
        /** The band checked beside an off segment, each side, as a fraction of the stroke width. */
        private const val BESIDE_WIDTH = 0.5f
        private const val BESIDE_INK = 0.25f
        private val CELL_INK_SHARE = 0.04f..0.5f
        /** Per-digit adjustment in half pixels: a blurry digit may read only within a narrow window. */
        private val NUDGES = listOf(-1f, -0.5f, 0f, 0.5f, 1f)

        /** Larger than the 14 points a digit can earn from margins alone. */
        private const val DIGIT_BONUS = 20f
    }
}

/**
 * The rectified digit row plus a margin of [margin] pixels on every side. Row units (0..1 on both axes)
 * span the calibrated corners; [mask] marks ink pixels.
 */
internal class Ink(
    val homography: Homography,
    val rowW: Int,
    val rowH: Int,
    val margin: Int,
    val mask: BooleanArray,
    /** Height of the calibrated row in the source image, in pixels. */
    val sourceHeight: Float = rowH.toFloat(),
) {
    val width = rowW + 2 * margin
    val height = rowH + 2 * margin

    /** Prepared-image pixel → row units. */
    fun rowX(px: Float) = (px - margin) / rowW
    fun rowY(py: Float) = (py - margin) / rowH

    fun at(x: Float, y: Float): Boolean {
        val px = floor(margin + x * rowW).toInt().coerceIn(0, width - 1)
        val py = floor(margin + y * rowH).toInt().coerceIn(0, height - 1)
        return mask[py * width + px]
    }

    fun withMask(mask: BooleanArray) = Ink(homography, rowW, rowH, margin, mask, sourceHeight)

    fun or(other: Ink) = withMask(BooleanArray(mask.size) { mask[it] || other.mask[it] })

    companion object {
        fun geometry(config: SevenSegConfig, imgW: Int, imgH: Int): Ink {
            val corners = config.corners.map { Point(it.x * imgW, it.y * imgH) }
            val (tl, tr, br, bl) = corners
            val width = (dist(tl, tr) + dist(bl, br)) / 2
            val height = (dist(tl, bl) + dist(tr, br)) / 2
            val rowH = SevenSegReader.WARP_HEIGHT
            val rowW = (rowH * width / height).roundToInt().coerceIn(config.digits * 8, 4000)
            val margin = (SevenSegReader.INK_MARGIN * rowH).roundToInt()
            return Ink(Homography(corners), rowW, rowH, margin, BooleanArray(0), height)
        }

        private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
    }
}

/** Digit cells and segment areas for a row of the given size. */
internal class Template(config: SevenSegConfig, ink: Ink) {
    private val digits = config.digits
    private val slant = config.slant
    private val rowW = ink.rowW.toFloat()
    private val rowH = ink.rowH.toFloat()
    private val cellW = rowW / digits
    val digitW = cellW * (1 - config.digitGap)
    val rects = SegmentTemplate.rects(config.segmentThickness, digitW / rowH)

    /** Digit-local (u, v) → row units, applying slant around the vertical middle. */
    fun toRow(digit: Int, u: Float, v: Float): Point {
        val left = digit * cellW + (cellW - digitW) / 2
        return Point((left + u * digitW + slant * (0.5f - v) * rowH) / rowW, v)
    }
}

/**
 * Where the digit row sits in the prepared image, in row units: the row's corners (TL, TR, BR, BL; the
 * calibration is the unit square) plus a small extra shift per digit. The map is projective, so moving the
 * corners can also correct the perspective of a rough calibration, and the refined corners give exactly
 * the warp a later read uses.
 */
internal data class Placement(val corners: List<Point>, val digitShifts: List<Point>) {
    private val homography = Homography(corners)

    fun map(x: Float, y: Float): Point = homography.map(x, y)

    fun shifted(dx: Float, dy: Float) = copy(corners = corners.map { Point(it.x + dx, it.y + dy) })

    companion object {
        private val UNIT = listOf(Point(0f, 0f), Point(1f, 0f), Point(1f, 1f), Point(0f, 1f))

        fun identity(digits: Int) = Placement(UNIT, List(digits) { Point(0f, 0f) })
    }
}
