package net.rpb.core

import net.rpb.core.controller.ControllerClient
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Region
import net.rpb.core.ocr.RowCrop
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.ocr.rowRegion
import net.rpb.core.protocol.Ack
import net.rpb.core.protocol.Bind
import net.rpb.core.protocol.Bound
import net.rpb.core.protocol.CalibPhoto
import net.rpb.core.protocol.CalibrationState
import net.rpb.core.protocol.ChunkAssembler
import net.rpb.core.protocol.Chunker
import net.rpb.core.protocol.Codec
import net.rpb.core.protocol.DigitResult
import net.rpb.core.protocol.InMemorySeqStore
import net.rpb.core.protocol.PhotoChunk
import net.rpb.core.protocol.Press
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.SetCalibration
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.StatusRequest
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.Topics
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
    @Test
    fun `commands and events round-trip through CBOR`() {
        val press = Press(seq = 5, ts = 1000, job = "ab12", delayMs = 1000)
        assertEquals(press, Codec.decodeCommand(Codec.encode(press)))
        // The controllers' default differs from the wire default, so it must travel (ADR 0019).
        val waitLonger = Press(seq = 5, ts = 1000, job = "ab12", delayMs = ControllerClient.DEFAULT_DELAY_MS)
        assertEquals(ControllerClient.DEFAULT_DELAY_MS, (Codec.decodeCommand(Codec.encode(waitLonger)) as Press).delayMs)
        assertEquals(StatusRequest(6, 2000, "mn34"), Codec.decodeCommand(Codec.encode(StatusRequest(6, 2000, "mn34"))))

        val text = TextResult("ab12", "123456", true, 0.8f, listOf(DigitResult("1", 0.9f)), pressedAt = 10, capturedAt = 1010)
        assertEquals(text, Codec.decodeEvent(Codec.encode(text)))
        assertEquals(Ack("x"), Codec.decodeEvent(Codec.encode(Ack("x"))))
        val status = Status(1, 80, 31.5f, true, 97, -60)
        assertEquals(status, Codec.decodeEvent(Codec.encode(status)))
        // A status names the request it answers (ADR 0033).
        assertEquals(status.copy(job = "mn34"), Codec.decodeEvent(Codec.encode(status.copy(job = "mn34"))))

        val calibration = Calibration(SevenSegConfig(digits = 6, slant = 0.07f), rotation = 270)
        val set = SetCalibration(7, 3000, "cd34", calibration)
        assertEquals(set, Codec.decodeCommand(Codec.encode(set)))
        val setAndPress = set.copy(pressDelayMs = 3000)
        assertEquals(setAndPress, Codec.decodeCommand(Codec.encode(setAndPress)))
        val photoAndPress = CalibPhoto(8, 4000, "ef56", pressDelayMs = 2500)
        assertEquals(photoAndPress, Codec.decodeCommand(Codec.encode(photoAndPress)))
        // What older controllers send: no field, so no press (ADR 0021).
        assertNull((Codec.decodeCommand(Codec.encode(CalibPhoto(9, 5000, "gh78"))) as CalibPhoto).pressDelayMs)
        assertEquals(CalibrationState("cd34", calibration), Codec.decodeEvent(Codec.encode(CalibrationState("cd34", calibration))))

        // The controller's ephemeral key travels as a byte string; older controllers leave it out (ADR 0030).
        val epk = Random(2).nextBytes(32)
        val withKey = Codec.decodeCommand(Codec.encode(Press(10, 6000, "ij90", 3000, epk)))
        assertIs<Press>(withKey)
        assertContentEquals(epk, withKey.epk)
        assertTrue(Codec.encode(Press(10, 6000, "ij90", 3000, epk)).size <= Codec.encode(Press(10, 6000, "ij90", 3000)).size + 32 + 6)
        assertNull((Codec.decodeCommand(Codec.encode(press)) as Press).epk)
        assertContentEquals(epk, (Codec.decodeCommand(Codec.encode(CalibPhoto(11, 7000, "kl12", epk = epk))) as CalibPhoto).epk)
        assertContentEquals(epk, (Codec.decodeCommand(Codec.encode(set.copy(epk = epk))) as SetCalibration).epk)

        // A bind carries its job key and the signing key it binds, both as byte strings (ADR 0033).
        val pub = SoftwareSigner().publicKey
        val bind = assertIs<Bind>(Codec.decodeCommand(Codec.encode(Bind(12, 8000, "mn34", epk, pub))))
        assertEquals(listOf(12L, 8000L), listOf(bind.seq, bind.ts))
        assertEquals("mn34", bind.job)
        assertContentEquals(epk, bind.epk)
        assertContentEquals(pub, bind.pub)
        assertTrue(Codec.encode(bind).size < pub.size + epk.size + 80, "byte strings, not arrays of ints")
        assertEquals(Bound("mn34", "0123 4567 89ab cdef"), Codec.decodeEvent(Codec.encode(Bound("mn34", "0123 4567 89ab cdef"))))

        val bytes = Random(1).nextBytes(1000)
        val chunk = Codec.decodeEvent(Codec.encode(PhotoChunk("j", 0, 1, bytes)))
        assertIs<PhotoChunk>(chunk)
        assertContentEquals(bytes, chunk.bytes)
        // Byte strings, not arrays of ints: overhead must stay small.
        assertTrue(Codec.encode(PhotoChunk("j", 0, 1, bytes)).size < 1100)
    }

    @Test
    fun `calibration checks keep a controller from saving one that breaks reads`() {
        assertNull(Calibration().problem())
        assertNull(Calibration(rotation = 270).problem())
        val bad = listOf(
            Calibration(rotation = 45),
            Calibration(SevenSegConfig(corners = SevenSegConfig().corners.take(3))),
            Calibration(SevenSegConfig(corners = SevenSegConfig().corners.map { Point(it.x, it.y + 0.5f) })),
            Calibration(SevenSegConfig(corners = List(4) { Point(0.5f, 0.5f) })),
            Calibration(SevenSegConfig(digits = 0)),
            Calibration(SevenSegConfig(digits = 100)),
            Calibration(SevenSegConfig(slant = Float.NaN)),
            Calibration(SevenSegConfig(onThreshold = 1f)),
            Calibration(SevenSegConfig(alignSearch = 2f)),
        )
        for (c in bad) assertTrue(c.problem() != null, "accepted $c")
    }

    @Test
    fun `a row cut from the camera image matches the same part of the upright photo`() {
        val source = GrayImage(37, 23)
        for (i in source.pixels.indices) source.pixels[i] = (i * 7).toByte()
        val config = SevenSegConfig(corners = listOf(Point(0.3f, 0.4f), Point(0.6f, 0.42f), Point(0.6f, 0.55f), Point(0.3f, 0.53f)))
        for (rotation in listOf(0, 90, 180, 270)) {
            val row = RowCrop.cut(source, rotation, config)
            val upright = source.rotated(rotation)
            val r = row.region
            val x0 = Math.round(r.left * upright.width)
            val y0 = Math.round(r.top * upright.height)
            assertEquals(Math.round(r.right * upright.width) - x0, row.gray.width, "rotation $rotation")
            assertEquals(Math.round(r.bottom * upright.height) - y0, row.gray.height, "rotation $rotation")
            assertContentEquals(upright.cropped(x0, y0, row.gray.width, row.gray.height).pixels, row.gray.pixels, "rotation $rotation")
            // The row and its margin are inside the crop.
            val rowRegion = config.rowRegion(upright.width.toFloat() / upright.height)
            assertTrue(r.left <= rowRegion.left && r.top <= rowRegion.top && r.right >= rowRegion.right && r.bottom >= rowRegion.bottom)
        }
        assertEquals(Region(0.125f, 0.25f, 0.375f, 0.5f), Region(0.125f, 0.25f, 0.375f, 0.5f).turned(90).turned(270))
    }

    @Test
    fun `chunks reassemble out of order and with duplicates`() {
        val photo = Random(2).nextBytes(300_000)
        val chunks = Chunker.split(photo, 64 * 1024)
        assertEquals(5, chunks.size)
        val assembler = ChunkAssembler()
        val order = listOf(3, 0, 0, 4, 1, 3)
        for (i in order) assertNull(assembler.add("job", i, chunks.size, chunks[i]))
        assertEquals(4 to 5, assembler.progress("job"))
        val whole = assembler.add("job", 2, chunks.size, chunks[2])
        assertContentEquals(photo, whole)
        assertNull(assembler.progress("job"))
    }

    @Test
    fun `chunk assembler ignores inconsistent input`() {
        val assembler = ChunkAssembler()
        assertNull(assembler.add("job", 5, 2, byteArrayOf(1)))
        assertNull(assembler.add("job", 0, 2, byteArrayOf(1)))
        assertNull(assembler.add("job", 1, 3, byteArrayOf(2))) // n changed mid-job
        assertContentEquals(byteArrayOf(1, 2), assembler.add("job", 1, 2, byteArrayOf(2)))
    }

    @Test
    fun `replay guard rejects old seq, repeats and clock skew`() {
        var now = 1_000_000L
        val guard = ReplayGuard(InMemorySeqStore(), clock = { now })
        assertTrue(guard.accept("c1", Press(10, now, "a")))
        assertFalse(guard.accept("c1", Press(10, now, "a")), "exact replay")
        assertFalse(guard.accept("c1", Press(9, now, "b")), "older seq")
        assertTrue(guard.accept("c2", Press(1, now, "c")), "seqs are per controller")
        assertFalse(guard.accept("c1", Press(11, now - 121_000, "d")), "too old")
        assertFalse(guard.accept("c1", Press(11, now + 121_000, "e")), "too far in future")
        now += 5_000
        assertTrue(guard.accept("c1", Press(11, now, "f")))
    }

    @Test
    fun `seq counter keeps increasing even if the clock goes backwards`() {
        var now = 5_000L
        val counter = SeqCounter(InMemorySeqStore(), "k", clock = { now })
        val a = counter.next()
        now = 1_000L
        val b = counter.next()
        assertTrue(b > a)
    }

    @Test
    fun `topic helpers`() {
        assertEquals("abc", Topics.controllerOfCmd("dev", "rpb/dev/c/abc/cmd"))
        assertNull(Topics.controllerOfCmd("dev", "rpb/other/c/abc/cmd"))
        assertNull(Topics.controllerOfCmd("dev", "rpb/dev/c/abc/evt"))
        assertTrue(Topics.matches("rpb/dev/c/+/cmd", "rpb/dev/c/abc/cmd"))
        assertFalse(Topics.matches("rpb/dev/c/+/cmd", "rpb/dev/c/abc/evt"))
        assertTrue(Topics.matches("rpb/#", "rpb/dev/online"))
        assertFalse(Topics.matches("rpb/dev/online", "rpb/dev/online/x"))
    }
}
