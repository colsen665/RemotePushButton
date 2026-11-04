package net.rpb.core.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.rpb.core.controller.ControllerClient
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.crypto.toHex
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.RowCrop
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.ocr.SevenSegReader
import net.rpb.core.ocr.SevenSegResult
import net.rpb.core.ocr.inRegion
import net.rpb.core.ocr.moved
import net.rpb.core.protocol.Ack
import net.rpb.core.protocol.Busy
import net.rpb.core.protocol.CalibPhoto
import net.rpb.core.protocol.CalibrationState
import net.rpb.core.protocol.Chunker
import net.rpb.core.protocol.Codec
import net.rpb.core.protocol.Command
import net.rpb.core.protocol.DigitResult
import net.rpb.core.protocol.ErrorCodes
import net.rpb.core.protocol.ErrorEvent
import net.rpb.core.protocol.Event
import net.rpb.core.protocol.JobCommand
import net.rpb.core.protocol.PhotoChunk
import net.rpb.core.protocol.Press
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.protocol.SetCalibration
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.StatusRequest
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.Relay
import net.rpb.core.relay.Topics

/** Presses the physical button (SwitchBot Bot on Android, a fake in tests). Throws on failure. */
fun interface Presser {
    suspend fun press()
}

/**
 * A captured photo: the JPEG to send and a greyscale copy (same geometry) for calibration and for following the
 * display. [fullSize], if the camera has it, cuts the digit row from the full-size image, which the reader prefers
 * (ADR 0017).
 */
class Frame(val jpeg: ByteArray, val gray: GrayImage, val fullSize: RowCutter? = null)

/** Cuts the row a config calibrates from the camera's full-size image, turned upright; null if it can't. */
fun interface RowCutter {
    suspend fun cut(config: SevenSegConfig): RowCrop?
}

fun interface FrameSource {
    /** Called when a job starts, before the press, so a camera that is closed between jobs can open and settle meanwhile. */
    fun prepare() {}

    suspend fun capture(): Frame
}

/**
 * The device's saved calibration, which the agent reads for every job and replaces on `set_calib` (ADR 0016), and
 * the anchor it follows the display with (ADR 0018).
 */
interface CalibrationStore {
    fun load(): Calibration
    fun save(calibration: Calibration)
    fun anchor(): DisplayAnchor?
    fun saveAnchor(anchor: DisplayAnchor)
}

fun interface StatusSource {
    fun status(): Status
}

class PairedController(val id: String, val name: String, val key: ByteArray)

fun interface ControllerRegistry {
    fun find(controllerId: String): PairedController?
}

/** Outcome of one press/photo job, reported to the device UI. */
data class JobRecord(
    val job: String,
    val controller: String,
    val pressed: Boolean,
    val result: SevenSegResult?,
    val jpeg: ByteArray?,
    val error: String?,
)

/**
 * The device side of the protocol: receives encrypted commands from paired controllers,
 * presses, waits, photographs, reads the digits and replies with the text first and the photo after.
 */
class DeviceAgent(
    private val deviceId: ByteArray,
    private val relay: Relay,
    private val registry: ControllerRegistry,
    private val replay: ReplayGuard,
    private val presser: Presser,
    private val camera: FrameSource,
    private val calibration: CalibrationStore,
    private val statusSource: StatusSource,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val onJob: (JobRecord) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deviceHex = deviceId.toHex()
    private val busy = Mutex()

    /** The wait after the press that controllers last asked for; calibration on the device itself uses it too. */
    @Volatile
    var delayMs: Int = ControllerClient.DEFAULT_DELAY_MS
        private set

    private val commands = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    fun start() {
        // Commands are admitted one at a time in the order they arrive, so the replay guard sees each controller's
        // numbers in order; each then runs on its own, so a press during a job is answered busy.
        scope.launch {
            for ((topic, payload) in commands) {
                val run = guarded { admit(topic, payload) } ?: continue
                scope.launch { guarded { run() } }
            }
        }
        relay.subscribe(Topics.allCmds(deviceHex)) { topic, payload -> commands.trySend(topic to payload) }
        relay.onConnected {
            scope.launch { runCatching { relay.publish(Topics.online(deviceHex), ONLINE, retain = true) } }
        }
        relay.start()
    }

    /** Marks the device offline before a clean shutdown (the last will only fires on unclean disconnects). */
    suspend fun stop() {
        runCatching { relay.publish(Topics.online(deviceHex), OFFLINE, retain = true) }
        relay.stop()
    }

    /** Uncaught, one failing command (an OS permission, a lost connection) takes the whole service down. */
    private inline fun <T> guarded(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("command failed: $e")
        null
    }

    /** Authenticates [payload] and checks it isn't replayed; returns what carries it out, or null if it is dropped. */
    private fun admit(topic: String, payload: ByteArray): (suspend () -> Unit)? {
        val controllerHex = Topics.controllerOfCmd(deviceHex, topic) ?: return null
        val controller = registry.find(controllerHex) ?: run {
            log("ignored command from unknown controller $controllerHex")
            return null
        }
        val envelope = Envelope.forPair(controller.key, deviceId, controllerHex.hexToBytes())
        val command = try {
            Codec.decodeCommand(envelope.open(Direction.CMD, payload))
        } catch (e: EnvelopeException) {
            log("dropped unauthenticated command for ${controller.name}")
            return null
        } catch (e: Exception) {
            log("dropped malformed command for ${controller.name}: $e")
            return null
        }
        if (!replay.accept(controllerHex, command)) {
            log("dropped replayed/stale command from ${controller.name}")
            return null
        }
        // A job's events go under a key that is gone once the job ends (ADR 0030); older controllers send no epk.
        val epk = (command as? JobCommand)?.epk
        val seal: (ByteArray) -> ByteArray = if (epk == null) {
            { envelope.seal(Direction.EVT, it) }
        } else {
            val jobEnvelope = try {
                envelope.deviceJob(epk)
            } catch (e: EnvelopeException) {
                log("dropped command with a bad job key from ${controller.name}")
                return null
            }
            jobEnvelope::seal
        }
        val reply: suspend (Event) -> Unit = { event ->
            relay.publish(Topics.evt(deviceHex, controllerHex), seal(Codec.encode(event)))
        }
        return {
            when (command) {
                is Press -> {
                    delayMs = command.delayMs
                    runJob(controller, command.job, command.delayMs, press = true, reply)
                }
                is CalibPhoto -> {
                    command.pressDelayMs?.let { delayMs = it }
                    runJob(controller, command.job, command.pressDelayMs ?: 0, press = command.pressDelayMs != null, reply, calibrating = true)
                }
                is SetCalibration -> {
                    val problem = command.calibration.problem()
                    command.pressDelayMs?.let { delayMs = it }
                    if (problem != null) {
                        reply(ErrorEvent(command.job, ErrorCodes.BAD_CALIBRATION, problem))
                    } else {
                        runJob(
                            controller, command.job, command.pressDelayMs ?: 0, press = command.pressDelayMs != null, reply,
                            calibrating = true, save = command.calibration,
                        )
                    }
                }
                is StatusRequest -> reply(statusSource.status())
            }
        }
    }

    private suspend fun runJob(
        controller: PairedController,
        job: String,
        delayMs: Int,
        press: Boolean,
        reply: suspend (Event) -> Unit,
        calibrating: Boolean = false,
        save: Calibration? = null,
    ) {
        if (!busy.tryLock()) {
            reply(Busy(job))
            return
        }
        try {
            reply(Ack(job))
            if (save != null) {
                calibration.save(save)
                log("calibration set by ${controller.name}")
            }
            if (calibrating) reply(CalibrationState(job, calibration.load()))
            camera.prepare()
            var pressedAt = 0L
            if (press) {
                try {
                    presser.press()
                    pressedAt = clock()
                } catch (e: Exception) {
                    log("press failed: $e")
                    reply(ErrorEvent(job, ErrorCodes.PRESS_FAILED, e.message ?: e.toString()))
                    onJob(JobRecord(job, controller.name, false, null, null, "press failed: ${e.message}"))
                    return
                }
                delay(delayMs.toLong().coerceIn(0, MAX_DELAY_MS))
            }

            val (frame, result, capturedAt) = try {
                captureAndRead()
            } catch (e: Exception) {
                log("camera failed: $e")
                reply(ErrorEvent(job, ErrorCodes.CAMERA_FAILED, e.message ?: e.toString()))
                onJob(JobRecord(job, controller.name, press, null, null, "camera failed: ${e.message}"))
                return
            }

            reply(
                TextResult(
                    job = job,
                    text = result.text,
                    valid = result.valid,
                    confidence = result.confidence,
                    digits = result.digits.map { DigitResult((it.digit ?: '?').toString(), it.confidence) },
                    pressedAt = pressedAt,
                    capturedAt = capturedAt,
                ),
            )
            val chunks = Chunker.split(frame.jpeg)
            chunks.forEachIndexed { i, bytes -> reply(PhotoChunk(job, i, chunks.size, bytes)) }
            onJob(JobRecord(job, controller.name, press, result, frame.jpeg, null))
        } catch (e: Exception) {
            log("job $job failed: $e")
            runCatching { reply(ErrorEvent(job, ErrorCodes.INTERNAL, e.message ?: e.toString())) }
        } finally {
            busy.unlock()
        }
    }

    /**
     * A photo for calibrating on the device itself, after a press and [delayMs] if [press] (ADR 0021). Throws if a
     * controller's job is running, or if the press fails.
     */
    suspend fun calibrationFrame(press: Boolean): Frame {
        check(busy.tryLock()) { "A press from a controller is running. Try again in a moment." }
        try {
            camera.prepare()
            if (press) {
                presser.press()
                delay(delayMs.toLong().coerceIn(0, MAX_DELAY_MS))
            }
            return camera.capture()
        } finally {
            busy.unlock()
        }
    }

    /**
     * Saves a calibration made on the device itself and anchors the display on [photo], the upright photo it was
     * made on, if that reads with it (ADR 0022). Touching the phone to save can move it, and without an anchor the
     * first press would read at the old position, fail, and never make one.
     */
    suspend fun saveCalibration(saved: Calibration, photo: GrayImage) = busy.withLock {
        calibration.save(saved)
        val anchor = withContext(Dispatchers.Default) {
            if (SevenSegReader(saved.config).read(photo).valid) DisplayAnchor.make(photo, saved) else null
        }
        if (anchor != null) {
            calibration.saveAnchor(anchor)
            log("display anchored")
        }
    }

    /**
     * First frame right after the delay; up to two more ~150 ms apart only if the read isn't valid. The row is read
     * where the anchor finds the display in the first frame (ADR 0018); the first valid read after a calibration
     * makes the anchor.
     */
    private suspend fun captureAndRead(): Triple<Frame, SevenSegResult, Long> {
        val saved = calibration.load()
        val first = camera.capture()
        val capturedAt = clock()
        val anchor = calibration.anchor()?.takeIf { it.calibration == saved }
        val config = if (anchor == null) saved.config else follow(anchor, first.gray, saved.config)
        val frames = mutableListOf(first)
        val rows = mutableListOf(first.fullSize?.cut(config))
        var result = read(frames, rows, config)
        while (!result.valid && frames.size < MAX_FRAMES) {
            delay(RETRY_INTERVAL_MS)
            val frame = camera.capture()
            frames += frame
            rows += frame.fullSize?.cut(config)
            result = read(frames, rows, config)
        }
        if (!result.valid && config != saved.config) {
            val unmoved = read(frames, frames.map { it.fullSize?.cut(saved.config) }, saved.config)
            if (unmoved.valid) {
                log("read at the calibrated position instead")
                result = unmoved
            }
        }
        if (anchor == null && result.valid) {
            withContext(Dispatchers.Default) { DisplayAnchor.make(first.gray, saved) }?.let {
                calibration.saveAnchor(it)
                log("display anchored")
            }
        }
        return Triple(first, result, capturedAt)
    }

    /** Where [config] lies now, as far as [anchor] finds the display in [gray]; [config] itself if it doesn't. */
    private suspend fun follow(anchor: DisplayAnchor, gray: GrayImage, config: SevenSegConfig): SevenSegConfig {
        val move = withContext(Dispatchers.Default) { anchor.follow(gray) }
        if (move == null) {
            log("display not found around its calibrated position; reading there")
            return config
        }
        val before = Point(config.corners.sumOf { it.x.toDouble() }.toFloat() / 4 * gray.width, config.corners.sumOf { it.y.toDouble() }.toFloat() / 4 * gray.height)
        val after = move.map(before)
        log("display followed: moved %.0f, %.0f px, turned %.1f°, scaled %.3f".format(after.x - before.x, after.y - before.y, move.degrees, move.scale))
        return config.moved(move, gray.width, gray.height)
    }

    /** Reads the full-size row crops when every frame has the same one, else the scaled photos. */
    private suspend fun read(frames: List<Frame>, rows: List<RowCrop?>, config: SevenSegConfig): SevenSegResult {
        val row = rows[0]
        val sameCrop = row != null && rows.all { it != null && it.region == row.region && it.gray.width == row.gray.width && it.gray.height == row.gray.height }
        return withContext(Dispatchers.Default) {
            if (sameCrop) {
                SevenSegReader(config.inRegion(row!!.region)).readFrames(rows.map { it!!.gray })
            } else {
                SevenSegReader(config).readFrames(frames.map { it.gray })
            }
        }
    }

    companion object {
        val ONLINE = "1".toByteArray()
        val OFFLINE = "0".toByteArray()
        const val MAX_FRAMES = 3
        const val RETRY_INTERVAL_MS = 150L
        const val MAX_DELAY_MS = 10_000L
    }
}
