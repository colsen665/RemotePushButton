package net.rpb.core

import kotlinx.serialization.json.Json
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.ocr.SevenSegReader
import net.rpb.core.ocr.moved
import java.util.Base64
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Following the display from the anchor made at calibration (ADR 0018), on [Scenes] of the rig. */
class DisplayAnchorTest {
    private val scene = Scenes.rig[0]
    private val photo = scene.image
    private val calibration = Calibration(rigConfig())
    private val anchor = assertNotNull(DisplayAnchor.make(photo, calibration))
    private val centre = calibration.config.corners.let { c ->
        Point(c.sumOf { it.x.toDouble() }.toFloat() / 4 * photo.width, c.sumOf { it.y.toDouble() }.toFloat() / 4 * photo.height)
    }

    /** Largest distance in pixels between the corners of two calibrations of a [photo]-sized image. */
    private fun cornerError(a: SevenSegConfig, b: SevenSegConfig) =
        a.corners.zip(b.corners).maxOf { (p, q) -> hypot((p.x - q.x) * photo.width, (p.y - q.y) * photo.height) }

    @Test
    fun `follows a display that moved, turned and changed size`() {
        val moves = listOf(
            turnAbout(centre, 0f, 1f, 40f, -25f),
            turnAbout(centre, 2f, 1.03f, -30f, 20f),
            turnAbout(centre, -3f, 0.95f, 120f, 90f),
            // About 1.9 digit heights, near the end of the search.
            turnAbout(centre, 0f, 1f, 150f, 120f),
            turnAbout(centre, -5f, 1.08f, 60f, 0f),
        )
        for (truth in moves) {
            val img = photo.moved(truth)
            val move = assertNotNull(anchor.follow(img), "$truth")
            val followed = calibration.config.moved(move, photo.width, photo.height)
            val error = cornerError(followed, calibration.config.moved(truth, photo.width, photo.height))
            assertTrue(error < 2f, "$truth: corners off by $error px")
            val read = SevenSegReader(followed).read(img)
            assertEquals(scene.code, read.text, "$truth")
            assertTrue(read.valid, "$truth")
            assertFalse(SevenSegReader(calibration.config).read(img).valid, "$truth: read without following")
        }
    }

    @Test
    fun `new digits on a display that stayed put don't move it`() {
        for (other in Scenes.rig.drop(1)) {
            val name = other.name
            val move = assertNotNull(anchor.follow(other.image), name)
            val error = cornerError(calibration.config.moved(move, photo.width, photo.height), calibration.config)
            assertTrue(error < 1f, "$name: moved by $error px")
        }
    }

    @Test
    fun `follows the display to the second rig position`() {
        val img = Scenes.secondRig.image
        val move = assertNotNull(anchor.follow(img))
        val read = SevenSegReader(calibration.config.moved(move, img.width, img.height)).read(img)
        assertEquals(Scenes.secondRig.code, read.text)
        assertTrue(read.valid)
    }

    @Test
    fun `finds nothing where the display isn't`() {
        assertNull(anchor.follow(GrayImage(photo.width, photo.height)))
        assertNull(anchor.follow(photo.rotated(180)))
        assertNull(anchor.follow(photo.cropped(0, 0, photo.width, photo.height - 100)))
        // The same kind of display, hand-held: elsewhere, larger and on another background.
        assertNull(anchor.follow(Scenes.handHeld[0].copy(centreX = 480f, centreY = 1180f, digitH = 130f).image))
        assertNull(DisplayAnchor.make(GrayImage(photo.width, photo.height), calibration))
    }

    @Test
    fun `a display that moved too far is not followed`() {
        assertNull(anchor.follow(photo.moved(turnAbout(centre, 0f, 1f, 300f, 0f))), "3 digit heights")
        assertNull(anchor.follow(photo.moved(turnAbout(centre, 8f, 1f, 0f, 0f))), "8°")
        assertNull(anchor.follow(photo.moved(turnAbout(centre, 0f, 1.2f, 0f, 0f))), "20% larger")
    }

    @Test
    fun `the anchor holds nothing of the digits`() {
        val corners = calibration.config.corners.map { Point(it.x * photo.width, it.y * photo.height) }
        fun inside(p: Point) = (0 until 4).all { i ->
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x) >= 0
        }
        val pixels = Base64.getDecoder().decode(anchor.pixels)
        var checked = 0
        for (y in 0 until anchor.height) for (x in 0 until anchor.width) {
            if (inside(Point(anchor.left + (x + 0.5f) * anchor.cell, anchor.top + (y + 0.5f) * anchor.cell))) {
                assertEquals(0, pixels[y * anchor.width + x].toInt(), "pixel $x, $y")
                checked++
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun `the anchor survives the settings store`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val restored = json.decodeFromString(DisplayAnchor.serializer(), json.encodeToString(DisplayAnchor.serializer(), anchor))
        assertEquals(anchor, restored)
        assertEquals(calibration, restored.calibration)
        val move = assertNotNull(restored.follow(photo))
        assertTrue(cornerError(calibration.config.moved(move, photo.width, photo.height), calibration.config) < 1f)
    }
}
