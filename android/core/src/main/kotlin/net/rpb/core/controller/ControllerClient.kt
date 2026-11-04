package net.rpb.core.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.toHex
import net.rpb.core.protocol.Ack
import net.rpb.core.protocol.Busy
import net.rpb.core.ocr.Calibration
import net.rpb.core.protocol.CalibPhoto
import net.rpb.core.protocol.CalibrationState
import net.rpb.core.protocol.ChunkAssembler
import net.rpb.core.protocol.Codec
import net.rpb.core.protocol.Command
import net.rpb.core.protocol.ErrorEvent
import net.rpb.core.protocol.Event
import net.rpb.core.protocol.PhotoChunk
import net.rpb.core.protocol.Press
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.SetCalibration
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.StatusRequest
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.Relay
import net.rpb.core.relay.Topics
import java.util.concurrent.ConcurrentHashMap

sealed interface PressUpdate {
    data class Sent(val job: String) : PressUpdate
    data object Acked : PressUpdate
    /** The device's calibration, in calibration jobs only. */
    data class Calibrated(val calibration: Calibration) : PressUpdate
    data class Text(val result: TextResult) : PressUpdate
    data class PhotoProgress(val received: Int, val total: Int) : PressUpdate
    class Photo(val jpeg: ByteArray) : PressUpdate
    data object DeviceBusy : PressUpdate
    data class Failed(val code: String, val message: String) : PressUpdate
}

/** The controller side of the protocol for one paired device. */
class ControllerClient(
    private val pairing: PairingCode,
    private val relay: Relay,
    private val seq: SeqCounter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val jobTimeoutMs: Long = JOB_TIMEOUT_MS,
) {
    private val deviceHex = pairing.deviceId.toHex()
    private val controllerHex = pairing.controllerId.toHex()
    private val envelope = pairing.envelope()
    private val jobs = ConcurrentHashMap<String, OpenJob>()
    private val sending = Mutex()
    private val assembler = ChunkAssembler()

    private val _online = MutableStateFlow<Boolean?>(null)
    /** Device presence from the retained online flag; null until known. */
    val online: StateFlow<Boolean?> = _online

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status

    val connected: StateFlow<Boolean> get() = relay.connected

    fun start() {
        // Events first: the retained online flag arrives once its own subscription is active, and a press sent on
        // seeing it must not have its reply arrive before the events subscription is.
        relay.subscribe(Topics.evt(deviceHex, controllerHex)) { _, payload -> onEvent(payload) }
        relay.subscribe(Topics.online(deviceHex)) { _, payload -> _online.value = payload.contentEquals("1".toByteArray()) }
        relay.onConnected { scope.launch { requestStatus() } }
        relay.start()
    }

    fun stop() = relay.stop()

    suspend fun requestStatus() {
        runCatching { publish { StatusRequest(seq.next(), clock()) } }.onFailure { log("status request failed: $it") }
    }

    fun press(delayMs: Int = DEFAULT_DELAY_MS): Flow<PressUpdate> =
        runJob { job, epk -> Press(seq.next(), clock(), job, delayMs, epk) }

    /** A photo to calibrate on; with [pressDelayMs], the device presses first and waits that long (ADR 0021). */
    fun calibrationPhoto(pressDelayMs: Int? = null): Flow<PressUpdate> =
        runJob { job, epk -> CalibPhoto(seq.next(), clock(), job, pressDelayMs, epk) }

    /** Saves [calibration] on the device, which then answers like [calibrationPhoto] with the new calibration. */
    fun setCalibration(calibration: Calibration, pressDelayMs: Int? = null): Flow<PressUpdate> =
        runJob { job, epk -> SetCalibration(seq.next(), clock(), job, calibration, pressDelayMs, epk) }

    private fun runJob(command: (job: String, epk: ByteArray) -> Command): Flow<PressUpdate> = channelFlow {
        val job = Keys.random(8).toHex()
        val keys = JobKeyPair.generate()
        val open = OpenJob(job, keys)
        jobs[job] = open
        try {
            publish { command(job, keys.public) }
            send(PressUpdate.Sent(job))
            val finished = withTimeoutOrNull(jobTimeoutMs) { followJob(job, open.events) { send(it) } }
            if (finished == null) send(PressUpdate.Failed("timeout", "no complete reply from device"))
        } finally {
            jobs.remove(job)
            open.close()
            assembler.discard(job)
        }
    }

    private suspend fun followJob(job: String, events: Channel<Event>, emit: suspend (PressUpdate) -> Unit) {
        val first = withTimeoutOrNull(ackTimeoutMs) { events.receive() }
            ?: return emit(PressUpdate.Failed("timeout", "device did not acknowledge (offline?)"))
        when (first) {
            is Busy -> return emit(PressUpdate.DeviceBusy)
            is ErrorEvent -> return emit(PressUpdate.Failed(first.code, first.msg))
            else -> emit(PressUpdate.Acked)
        }
        // If the ack itself was lost, the first event is already part of the result.
        var pending: Event? = first.takeUnless { it is Ack }
        while (true) {
            val event = pending ?: events.receive()
            pending = null
            when (event) {
                is CalibrationState -> emit(PressUpdate.Calibrated(event.calibration))
                is TextResult -> emit(PressUpdate.Text(event))
                is PhotoChunk -> {
                    val whole = assembler.add(job, event.i, event.n, event.bytes)
                    if (whole != null) return emit(PressUpdate.Photo(whole))
                    assembler.progress(job)?.let { (got, total) -> emit(PressUpdate.PhotoProgress(got, total)) }
                }
                is ErrorEvent -> return emit(PressUpdate.Failed(event.code, event.msg))
                else -> Unit
            }
        }
    }

    /**
     * Numbers and sends a command in one step. The device drops a command numbered below one it already has, so the
     * status request sent on connecting must not overtake a press numbered before it.
     */
    private suspend fun publish(command: () -> Command) = sending.withLock {
        relay.publish(Topics.cmd(deviceHex, controllerHex), envelope.seal(Direction.CMD, Codec.encode(command())))
    }

    private fun onEvent(payload: ByteArray) {
        JobEnvelope.deviceEpk(payload)?.let { return onJobEvent(payload, it) }
        // Version 1 carries only status: every job's events come under its job key, so a version 1 job event can
        // only be a forgery by someone holding K, or a device older than ADR 0030, which none is (ADR 0030).
        val event = try {
            Codec.decodeEvent(envelope.open(Direction.EVT, payload))
        } catch (e: EnvelopeException) {
            log("dropped unauthenticated event")
            return
        } catch (e: Exception) {
            log("dropped malformed event: $e")
            return
        }
        if (event is Status) _status.value = event else log("dropped version 1 job event")
    }

    /** The job whose key opens [payload] is the one it belongs to; its ID isn't on the wire. */
    private fun onJobEvent(payload: ByteArray, deviceEpk: ByteArray) {
        for (open in jobs.values) {
            val plain = open.open(envelope, payload, deviceEpk) ?: continue
            val event = try {
                Codec.decodeEvent(plain)
            } catch (e: Exception) {
                log("dropped malformed event: $e")
                return
            }
            if (jobOf(event) == open.job) open.events.trySend(event) else log("dropped event naming another job")
            return
        }
        log("dropped unauthenticated event")
    }

    private fun jobOf(event: Event): String? = when (event) {
        is Ack -> event.job
        is Busy -> event.job
        is CalibrationState -> event.job
        is TextResult -> event.job
        is PhotoChunk -> event.job
        is ErrorEvent -> event.job
        is Status -> null
    }

    /**
     * A job in progress. Its ephemeral private key lives until the device's first event names the device's key,
     * then only the derived job envelope remains, until the job ends (ADR 0030).
     */
    private class OpenJob(val job: String, keys: JobKeyPair) {
        val events = Channel<Event>(Channel.UNLIMITED)
        private var keys: JobKeyPair? = keys
        private var jobEnvelope: JobEnvelope? = null

        /** [payload] opened, or null if it isn't this job's. */
        @Synchronized
        fun open(pairEnvelope: Envelope, payload: ByteArray, deviceEpk: ByteArray): ByteArray? {
            jobEnvelope?.let { known ->
                if (!known.deviceEpk.contentEquals(deviceEpk)) return null
                return try {
                    known.open(payload)
                } catch (e: EnvelopeException) {
                    null
                }
            }
            val pair = keys ?: return null
            return try {
                val candidate = pairEnvelope.controllerJob(pair, deviceEpk)
                candidate.open(payload).also {
                    jobEnvelope = candidate
                    pair.destroy()
                    keys = null
                }
            } catch (e: EnvelopeException) {
                null
            }
        }

        @Synchronized
        fun close() {
            keys?.destroy()
            keys = null
            jobEnvelope = null
        }
    }

    companion object {
        const val ACK_TIMEOUT_MS = 15_000L
        const val JOB_TIMEOUT_MS = 60_000L
        /** Wait between the press and the photo, unless the user sets another for the device (ADR 0019). */
        const val DEFAULT_DELAY_MS = 3000
    }
}
