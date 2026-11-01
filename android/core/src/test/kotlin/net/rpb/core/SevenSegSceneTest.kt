package net.rpb.core

import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.RowCrop
import net.rpb.core.ocr.SegmentTemplate
import net.rpb.core.ocr.SevenSegCalibrator
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.ocr.SevenSegLocator
import net.rpb.core.ocr.SevenSegReader
import net.rpb.core.ocr.inRegion
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reader, Refine and the locator on [Scenes]: invented codes on drawn displays, in the form `CameraCapture` hands
 * to the reader. Real photos and codes stay out of the repo (ADR 0027).
 */
class SevenSegSceneTest {
    private val handHeld = Scenes.handHeld
    private val rig = Scenes.rig
    private val secondRig = Scenes.secondRig
    private val rig3 = Scenes.rig3
    private val photos = handHeld + rig + secondRig + rig3

    private fun config(photo: SyntheticPhoto, alignSearch: Float = 0.06f, offset: (Int) -> Float = { 0f }) =
        photo.config(alignSearch, offset)

    @Test
    fun `reads every calibrated scene`() {
        for (photo in photos) {
            val img = photo.image
            val result = SevenSegReader(config(photo)).read(img)
            assertEquals(photo.code, result.text, photo.name)
            assertTrue(result.valid, "${photo.name} confidence ${result.confidence}")
        }
    }

    @Test
    fun `hand-held scenes read without the alignment search too`() {
        for (photo in handHeld) {
            val img = photo.image
            val result = SevenSegReader(config(photo, alignSearch = 0f)).read(img)
            assertEquals(photo.code, result.text, photo.name)
            assertTrue(result.valid, "${photo.name} confidence ${result.confidence}")
        }
    }

    @Test
    fun `alignment search reads a row shifted by up to 3 px`() {
        val shifts = listOf(1, 2, 3).flatMap { d -> listOf(d to 0, -d to 0, 0 to d, 0 to -d) }
        val failed = ArrayList<String>()
        for (photo in photos) {
            val img = photo.image
            for ((dx, dy) in shifts) {
                val result = SevenSegReader(config(photo) { if (it % 2 == 0) dx.toFloat() else dy.toFloat() }).read(img)
                if (result.valid) assertEquals(photo.code, result.text, "${photo.name} shifted $dx,$dy") else failed += "${photo.name} $dx,$dy"
            }
        }
        assertEquals(emptyList(), failed, "not valid after the search")
    }

    @Test
    fun `a misaligned calibration is rejected, never misread`() {
        for (photo in photos) {
            val img = photo.image
            val rnd = Random(photo.name.hashCode())
            repeat(10) {
                val jitter = FloatArray(8) { rnd.nextDouble(-6.0, 6.0).toFloat() }
                for (search in listOf(0f, 0.06f)) {
                    val result = SevenSegReader(config(photo, search) { jitter[it] }).read(img)
                    if (result.valid) assertEquals(photo.code, result.text, "${photo.name} search $search jitter ${jitter.toList()}")
                }
            }
        }
    }

    @Test
    fun `refine recovers a rough calibration when told the code`() {
        var correct = 0
        for (photo in handHeld) {
            val img = photo.image
            val rnd = Random(photo.name.hashCode())
            val jitter = FloatArray(8) { rnd.nextDouble(-6.0, 6.0).toFloat() }
            val rough = config(photo) { jitter[it] }.copy(slant = 0f)
            val result = SevenSegReader(SevenSegCalibrator.refine(listOf(img), rough, expected = listOf(photo.code))).read(img)
            if (result.valid) assertEquals(photo.code, result.text, photo.name)
            if (result.valid) correct++
        }
        assertTrue(correct >= 11, "only $correct of ${handHeld.size} recovered")
    }

    @Test
    fun `one refined calibration reads every rig scene`() {
        val images = rig.map { it.image }
        // Corners as if marked by eye; the default shape doesn't match this view of the display.
        val byEye = floatArrayOf(-10.7f, -2.1f, -1.5f, 1.3f, 2.5f, -4.7f, -6.5f, 4.4f)
        val truth = rig[0].corners
        val rough = SevenSegConfig(
            corners = (0 until 4).map { Point((truth[2 * it] + byEye[2 * it]) / rig[0].width, (truth[2 * it + 1] + byEye[2 * it + 1]) / rig[0].height) },
        )
        val fitted = SevenSegCalibrator.refine(images, rough, expected = rig.map { it.code })
        for ((photo, img) in rig.zip(images)) {
            val result = SevenSegReader(fitted).read(img)
            assertEquals(photo.code, result.text, photo.name)
            assertTrue(result.valid, "${photo.name} confidence ${result.confidence}")
        }
    }

    /** Largest distance in pixels between a located corner and the calibrated one, in the upright scene. */
    private fun cornerError(img: GrayImage, photo: SyntheticPhoto, located: SevenSegLocator.Located) =
        located.config.corners.withIndex().maxOf { (i, p) -> hypot(p.x * img.width - photo.corners[2 * i], p.y * img.height - photo.corners[2 * i + 1]) }

    @Test
    fun `locator finds the digit row in every scene from the code alone`() {
        for (photo in photos) {
            val img = photo.image
            val located = assertNotNull(SevenSegLocator.locate(img, photo.code), photo.name)
            assertEquals(0, located.turn, photo.name)
            assertEquals(photo.code, located.result.text, photo.name)
            assertTrue(located.result.valid, photo.name)
            val error = cornerError(img, photo, located)
            assertTrue(error < 10f, "${photo.name} corners off by $error px")
        }
    }

    @Test
    fun `locator finds the turn that makes the digits upright`() {
        for (photo in listOf(handHeld[0], rig[0], secondRig)) {
            val img = photo.image
            for (turn in listOf(90, 180, 270)) {
                val located = assertNotNull(SevenSegLocator.locate(img.rotated(turn), photo.code), "${photo.name} turned $turn")
                assertEquals((360 - turn) % 360, located.turn, "${photo.name} turned $turn")
                assertTrue(cornerError(img, photo, located) < 10f, "${photo.name} turned $turn")
            }
        }
    }

    @Test
    fun `locator finds nothing for a code the display doesn't show`() {
        val shown = handHeld[0]
        assertNull(SevenSegLocator.locate(shown.image, shown.code.dropLast(1) + "3"))
        // The upside-down reading of the second rig scene: a 1 on the left bars is a turned 1, not a digit.
        assertNull(SevenSegLocator.locate(secondRig.image, Scenes.SECOND_RIG_UPSIDE_DOWN))
    }

    @Test
    fun `codes without a 1, 3, 4 or 7 read the same upside down`() {
        assertFalse(assertNotNull(SevenSegLocator.locate(secondRig.image, secondRig.code)).upsideDownToo)
        assertEquals(listOf('0', null, '2', null, null, '5', '9', null, '8', '6'), ('0'..'9').map(SegmentTemplate::upsideDown))
    }

    @Test
    fun `reads the row cut from the full-size image in every sensor orientation`() {
        for (photo in listOf(rig[0], secondRig, handHeld[3])) {
            val img = photo.image
            val config = config(photo)
            // 2.5 times the scene: the digits about 250 px tall, as the camera delivers them before scaling.
            val full = img.enlarged(2.5f)
            for (rotation in listOf(0, 90, 180, 270)) {
                // As the sensor sees it: turning it by the rotation makes it upright.
                val sensor = full.rotated(360 - rotation)
                val row = RowCrop.cut(sensor, rotation, config)
                assertTrue(row.gray.width * row.gray.height < full.width * full.height / 10, "${photo.name}: crop too big")
                val result = SevenSegReader(config.inRegion(row.region)).read(row.gray)
                assertEquals(photo.code, result.text, "${photo.name} rotation $rotation")
                assertTrue(result.valid, "${photo.name} rotation $rotation confidence ${result.confidence}")
            }
        }
    }
}
