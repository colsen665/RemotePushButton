package net.rpb.core.device

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import java.io.File

/**
 * Files kept on the device while the user has diagnostics on (ADR 0023), so a failed read can be reproduced in
 * `core`: every camera photo at full size, as the camera made it, and per job the calibration and anchor. Names
 * start with the time in milliseconds, so they sort in order; only the newest [MAX_FILES] are kept. Writing never
 * fails a job.
 */
class Diagnostics(
    private val dir: File,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Job(val job: String, val controller: String, val calibration: Calibration, val anchor: DisplayAnchor?)

    /** [jpeg] turned [rotation] degrees clockwise is upright. */
    fun photo(jpeg: ByteArray, rotation: Int) = write("photo-t$rotation.jpg") { it.writeBytes(jpeg) }

    /** What the device held after [job]: the calibration it read with, and the anchor, if the job made one. */
    fun job(job: String, controller: String, calibration: Calibration, anchor: DisplayAnchor?) =
        write("job-$job.json") { it.writeText(json.encodeToString(Job.serializer(), Job(job, controller, calibration, anchor))) }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun write(suffix: String, body: (File) -> Unit) {
        if (!enabled()) return
        runCatching {
            dir.mkdirs()
            body(File(dir, "${clock()}-$suffix"))
            dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach(File::delete)
        }
    }

    companion object {
        /** About ten jobs of three full-size photos each, some 100 MB on a 16 MP camera. */
        const val MAX_FILES = 40
        private val json = Json { encodeDefaults = true }
    }
}
