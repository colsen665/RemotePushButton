package net.rpb.core.ocr

/**
 * Fits a rough calibration to photos of the display (ADR 0013). It moves the corners, and optionally the
 * slant, digit gap and segment thickness, until every segment reads clearly on or off. Knowing the code
 * each frame shows ([expected]) rules out fits that read clearly but wrongly; without it the user has to
 * check the read.
 */
object SevenSegCalibrator {

    fun refine(frames: List<GrayImage>, config: SevenSegConfig, fitShape: Boolean = true, expected: List<String>? = null): SevenSegConfig {
        require(expected == null || expected.size == frames.size) { "one expected code per frame" }
        require(expected == null || expected.all { code -> code.length == config.digits && code.all(Char::isDigit) }) {
            "expected codes must have ${config.digits} digits"
        }
        // The threshold comes from the calibrated row, so a second pass re-thresholds with the refined corners.
        val first = pass(frames, config, fitShape, expected)
        return pass(frames, first, fitShape, expected)
    }

    private fun pass(frames: List<GrayImage>, config: SevenSegConfig, fitShape: Boolean, expected: List<String>?): SevenSegConfig {
        require(frames.isNotEmpty())
        require(frames.all { it.width == frames[0].width && it.height == frames[0].height }) { "frames differ in size" }
        val start = config.copy(alignSearch = 0f)
        val inks = frames.map(SevenSegReader(start)::prepare)
        val ink = inks[0]
        val px = 1f / ink.rowW
        val py = 1f / ink.rowH
        val noShifts = List(config.digits) { Point(0f, 0f) }

        fun score(c: Candidate): Float {
            val reader = SevenSegReader(c.shape)
            val template = Template(c.shape, ink)
            val placement = Placement(c.corners, noShifts)
            var total = 0f
            for ((f, i) in inks.withIndex()) {
                for (d in 0 until config.digits) total += reader.alignScore(i, template, placement, d, expected?.get(f)?.get(d))
            }
            return total
        }

        // Coarse grid over shift and width, then hill-climb from the best few.
        val unit = Placement.identity(config.digits).corners
        val coarse = ArrayList<Pair<Candidate, Float>>()
        for (sx in -COARSE..COARSE step 2) for (sy in -COARSE..COARSE step 2) for (w in WIDTHS) {
            val corners = unit.mapIndexed { i, p ->
                val stretch = if (i == 1 || i == 2) w else 0f
                Point(p.x + sx * px + stretch, p.y + sy * py)
            }
            val c = Candidate(corners, start)
            coarse += c to score(c)
        }
        val best = coarse.sortedByDescending { it.second }.take(STARTS).map { (c, s) ->
            var cur = c to s
            cur = climbCorners(cur, px, py, listOf(2f, 1f, 0.5f), ::score)
            if (fitShape) {
                cur = climbShape(cur, ::score)
                cur = climbCorners(cur, px, py, listOf(1f, 0.5f), ::score)
            }
            cur
        }.maxBy { it.second }.first

        val corners = best.corners.map {
            val p = ink.homography.map(it.x, it.y)
            Point((p.x / frames[0].width).coerceIn(0f, 1f), (p.y / frames[0].height).coerceIn(0f, 1f))
        }
        return best.shape.copy(corners = corners, alignSearch = config.alignSearch)
    }

    private class Candidate(val corners: List<Point>, val shape: SevenSegConfig)

    private fun climbCorners(
        from: Pair<Candidate, Float>,
        px: Float,
        py: Float,
        steps: List<Float>,
        score: (Candidate) -> Float,
    ): Pair<Candidate, Float> {
        var (best, bestScore) = from
        for (step in steps) {
            var improved = true
            while (improved) {
                improved = false
                for (group in CORNER_GROUPS) for (axis in 0 until 2) for (sign in SIGNS) {
                    val corners = best.corners.toMutableList()
                    for (i in group) {
                        val p = corners[i]
                        corners[i] = if (axis == 0) Point(p.x + sign * step * px, p.y) else Point(p.x, p.y + sign * step * py)
                    }
                    val c = Candidate(corners, best.shape)
                    val s = score(c)
                    if (s > bestScore + EPSILON) {
                        best = c
                        bestScore = s
                        improved = true
                    }
                }
            }
        }
        return best to bestScore
    }

    private fun climbShape(from: Pair<Candidate, Float>, score: (Candidate) -> Float): Pair<Candidate, Float> {
        var (best, bestScore) = from
        for (step in listOf(0.02f, 0.01f, 0.005f)) {
            var improved = true
            while (improved) {
                improved = false
                for (param in 0 until 3) for (sign in SIGNS) {
                    val s0 = best.shape
                    val shape = when (param) {
                        0 -> s0.copy(slant = (s0.slant + sign * step).coerceIn(-0.3f, 0.3f))
                        1 -> s0.copy(digitGap = (s0.digitGap + sign * step).coerceIn(0f, 0.5f))
                        else -> s0.copy(segmentThickness = (s0.segmentThickness + sign * step).coerceIn(0.1f, 0.4f))
                    }
                    val c = Candidate(best.corners, shape)
                    val s = score(c)
                    if (s > bestScore + EPSILON) {
                        best = c
                        bestScore = s
                        improved = true
                    }
                }
            }
        }
        return best to bestScore
    }

    /** Coarse search reach in prepared-image pixels (about a tenth of the digit height). */
    private const val COARSE = 10
    private val WIDTHS = listOf(-0.04f, -0.02f, 0f, 0.02f, 0.04f)
    private const val STARTS = 3
    private const val EPSILON = 1e-4f
    private val SIGNS = listOf(-1f, 1f)
    /**
     * Corners moved together: each alone, and each edge's pair. Tilting the row takes both corners of an edge;
     * one corner at a time only skews it, so a level start could get stuck on a tilted row.
     */
    private val CORNER_GROUPS = listOf(listOf(0), listOf(1), listOf(2), listOf(3), listOf(0, 1), listOf(1, 2), listOf(2, 3), listOf(3, 0))
}
