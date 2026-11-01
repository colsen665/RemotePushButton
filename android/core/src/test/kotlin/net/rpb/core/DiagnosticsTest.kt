package net.rpb.core

import kotlinx.serialization.json.Json
import net.rpb.core.device.Diagnostics
import net.rpb.core.ocr.Calibration
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    private val dir = Files.createTempDirectory("diagnostics").toFile().resolve("diagnostics")
    private var now = 1_792_336_210_000L
    private var on = true
    private val diagnostics = Diagnostics(dir, { on }, { now++ })

    @AfterTest
    fun tearDown() {
        dir.parentFile.deleteRecursively()
    }

    @Test
    fun `nothing is written while off`() {
        on = false
        diagnostics.photo(byteArrayOf(1, 2, 3), 90)
        diagnostics.job("j1", "phone", Calibration(), null)
        assertFalse(dir.exists())
    }

    @Test
    fun `photos and jobs are written in order and read back`() {
        val jpeg = byteArrayOf(-1, -40, 7)
        diagnostics.photo(jpeg, 180)
        val calibration = Calibration(rotation = 90)
        diagnostics.job("j1", "phone", calibration, null)
        val files = dir.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("1792336210000-photo-t180.jpg", "1792336210001-job-j1.json"), files.map { it.name })
        assertContentEquals(jpeg, files[0].readBytes())
        val job = Json.decodeFromString(Diagnostics.Job.serializer(), files[1].readText())
        assertEquals(Diagnostics.Job("j1", "phone", calibration, null), job)
    }

    @Test
    fun `only the newest files are kept`() {
        repeat(Diagnostics.MAX_FILES + 5) { diagnostics.photo(byteArrayOf(it.toByte()), 0) }
        val names = dir.listFiles()!!.map { it.name }.sorted()
        assertEquals(Diagnostics.MAX_FILES, names.size)
        assertTrue(names.first().startsWith("${1_792_336_210_005L}-"), names.first())
    }

    @Test
    fun `clear removes everything`() {
        diagnostics.photo(byteArrayOf(1), 0)
        diagnostics.clear()
        assertFalse(dir.exists())
    }
}
