package net.rpb.core

import net.rpb.core.ocr.Point
import net.rpb.core.ocr.Polarity
import net.rpb.core.ocr.SevenSegCalibrator
import net.rpb.core.ocr.SevenSegLocator
import net.rpb.core.ocr.SevenSegReader
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SevenSegReaderTest {
    private fun read(display: SyntheticDisplay, tweak: (net.rpb.core.ocr.SevenSegConfig) -> net.rpb.core.ocr.SevenSegConfig = { it }) =
        display.render().let { (img, config) -> SevenSegReader(tweak(config)).read(img) }

    @Test
    fun `reads every digit on a dark-on-light LCD`() {
        val result = read(SyntheticDisplay("0123456789"))
        assertEquals("0123456789", result.text)
        assertTrue(result.valid, "confidence ${result.confidence}")
    }

    @Test
    fun `reads every digit on a light-on-dark LED`() {
        val result = read(SyntheticDisplay("9876543210", polarity = Polarity.LIGHT_ON_DARK))
        assertEquals("9876543210", result.text)
        assertTrue(result.valid)
    }

    @Test
    fun `reads slanted digits when slant is calibrated`() {
        val result = read(SyntheticDisplay("482916", slant = 0.12f, rotationDeg = 2.0))
        assertEquals("482916", result.text)
        assertTrue(result.valid)
    }

    @Test
    fun `survives noise, blur and a dim low-contrast display`() {
        val display = SyntheticDisplay(
            "305718",
            noiseSigma = 18.0,
            blurPasses = 2,
            segmentColor = 95,
            backgroundColor = 150,
            rotationDeg = -3.0,
        )
        val result = read(display)
        assertEquals("305718", result.text)
        assertTrue(result.valid, "confidence ${result.confidence}")
    }

    @Test
    fun `random codes under random rotation and thickness mismatch`() {
        val rnd = Random(42)
        repeat(40) { i ->
            val code = (1..6).map { '0' + rnd.nextInt(10) }.joinToString("")
            val display = SyntheticDisplay(
                code,
                polarity = if (i % 2 == 0) Polarity.DARK_ON_LIGHT else Polarity.LIGHT_ON_DARK,
                rotationDeg = rnd.nextDouble(-5.0, 5.0),
                noiseSigma = rnd.nextDouble(0.0, 15.0),
                thickness = rnd.nextDouble(0.17, 0.27).toFloat(),
                seed = i,
            )
            val result = read(display)
            assertEquals(code, result.text, "iteration $i")
            assertTrue(result.valid, "iteration $i confidence ${result.confidence}")
        }
    }

    @Test
    fun `tolerates a slightly off calibration`() {
        val result = read(SyntheticDisplay("271828")) { config ->
            config.copy(corners = config.corners.map { Point(it.x + 0.004f, it.y + 0.006f) })
        }
        assertEquals("271828", result.text)
        assertTrue(result.valid)
    }

    @Test
    fun `half-visible segment is flagged instead of silently misread`() {
        // Digit 0 is an '8' whose middle bar (g, index 6) is only half drawn.
        val result = read(SyntheticDisplay("812345", partial = mapOf((0 to 6) to 0.5f)))
        assertFalse(result.valid, "got ${result.text} conf ${result.confidence}")
        assertTrue(result.digits[0].confidence < 0.35f)
    }

    @Test
    fun `blank display is invalid`() {
        val result = read(SyntheticDisplay("888888", unlitDigits = (0..5).toSet()))
        assertFalse(result.valid)
        assertEquals("??????", result.text)
    }

    @Test
    fun `wrong digit count in calibration is invalid`() {
        val result = read(SyntheticDisplay("123456")) { it.copy(digits = 5) }
        assertFalse(result.valid)
    }

    @Test
    fun `multiplexed LED frames are combined`() {
        val base = SyntheticDisplay("640213", polarity = Polarity.LIGHT_ON_DARK)
        val (frameA, config) = base.copy(unlitDigits = setOf(0, 2, 4)).render()
        val (frameB, _) = base.copy(unlitDigits = setOf(1, 3, 5), seed = 2).render()
        val single = SevenSegReader(config.copy(ledMultiplex = true)).read(frameA)
        assertFalse(single.valid)
        val combined = SevenSegReader(config.copy(ledMultiplex = true)).readFrames(listOf(frameA, frameB))
        assertEquals("640213", combined.text)
        assertTrue(combined.valid)
    }

    @Test
    fun `LCD mode picks the best of several frames`() {
        val (bad, config) = SyntheticDisplay("555111", partial = mapOf((0 to 0) to 0.5f)).render()
        val (good, _) = SyntheticDisplay("555111").render()
        val result = SevenSegReader(config).readFrames(listOf(bad, good))
        assertEquals("555111", result.text)
        assertTrue(result.valid)
    }

    @Test
    fun `alignment search follows a display that moved and reports the shift`() {
        val (img, config) = SyntheticDisplay("314159").render()
        // The calibration sits 4 px right of the digits (digit height 120 px).
        val moved = config.copy(corners = config.corners.map { Point(it.x + 4f / img.width, it.y) })
        val result = SevenSegReader(moved).read(img)
        assertEquals("314159", result.text)
        assertTrue(result.valid)
        assertTrue(result.shift.x in -0.05f..-0.02f, "shift ${result.shift}")
    }

    @Test
    fun `refine snaps a rough calibration onto the digits`() {
        val (img, config) = SyntheticDisplay("707070", rotationDeg = 2.0).render()
        val rough = config.copy(
            corners = config.corners.mapIndexed { i, p -> Point(p.x + (if (i % 2 == 0) 6f else -5f) / img.width, p.y + (if (i < 2) 5f else -4f) / img.height) },
            alignSearch = 0f,
        )
        val fitted = SevenSegCalibrator.refine(listOf(img), rough, fitShape = false, expected = listOf("707070"))
        val result = SevenSegReader(fitted).read(img)
        assertEquals("707070", result.text)
        assertTrue(result.confidence >= 0.9f, "confidence ${result.confidence}")
        // Thick, sharp synthetic segments read perfectly over a few pixels, so the corners land within that.
        fun dist(a: Point, b: Point) = hypot((a.x - b.x) * img.width, (a.y - b.y) * img.height)
        for (i in 0 until 4) {
            val before = dist(rough.corners[i], config.corners[i])
            val after = dist(fitted.corners[i], config.corners[i])
            assertTrue(after < before && after < 4f, "corner $i: $before px before, $after px after")
        }
    }

    @Test
    fun `overlay outlines map into the image`() {
        val (img, config) = SyntheticDisplay("123456", rotationDeg = 4.0).render()
        val overlay = SevenSegReader(config).overlay(img.width, img.height)
        assertEquals(6 * 7, overlay.size)
        assertTrue(overlay.all { seg -> seg.points.all { it.x in 0f..img.width.toFloat() && it.y in 0f..img.height.toFloat() } })
    }

    @Test
    fun `locator finds an LED display and its polarity`() {
        val (img, config) = SyntheticDisplay("730295", polarity = Polarity.LIGHT_ON_DARK, rotationDeg = 3.0, noiseSigma = 8.0).render()
        val located = assertNotNull(SevenSegLocator.locate(img, "730295"))
        assertEquals(Polarity.LIGHT_ON_DARK, located.config.polarity)
        assertEquals("730295", located.result.text)
        assertFalse(located.upsideDownToo)
        located.config.corners.zip(config.corners).forEach { (a, b) ->
            assertTrue(hypot((a.x - b.x) * img.width, (a.y - b.y) * img.height) < 8f, "corner $a, expected $b")
        }
    }

    @Test
    fun `locator flags a code that reads the same upside down`() {
        val (img, _) = SyntheticDisplay("2552").render()
        assertTrue(assertNotNull(SevenSegLocator.locate(img, "2552")).upsideDownToo)
    }
}
