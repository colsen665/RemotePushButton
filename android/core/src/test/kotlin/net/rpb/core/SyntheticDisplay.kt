package net.rpb.core

import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.Polarity
import net.rpb.core.ocr.SegmentTemplate
import net.rpb.core.ocr.SevenSegConfig
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import kotlin.random.Random

/**
 * Renders a photo-like image of a 7-segment display (rotation, slant, noise, blur, dim contrast)
 * together with the calibration a user would set for it.
 */
data class SyntheticDisplay(
    val text: String,
    val polarity: Polarity = Polarity.DARK_ON_LIGHT,
    val slant: Float = 0f,
    val rotationDeg: Double = 0.0,
    val noiseSigma: Double = 0.0,
    val blurPasses: Int = 0,
    val segmentColor: Int? = null,
    val backgroundColor: Int? = null,
    val thickness: Float = 0.22f,
    val gap: Float = 0.2f,
    /** Segments to draw only partly: (digit index, segment index) → fraction of the segment length drawn. */
    val partial: Map<Pair<Int, Int>, Float> = emptyMap(),
    /** Digits not drawn at all (e.g. a multiplexed LED frame). */
    val unlitDigits: Set<Int> = emptySet(),
    val seed: Int = 1,
    val width: Int = 800,
    val height: Int = 600,
) {
    fun render(): Pair<GrayImage, SevenSegConfig> {
        val dark = polarity == Polarity.DARK_ON_LIGHT
        val bg = backgroundColor ?: if (dark) 200 else 25
        val fg = segmentColor ?: if (dark) 45 else 235
        val img = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(110, 110, 110)
        g.fillRect(0, 0, width, height) // device housing around the display

        val transform = AffineTransform.getRotateInstance(Math.toRadians(rotationDeg), width / 2.0, height / 2.0)
        g.transform = transform

        // Display window with a margin around the digit row.
        val digitH = 120.0
        val n = text.length
        val digitW = digitH * 0.55
        val cellW = digitW / (1 - gap)
        val rowW = cellW * n
        val x0 = (width - rowW) / 2
        val y0 = (height - digitH) / 2
        g.color = Color(bg, bg, bg)
        g.fill(java.awt.geom.Rectangle2D.Double(x0 - 30, y0 - 25, rowW + 60, digitH + 50))

        g.color = Color(fg, fg, fg)
        val rects = SegmentTemplate.rects(thickness, (digitW / digitH).toFloat())
        text.forEachIndexed { d, ch ->
            if (d in unlitDigits) return@forEachIndexed
            val pattern = SegmentTemplate.patternFor(ch)
            val left = x0 + d * cellW + (cellW - digitW) / 2
            rects.forEachIndexed { s, r ->
                if (pattern and (1 shl s) == 0) return@forEachIndexed
                val frac = partial[d to s] ?: 1f
                // Draw a fraction of the segment along its long axis.
                val horizontal = (r.u1 - r.u0) * digitW > (r.v1 - r.v0) * digitH
                val u1 = if (horizontal) r.u0 + (r.u1 - r.u0) * frac else r.u1
                val v1 = if (horizontal) r.v1 else r.v0 + (r.v1 - r.v0) * frac
                fun pt(u: Float, v: Float) = Point2D.Double(left + u * digitW + slant * (0.5 - v) * digitH, y0 + v * digitH)
                val p = Path2D.Double()
                val a = pt(r.u0, r.v0); val b = pt(u1, r.v0); val c = pt(u1, v1); val e = pt(r.u0, v1)
                p.moveTo(a.x, a.y); p.lineTo(b.x, b.y); p.lineTo(c.x, c.y); p.lineTo(e.x, e.y); p.closePath()
                g.fill(p)
            }
        }
        g.dispose()

        var gray = GrayImage(width, height)
        val raster = img.raster
        val rnd = Random(seed)
        for (y in 0 until height) for (x in 0 until width) {
            val noise = if (noiseSigma > 0) gaussian(rnd) * noiseSigma else 0.0
            gray[x, y] = (raster.getSample(x, y, 0) + noise).toInt()
        }
        repeat(blurPasses) { gray = gray.boxBlur3() }

        // Calibration: the digit row's corners (tight top/bottom, slightly loose left/right).
        val corners = listOf(
            Point2D.Double(x0, y0), Point2D.Double(x0 + rowW, y0),
            Point2D.Double(x0 + rowW, y0 + digitH), Point2D.Double(x0, y0 + digitH),
        ).map { transform.transform(it, null) }.map { Point((it.x / width).toFloat(), (it.y / height).toFloat()) }

        val config = SevenSegConfig(
            corners = corners,
            digits = n,
            slant = slant,
            polarity = polarity,
            digitGap = gap,
            segmentThickness = 0.22f,
        )
        return gray to config
    }

    private fun gaussian(rnd: Random): Double {
        // Box–Muller
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * kotlin.math.cos(2 * Math.PI * u2)
    }
}

/** Scaled up with bilinear sampling, standing in for the camera's full-size image. */
fun GrayImage.enlarged(factor: Float): GrayImage {
    val out = GrayImage((width * factor).toInt(), (height * factor).toInt())
    for (y in 0 until out.height) for (x in 0 until out.width) out[x, y] = sample((x + 0.5f) / factor - 0.5f, (y + 0.5f) / factor - 0.5f).toInt()
    return out
}
