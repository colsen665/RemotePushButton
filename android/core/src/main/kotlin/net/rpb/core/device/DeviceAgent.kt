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
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.CommandSignatureException
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.Signer
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
import net.rpb.core.protocol.Bind
import net.rpb.core.protocol.Bound
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

/**
 * A paired controller (ADR 0033). [publicKey] is the SubjectPublicKeyInfo of its signing key, null until it is bound.
 * [bindUntil] (device clock, ms) is set while it is pending: the pairing QR is shown and a `bind` is awaited until then.
 * With neither, it was paired before ADR 0033 or missed its window, and takes no commands: it must be paired again.
 */
class PairedController(
    val id: String,
    val name: String,
    val key: ByteArray,
    val publicKey: ByteArray? = null,
    val bindUntil: Long? = null,
) {
    val bound: Boolean get() = publicKey != null

    /** Waiting for its bind, whether or not the deadline has passed. */
    val pending: Boolean get() = publicKey == null && bindUntil != null

    /** Whether a `bind` at [now] wins: pending, and the deadline not passed. */
    fun canBind(now: Long): Boolean = publicKey == null && bindUntil != null && now <= bindUntil

    /** Pending past its deadline: the app removes it (ADR 0033). */
    fun expired(now: Long): Boolean = pending && now > bindUntil!!

    /** Paired before ADR 0033: no key and never pending. Shown as *Pair again*. */
    val needsPairingAgain: Boolean get() = publicKey == null && bindUntil == null

    /** The fingerprint both phones show, once bound. */
    val fingerprint: String? get() = publicKey?.let(CommandSignature::fingerprint)

    /** This controller bound to [publicKey], no longer pending. */
    fun bound(publicKey: ByteArray) = PairedController(id, name, key, publicKey.copyOf(), null)

    companion object {
        /** How long a new pairing waits for its bind after the QR appears (ADR 0033). */
        const val BIND_WINDOW_MS = 10 * 60_000L

        /** A new controller, pending from [now] until [now] + [windowMs]. */
        fun pending(id: String, name: String, key: ByteArray, now: Long, windowMs: Long = BIND_WINDOW_MS) =
            PairedController(id, name, key, null, now + windowMs)
    }
}

/** The device's paired controllers, as the agent sees them. */
interface ControllerRegistry {
    fun find(controllerId: String): PairedController?

    /**
     * Binds [publicKey] to the controller [controllerId] if it [PairedController.canBind] at [now], and saves it; true if
     * this call bound it. The check and the change are one atomic step, so of two binds at once only one wins, and a
     * pairing removed meanwhile (its dialog closed) isn't brought back.
     */
    fun bind(controllerId: String, publicKey: ByteArray, now: Long): Boolean
}

/** A [ControllerRegistry] in memory, with the operations the app's store also offers (ADR 0033). */
class InMemoryControllerRegistry : ControllerRegistry {
    private val controllers = LinkedHashMap<String, PairedController>()

    @Synchronized
    fun add(controller: PairedController) {
        controllers[controller.id] = controller
    }

    @Synchronized
    fun remove(controllerId: String): PairedController? = controllers.remove(controllerId)

    /** Removes [controllerId] only if it is still pending: closing the QR dialog before a bind. */
    @Synchronized
    fun removeIfPending(controllerId: String): Boolean =
        controllers[controllerId]?.takeIf { it.pending }?.let { controllers.remove(controllerId) } != null

    /** Removes every pending controller past its deadline; returns their IDs. */
    @Synchronized
    fun purgeExpired(now: Long): List<String> =
        controllers.values.filter { it.expired(now) }.map { it.id }.onEach { controllers.remove(it) }

    @Synchronized
    fun all(): List<PairedController> = controllers.values.toList()

    @Synchronized
    override fun find(controllerId: String): PairedController? = controllers[controllerId]

    @Synchronized
    override fun bind(controllerId: String, publicKey: ByteArray, now: Long): Boolean {
        val controller = controllers[controllerId]?.takeIf { it.canBind(now) } ?: return false
        controllers[controllerId] = controller.bound(publicKey)
        return true
    }
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
 * [signer] is the device's signing key, whose public key is in every pairing code: it signs each job's key exchange,
 * so that only this device can answer (ADR 0033).
 */
class DeviceAgent(
    private val deviceId: ByteArray,
    private val signer: Signer,
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

    /**
     * Authenticates [payload] and checks it isn't replayed; returns what carries it out, or null if it is dropped.
     * Every command must be signed with the controller's bound key; a pending controller takes only a `bind`, signed by
     * the key it carries, before its deadline; one with neither takes nothing (ADR 0033).
     */
    private suspend fun admit(topic: String, payload: ByteArray): (suspend () -> Unit)? {
        val controllerHex = Topics.controllerOfCmd(deviceHex, topic) ?: return null
        val controller = registry.find(controllerHex) ?: run {
            log("ignored command from unknown controller $controllerHex")
            return null
        }
        val now = clock()
        val envelope = Envelope.forPair(controller.key, deviceId, controllerHex.hexToBytes())
        var refusal = "not signed by its key"
        val command = try {
            Codec.decodeCommand(
                envelope.openCommand(payload) { cbor ->
                    controller.publicKey ?: run {
                        refusal = when {
                            !controller.pending -> "not bound: pair again"
                            !controller.canBind(now) -> "past its bind deadline"
                            else -> {
                                // The only bytes parsed before their signature is checked: a bind names its own key.
                                (runCatching { Codec.decodeCommand(cbor) }.getOrNull() as? Bind)?.let { return@openCommand it.pub }
                                "not a bind, from a pending controller"
                            }
                        }
                        null
                    }
                },
            )
        } catch (e: CommandSignatureException) {
            log("dropped command for ${controller.name}: $refusal")
            return null
        } catch (e: EnvelopeException) {
            log("dropped unauthenticated command for ${controller.name}")
            return null
        } catch (e: Exception) {
            log("dropped malformed command for ${controller.name}: $e")
            return null
        }
        // Every event goes under the key of its command's job, which is gone once the job ends (ADR 0030); nothing
        // goes under the pairing key (ADR 0033). Before the replay guard: a pending controller's bind is signed by
        // whoever holds the QR, and one that can't be answered must not use up a number, or it would shut out the
        // real bind for the rest of the window.
        val job = command as? JobCommand
        val epk = job?.epk ?: run {
            log("dropped command without a job key from ${controller.name}")
            return null
        }
        val jobEnvelope = try {
            envelope.deviceJob(epk)
        } catch (e: EnvelopeException) {
            log("dropped command with a bad job key from ${controller.name}")
            return null
        }
        if (!replay.accept(controllerHex, command)) {
            log("dropped replayed/stale command from ${controller.name}")
            return null
        }
        // Once per job, and only for a command that passed the replay guard: a replay costs no signature. Off the
        // caller's thread, which is the main one on Android, as a Keystore signature blocks.
        val signature = withContext(Dispatchers.IO) { jobEnvelope.sign(job.job, signer) }
        // Only once the answer can be sealed: a bind the controller never hears of would leave it bound here alone.
        if (command is Bind) {
            val bound = controller.publicKey
            when {
                bound == null -> if (registry.bind(controllerHex, command.pub, now)) {
                    log("bound ${controller.name}")
                } else {
                    log("refused bind for ${controller.name}: no longer pending")
                    return null
                }
                !bound.contentEquals(command.pub) -> {
                    log("refused bind for ${controller.name}: already bound")
                    return null
                }
                // Signed by the bound key: a controller whose answer was lost asks again, and gets it again.
                else -> Unit
            }
        }
        val reply: suspend (Event) -> Unit = { event ->
            relay.publish(Topics.evt(deviceHex, controllerHex), jobEnvelope.seal(Codec.encode(event), signature))
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
                is StatusRequest -> reply(statusSource.status().copy(job = command.job))
                is Bind -> reply(Bound(command.job, CommandSignature.fingerprint(command.pub)))
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
