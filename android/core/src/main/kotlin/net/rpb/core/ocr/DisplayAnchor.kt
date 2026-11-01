package net.rpb.core.ocr

import kotlinx.serialization.Serializable
import java.util.Base64
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A shift, turn and scale of the photo in pixels: x' = a·x − b·y + tx, y' = b·x + a·y + ty. */
data class Move(val a: Float, val b: Float, val tx: Float, val ty: Float) {
    fun map(p: Point) = Point(a * p.x - b * p.y + tx, b * p.x + a * p.y + ty)

    val scale: Float get() = hypot(a, b)

    /** Clockwise, as the photo shows it. */
    val degrees: Float get() = Math.toDegrees(atan2(b, a).toDouble()).toFloat()
}

/** This calibration after the display made [move] in a [width] × [height] photo. */
fun SevenSegConfig.moved(move: Move, width: Int, height: Int) =
    copy(corners = corners.map { move.map(Point(it.x * width, it.y * height)).let { p -> Point(p.x / width, p.y / height) } })

/**
 * The photo around the calibrated digit row, kept so the device can follow the display when the rig is bumped
 * (ADR 0018). It is reduced to [PER_ROW] pixels per digit height, and the row itself is blanked, so it never
 * holds a code: what is left doesn't change between presses, on the display the LCD window frame and the
 * logo.
 */
@Serializable
data class DisplayAnchor(
    /** The calibration it was made for; it follows the display only for that one. */
    val calibration: Calibration,
    val photoWidth: Int,
    val photoHeight: Int,
    /** Where the patch starts in the photo, and the photo pixels each of its pixels covers. */
    val left: Float,
    val top: Float,
    val cell: Float,
    val width: Int,
    val height: Int,
    /** The patch, row-major, in Base64; the blanked row is 0. */
    val pixels: String,
) {
    /**
     * How the display moved between the anchor's photo and [photo], or null if it isn't clearly there. Each tile
     * of the patch is found on its own; the tiles that agree on one shift, turn and scale decide, so a tile hit
     * by glare, or on background that stayed put, is outvoted.
     */
    fun follow(photo: GrayImage): Move? {
        if (photo.width != photoWidth || photo.height != photoHeight) return null
        val row = Row(calibration.config, photoWidth, photoHeight)
        val bytes = Base64.getDecoder().decode(pixels)
        val patch = Patch(width, height, FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF).toFloat() }, usable(row))
        val r = (SEARCH * PER_ROW).toInt()
        val area = around(photo, r, null)

        // Roughly, at half resolution, from the whole patch.
        val half = patch.halved()
        val whole = half.part(0, 0, half.width, half.height) ?: return null
        val areaHalf = area.halved()
        var coarse = Double.NEGATIVE_INFINITY
        var cx = 0
        var cy = 0
        for (dy in -r / 2..r / 2) for (dx in -r / 2..r / 2) {
            val c = whole.ncc(areaHalf, r / 2 + dx, r / 2 + dy)
            if (c > coarse) {
                coarse = c
                cx = 2 * dx
                cy = 2 * dy
            }
        }
        if (coarse < MIN_COARSE) return null

        // Then tile by tile around that, and once more on the photo moved back by that fit: the tiles then line up
        // to within what it missed, and a tile that isn't turned or scaled against the photo is found precisely.
        val first = fit(tiles(patch, area, r, cx, cy, (REACH * PER_ROW).toInt()) { it }, row) ?: return null
        val fine = (FINE * PER_ROW).toInt()
        return fit(tiles(patch, around(photo, fine + 2, first), fine + 2, 0, 0, fine, first::map), row)
    }

    /** The photo under the patch and [r] patch pixels around it, where the display was before [move] if given. */
    private fun around(photo: GrayImage, r: Int, move: Move?) =
        Patch(width + 2 * r, height + 2 * r, sample(photo, left - r * cell, top - r * cell, cell, width + 2 * r, height + 2 * r, move))

    /**
     * Each tile of [patch] with contrast, found in [area] (which reaches [r] beyond the patch) within [reach] of the
     * shift [cx], [cy]. [toPhoto] maps a position in the area to the photo.
     */
    private fun tiles(patch: Patch, area: Patch, r: Int, cx: Int, cy: Int, reach: Int, toPhoto: (Point) -> Point): List<Match> {
        val matches = ArrayList<Match>()
        for (ty in 0..height - PER_ROW step PER_ROW) for (tx in 0..width - PER_ROW step PER_ROW) {
            val part = patch.part(tx, ty, tx + PER_ROW, ty + PER_ROW) ?: continue
            val x0 = max(-r + 2, cx - reach)
            val x1 = min(r - 2, cx + reach)
            val y0 = max(-r + 2, cy - reach)
            val y1 = min(r - 2, cy + reach)
            fun at(dx: Int, dy: Int) = part.ncc(area, r + dx, r + dy)
            var peak = Double.NEGATIVE_INFINITY
            var px = 0
            var py = 0
            for (dy in y0..y1) for (dx in x0..x1) {
                val c = at(dx, dy)
                if (c > peak) {
                    peak = c
                    px = dx
                    py = dy
                }
            }
            // On the edge of the search, the real peak may lie outside it.
            if (peak < MIN_TILE || px == x0 || px == x1 || py == y0 || py == y1) continue
            val centre = Point(left + (tx + PER_ROW / 2f) * cell, top + (ty + PER_ROW / 2f) * cell)
            val sx = px + vertex(at(px - 1, py), peak, at(px + 1, py))
            val sy = py + vertex(at(px, py - 1), peak, at(px, py + 1))
            // A tile with only a straight edge in it pins the shift across the edge, not along it.
            val useX = peak - max(at(px - 2, py), at(px + 2, py)) >= MIN_DROP
            val useY = peak - max(at(px, py - 2), at(px, py + 2)) >= MIN_DROP
            if (useX || useY) matches += Match(centre, toPhoto(Point(centre.x + sx * cell, centre.y + sy * cell)), useX, useY)
        }
        return matches
    }

    /** Patch pixels that lie away from the digit row. */
    private fun usable(row: Row): BooleanArray {
        val out = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            out[y * width + x] = !row.near(Point(left + (x + 0.5f) * cell, top + (y + 0.5f) * cell), MASK_MARGIN * row.height)
        }
        return out
    }

    /**
     * The shift, turn and scale that best fit the matched tiles, by least squares in units of the digit height
     * around the row's centre. The worst tile is dropped until every one fits within [MAX_RESIDUAL].
     */
    private fun fit(matches: List<Match>, row: Row): Move? {
        val s = row.height
        val c = row.centre
        val inliers = matches.toMutableList()
        while (true) {
            if (inliers.size < MIN_TILES) return null
            val p = solve(inliers, c, s) ?: return null
            val worst = inliers.maxBy { residual(it, p, c, s) }
            if (residual(worst, p, c, s) <= MAX_RESIDUAL) {
                val (a, b, u, v) = p
                val ys = inliers.filter { it.useY }
                if (inliers.count { it.useX } < MIN_AXIS || ys.size < MIN_AXIS) return null
                // The turn rests on matches spread along the row.
                if ((ys.maxOf { it.from.x } - ys.minOf { it.from.x }) / s < MIN_SPREAD) return null
                val move = Move(
                    a.toFloat(), b.toFloat(),
                    (c.x - a * c.x + b * c.y + s * u).toFloat(),
                    (c.y - b * c.x - a * c.y + s * v).toFloat(),
                )
                if (abs(move.degrees) > MAX_DEGREES || abs(move.scale - 1) > MAX_SCALE) return null
                val inside = row.corners.all { move.map(it).let { q -> q.x in 0f..photoWidth.toFloat() && q.y in 0f..photoHeight.toFloat() } }
                return move.takeIf { inside }
            }
            inliers.remove(worst)
        }
    }

    companion object {
        /** Patch pixels per digit height; also the side of a tile. */
        const val PER_ROW = 16
        /** Patch beyond the row's bounding box, in digit heights: enough for the logo beside the window. */
        const val MARGIN_X = 3f
        const val MARGIN_Y = 1.5f
        /** Blanked around the row, in digit heights: the slant, stroke width and blur of the digits. */
        const val MASK_MARGIN = 0.3f
        /** How far the display may have moved, in digit heights. */
        const val SEARCH = 2f
        /** How far each tile may lie from the rough shift, in digit heights: the turn and scale at the patch's ends. */
        const val REACH = 0.6f
        /** How far each tile may lie from the first fit, in digit heights. */
        const val FINE = 0.2f
        const val MIN_COARSE = 0.3
        const val MIN_TILE = 0.6
        /** Correlation lost two patch pixels off the peak, for the tile to count along that axis. */
        const val MIN_DROP = 0.1
        /** Standard deviation in grey levels for a tile to count as more than plain plastic. */
        const val MIN_CONTRAST = 6.0
        const val MIN_TILES = 6
        const val MIN_AXIS = 3
        /** In digit heights. */
        const val MAX_RESIDUAL = 0.1
        const val MIN_SPREAD = 2f
        const val MAX_DEGREES = 5f
        const val MAX_SCALE = 0.1f
        const val MAX_SAMPLES = 8

        /**
         * The anchor for [calibration], made from [photo], or null if there is too little around the row to follow
         * (fewer than [MIN_TILES] tiles with contrast).
         */
        fun make(photo: GrayImage, calibration: Calibration): DisplayAnchor? {
            val row = Row(calibration.config, photo.width, photo.height)
            val cell = row.height / PER_ROW
            val left = max(0f, row.corners.minOf { it.x } - MARGIN_X * row.height)
            val top = max(0f, row.corners.minOf { it.y } - MARGIN_Y * row.height)
            val right = min(photo.width.toFloat(), row.corners.maxOf { it.x } + MARGIN_X * row.height)
            val bottom = min(photo.height.toFloat(), row.corners.maxOf { it.y } + MARGIN_Y * row.height)
            val w = ((right - left) / cell).toInt()
            val h = ((bottom - top) / cell).toInt()
            if (w < PER_ROW || h < PER_ROW) return null
            val blank = DisplayAnchor(calibration, photo.width, photo.height, left, top, cell, w, h, "")
            val usable = blank.usable(row)
            val values = sample(photo, left, top, cell, w, h)
            for (i in values.indices) if (!usable[i]) values[i] = 0f
            val patch = Patch(w, h, values, usable)
            val tiles = (0..h - PER_ROW step PER_ROW).sumOf { y ->
                (0..w - PER_ROW step PER_ROW).count { x -> patch.part(x, y, x + PER_ROW, y + PER_ROW) != null }
            }
            if (tiles < MIN_TILES) return null
            val bytes = ByteArray(values.size) { values[it].roundToInt().coerceIn(0, 255).toByte() }
            return blank.copy(pixels = Base64.getEncoder().encodeToString(bytes))
        }

        /**
         * Mean of each [cell]-sized square from ([left], [top]), [w] × [h] of them, by bilinear samples; each
         * sample taken where [move] puts it, if given.
         */
        private fun sample(photo: GrayImage, left: Float, top: Float, cell: Float, w: Int, h: Int, move: Move? = null): FloatArray {
            val k = cell.roundToInt().coerceIn(1, MAX_SAMPLES)
            val out = FloatArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var sum = 0f
                for (j in 0 until k) for (i in 0 until k) {
                    val p = Point(left + (x + (i + 0.5f) / k) * cell, top + (y + (j + 0.5f) / k) * cell)
                    val q = move?.map(p) ?: p
                    sum += photo.sample(q.x, q.y)
                }
                out[y * w + x] = sum / (k * k)
            }
            return out
        }

        /** Where the parabola through three equally spaced values peaks, relative to the middle one. */
        private fun vertex(before: Double, peak: Double, after: Double): Float {
            val curve = before - 2 * peak + after
            return if (curve >= 0) 0f else (0.5 * (before - after) / curve).toFloat().coerceIn(-0.5f, 0.5f)
        }

        /** Parameters a, b, u, v of the similarity, in digit heights [s] around [c]. */
        private fun solve(matches: List<Match>, c: Point, s: Float): DoubleArray? {
            val ata = Array(4) { DoubleArray(4) }
            val atb = DoubleArray(4)
            fun add(row: DoubleArray, value: Double) {
                for (i in 0 until 4) {
                    for (j in 0 until 4) ata[i][j] += row[i] * row[j]
                    atb[i] += row[i] * value
                }
            }
            for (m in matches) {
                val x = ((m.from.x - c.x) / s).toDouble()
                val y = ((m.from.y - c.y) / s).toDouble()
                if (m.useX) add(doubleArrayOf(x, -y, 1.0, 0.0), ((m.to.x - c.x) / s).toDouble())
                if (m.useY) add(doubleArrayOf(y, x, 0.0, 1.0), ((m.to.y - c.y) / s).toDouble())
            }
            return gauss(ata, atb)
        }

        private fun residual(m: Match, p: DoubleArray, c: Point, s: Float): Double {
            val (a, b, u, v) = p
            val x = ((m.from.x - c.x) / s).toDouble()
            val y = ((m.from.y - c.y) / s).toDouble()
            val rx = if (m.useX) abs(a * x - b * y + u - (m.to.x - c.x) / s) else 0.0
            val ry = if (m.useY) abs(b * x + a * y + v - (m.to.y - c.y) / s) else 0.0
            return max(rx, ry)
        }

        /** Solves [m] · x = [b] by elimination with partial pivoting; null if [m] is singular. */
        private fun gauss(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(m[it][col]) }
                if (abs(m[pivot][col]) < 1e-9) return null
                m[col] = m[pivot].also { m[pivot] = m[col] }
                b[col] = b[pivot].also { b[pivot] = b[col] }
                for (r in col + 1 until n) {
                    val f = m[r][col] / m[col][col]
                    for (k in col until n) m[r][k] -= f * m[col][k]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) x[r] = (b[r] - (r + 1 until n).sumOf { m[r][it] * x[it] }) / m[r][r]
            return x
        }
    }
}

/** A tile's centre in the anchor's photo ([from]) and where it was found ([to]), and which axes it pins. */
private class Match(val from: Point, val to: Point, val useX: Boolean, val useY: Boolean)

/** The calibrated row in photo pixels. */
private class Row(config: SevenSegConfig, photoWidth: Int, photoHeight: Int) {
    val corners = config.corners.map { Point(it.x * photoWidth, it.y * photoHeight) }
    val height = (hypot(corners[3].x - corners[0].x, corners[3].y - corners[0].y) + hypot(corners[2].x - corners[1].x, corners[2].y - corners[1].y)) / 2
    val centre = Point(corners.sumOf { it.x.toDouble() }.toFloat() / 4, corners.sumOf { it.y.toDouble() }.toFloat() / 4)

    /** Whether [p] lies inside the row, or less than [margin] outside one of its edges. */
    fun near(p: Point, margin: Float) = (0 until 4).all { i ->
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / hypot(b.x - a.x, b.y - a.y) > -margin
    }
}

/** Grey values, row-major, and which of them count. */
private class Patch(val width: Int, val height: Int, val values: FloatArray, val usable: BooleanArray = BooleanArray(values.size) { true }) {
    fun halved(): Patch {
        val w = width / 2
        val h = height / 2
        val v = FloatArray(w * h)
        val u = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = 2 * y * width + 2 * x
            v[y * w + x] = (values[i] + values[i + 1] + values[i + width] + values[i + width + 1]) / 4
            u[y * w + x] = usable[i] && usable[i + 1] && usable[i + width] && usable[i + width + 1]
        }
        return Patch(w, h, v, u)
    }

    /** The usable pixels in [x0, x1) × [y0, y1), or null if fewer than half are usable or they are too even. */
    fun part(x0: Int, y0: Int, x1: Int, y1: Int): Part? {
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in y0 until y1) for (x in x0 until x1) if (usable[y * width + x]) {
            xs += x
            ys += y
        }
        if (xs.size * 2 < (x1 - x0) * (y1 - y0)) return null
        val mean = xs.indices.sumOf { values[ys[it] * width + xs[it]].toDouble() } / xs.size
        val centred = DoubleArray(xs.size) { values[ys[it] * width + xs[it]] - mean }
        val norm = sqrt(centred.sumOf { it * it })
        if (norm / sqrt(xs.size.toDouble()) < DisplayAnchor.MIN_CONTRAST) return null
        return Part(xs.toIntArray(), ys.toIntArray(), centred, norm)
    }
}

/** Some pixels of a patch less their mean, for normalised cross-correlation. */
private class Part(val xs: IntArray, val ys: IntArray, val values: DoubleArray, val norm: Double) {
    /** Correlation with [area] when patch pixel (x, y) lies on area pixel (x + [ox], y + [oy]). */
    fun ncc(area: Patch, ox: Int, oy: Int): Double {
        var st = 0.0
        var sum = 0.0
        var squares = 0.0
        for (k in xs.indices) {
            val v = area.values[(ys[k] + oy) * area.width + xs[k] + ox].toDouble()
            st += values[k] * v
            sum += v
            squares += v * v
        }
        val spread = squares - sum * sum / xs.size
        return if (spread < 1e-6) -1.0 else st / (norm * sqrt(spread))
    }
}
