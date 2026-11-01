package net.rpb.core

import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.SegmentTemplate
import net.rpb.core.ocr.SevenSegConfig
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A drawn scene standing in for a camera photo: a generic device body with an LCD window and marks around it, on a
 * textured ground, with thin blurred segments, tilt, uneven light, glare and sensor noise. Its calibration is known
 * exactly. The codes and the look are invented; real photos and codes never enter the repo (ADR 0027).
 */
data class SyntheticPhoto(
    val name: String,
    val code: String,
    val centreX: Float,
    val centreY: Float,
    val tiltDeg: Double = 0.0,
    val digitH: Float = 105f,
    val cellAspect: Float = 0.55f,
    val slant: Float = 0.03f,
    val gap: Float = 0.16f,
    val thickness: Float = 0.2f,
    val width: Int = 1600,
    val height: Int = 1600,
    val lcd: Int = 165,
    val ink: Int = 45,
    val body: Int = 45,
    val ground: Int = 70,
    /** Share of the contrast left at the right end of the row; 1 is even. */
    val fadeTo: Float = 1f,
    /** A bright spot at this fraction along the row, and its strength. */
    val glareAt: Float = 0f,
    val glare: Float = 0f,
    val noise: Double = 3.0,
    val blurPasses: Int = 6,
    val seed: Int = 1,
) {
    private val cellW get() = digitH * cellAspect
    private val rowW get() = cellW * code.length
    private val transform get() = AffineTransform.getRotateInstance(Math.toRadians(tiltDeg), centreX.toDouble(), centreY.toDouble())

    /** The digit row in pixels: TL, TR, BR, BL as x, y pairs. */
    val corners: FloatArray
        get() {
            val l = centreX - rowW / 2.0
            val r = centreX + rowW / 2.0
            val t = centreY - digitH / 2.0
            val b = centreY + digitH / 2.0
            return listOf(l to t, r to t, r to b, l to b).flatMap { (x, y) ->
                val p = transform.transform(Point2D.Double(x, y), null)
                listOf(p.x.toFloat(), p.y.toFloat())
            }.toFloatArray()
        }

    fun config(alignSearch: Float = 0.06f, offset: (Int) -> Float = { 0f }) = SevenSegConfig(
        corners = (0 until 4).map { Point((corners[2 * it] + offset(2 * it)) / width, (corners[2 * it + 1] + offset(2 * it + 1)) / height) },
        slant = slant,
        digitGap = gap,
        segmentThickness = thickness,
        alignSearch = alignSearch,
    )

    val image: GrayImage by lazy { render() }

    private fun render(): GrayImage {
        val buf = BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY)
        val g = buf.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        fun grey(v: Int) = v.coerceIn(0, 255).let { Color(it, it, it) }
        g.color = grey(ground)
        g.fillRect(0, 0, width, height)
        val speckle = Random(seed)
        repeat(width * height / 400) {
            g.color = grey(ground + speckle.nextInt(-22, 23))
            g.fill(Ellipse2D.Double(speckle.nextDouble(width.toDouble()), speckle.nextDouble(height.toDouble()), 5.0, 5.0))
        }

        g.transform = transform
        val h = digitH.toDouble()
        val left = centreX - rowW / 2.0
        val top = centreY - h / 2.0
        fun box(x: Double, y: Double, w: Double, ht: Double, shade: Int) {
            g.color = grey(shade)
            g.fill(Rectangle2D.Double(left + x * h, top + y * h, w * h, ht * h))
        }
        val rowInH = rowW / h
        g.color = grey(body)
        g.fill(RoundRectangle2D.Double(left - 3.4 * h, top - 2.2 * h, rowW + 6.8 * h, 5.4 * h, 1.5 * h, 1.5 * h))
        // Marks that don't change between presses and aren't symmetric: the window's frame, a maker's block, screws.
        box(-0.42, -0.5, rowInH + 0.84, 2.0, body + 55)
        box(-0.3, -0.38, rowInH + 0.6, 1.76, lcd)
        box(-2.6, -1.0, 1.4, 0.34, body + 110)
        box(-2.6, -0.45, 0.6, 1.2, body + 80)
        box(-1.75, -0.2, 0.55, 0.3, body + 120)
        box(-2.6, 1.05, 1.5, 0.22, body + 95)
        box(0.1, -1.35, 1.7, 0.25, body + 70)
        box(rowInH - 1.3, 1.75, 1.2, 0.25, body + 90)
        for ((sx, sy) in listOf(rowInH + 1.3 to -1.2, rowInH + 2.2 to 0.5, rowInH + 1.0 to 1.9)) {
            g.color = grey(body + 100)
            g.fill(Ellipse2D.Double(left + sx * h, top + sy * h, 0.3 * h, 0.3 * h))
        }

        g.color = grey(ink)
        val digitW = cellW * (1 - gap)
        val rects = SegmentTemplate.rects(thickness, (digitW / h).toFloat())
        code.forEachIndexed { d, ch ->
            val pattern = SegmentTemplate.patternFor(ch)
            val x0 = left + d * cellW + (cellW - digitW) / 2
            rects.forEachIndexed { s, r ->
                if (pattern and (1 shl s) == 0) return@forEachIndexed
                fun pt(u: Float, v: Float) = Point2D.Double(x0 + u * digitW + slant * (0.5 - v) * h, top + v * h)
                val a = pt(r.u0, r.v0)
                val b = pt(r.u1, r.v0)
                val c = pt(r.u1, r.v1)
                val e = pt(r.u0, r.v1)
                g.fill(Path2D.Double().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(e.x, e.y); closePath() })
            }
        }
        g.dispose()

        var out = GrayImage(width, height)
        val raster = buf.raster
        for (y in 0 until height) for (x in 0 until width) out[x, y] = raster.getSample(x, y, 0)
        repeat(blurPasses) { out = out.boxBlur3() }
        return light(out)
    }

    /** Fade, glare and noise, in the row's own coordinates so that they follow its tilt. */
    private fun light(img: GrayImage): GrayImage {
        val rnd = Random(seed * 31 + 7)
        val cosT = cos(Math.toRadians(tiltDeg))
        val sinT = sin(Math.toRadians(tiltDeg))
        val glareX = (glareAt - 0.5f) * rowW
        val radius = digitH * 0.45
        for (y in 0 until height) for (x in 0 until width) {
            val dx = x - centreX
            val dy = y - centreY
            val along = dx * cosT + dy * sinT
            val across = -dx * sinT + dy * cosT
            var v = img[x, y].toDouble()
            if (fadeTo < 1f && kotlin.math.abs(across) < digitH * 0.9) {
                val t = (along / rowW + 0.5).coerceIn(0.0, 1.0)
                v = lcd + (v - lcd) * (1 - (1 - fadeTo) * t)
            }
            if (glare > 0f) {
                val d2 = (along - glareX) * (along - glareX) + across * across
                v += glare * exp(-d2 / (2 * radius * radius))
            }
            if (noise > 0) {
                val u = rnd.nextDouble().coerceAtLeast(1e-12)
                v += sqrt(-2 * ln(u)) * cos(2 * Math.PI * rnd.nextDouble()) * noise
            }
            img[x, y] = v.toInt()
        }
        return img
    }
}

/**
 * Stand-ins for the views the reader has to cope with. The codes between them use every digit. Renders are cached, as
 * the images are used read-only.
 */
object Scenes {
    private val first = listOf("160382", "729105", "483620", "057291")
    private val offsets = listOf(Triple(0f, 0f, 0.6), Triple(14f, 18f, -0.8), Triple(-20f, -9f, 1.4))

    val handHeld: List<SyntheticPhoto> = first.flatMapIndexed { c, code ->
        offsets.mapIndexed { f, (dx, dy, tilt) ->
            SyntheticPhoto("hand-$code-$f", code, 760f + dx + 30f * c, 820f + dy - 25f * c, tiltDeg = tilt, seed = 10 * c + f + 1)
        }
    }

    private val rigShape = SyntheticPhoto(
        "", "", 933f, 875f, tiltDeg = 5.1, digitH = 98f, slant = 0.07f, gap = 0.24f, thickness = 0.215f,
        lcd = 205, ink = 35, body = 28, ground = 75, glareAt = 0.42f, glare = 30f, blurPasses = 3,
    )

    val rig: List<SyntheticPhoto> = listOf("502846", "791364", "681205").mapIndexed { i, code ->
        rigShape.copy(name = "rig-$code", code = code, seed = 101 + i)
    }

    /** About 2 digit heights from the first position, turned 2°, with its own glare spot. */
    val secondRig = rigShape.copy(
        name = "rig2-916052", code = "916052", centreX = 790f, centreY = 790f, tiltDeg = 2.9, digitH = 100f, glareAt = 0.7f, glare = 35f, blurPasses = 3, seed = 201,
    )
    const val SECOND_RIG_UPSIDE_DOWN = "250916"

    /** Lit unevenly by the torch: the digits fade towards the right end, keeping the true row's score low. */
    val rig3: List<SyntheticPhoto> = listOf("283905" to 301, "716482" to 302, "716482" to 303).map { (code, seed) ->
        SyntheticPhoto(
            "rig3-$code-$seed", code, 740f, 420f, tiltDeg = 1.9, digitH = 82f, slant = 0.16f, gap = 0.24f, thickness = 0.18f,
            width = 1600, height = 1200, lcd = 215, ink = 60, body = 25, ground = 35, fadeTo = 0.45f, seed = seed,
        )
    }
}
