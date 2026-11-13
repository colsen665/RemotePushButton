package net.rpb.core.controller

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.Envelope
import net.rpb.core.crypto.EnvelopeException
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.Signer
import net.rpb.core.crypto.toHex
import net.rpb.core.protocol.Ack
import net.rpb.core.protocol.Bind
import net.rpb.core.protocol.Bound
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
import net.rpb.core.vault.KeyProblem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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

sealed interface BindResult {
    /** The device bound the key; both phones show [fingerprint]. */
    data class Bound(val fingerprint: String) : BindResult
    data class Failed(val code: String, val message: String) : BindResult
}

/**
 * The controller side of the protocol for one paired device; [signer] signs every command, and every event must carry
 * the device's signature, by the key in the pairing code, over its job's key exchange (ADR 0033).
 */
class ControllerClient(
    private val pairing: PairingCode,
    private val signer: Signer,
    private val relay: Relay,
    private val seq: SeqCounter,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
    private val ackTimeoutMs: Long = ACK_TIMEOUT_MS,
    private val jobTimeoutMs: Long = JOB_TIMEOUT_MS,
    private val statusIntervalMs: Long = STATUS_INTERVAL_MS,
) {
    private val deviceHex = pairing.deviceId.toHex()
    private val controllerHex = pairing.controllerId.toHex()
    private val envelope = pairing.envelope()
    private val devicePub = pairing.devicePub.copyOf()
    private val jobs = ConcurrentHashMap<String, OpenJob>()
    private val sending = Mutex()
    private val assembler = ChunkAssembler()

    private val _online = MutableStateFlow<Boolean?>(null)
    /** Device presence from the retained online flag; null until known. */
    val online: StateFlow<Boolean?> = _online

    private val _status = MutableStateFlow<Status?>(null)
    /** The device's answer to the latest status request that has been answered. */
    val status: StateFlow<Status?> = _status
    private val statusAsked = AtomicLong()
    private var statusShown = 0L
    private val flagAsks = AtomicInteger()

    private val _forgeries = MutableStateFlow(0)
    /**
     * Events that opened under a job's key but weren't signed by the device for that job, and `bind`s the device
     * answered with another key's fingerprint. Either takes the pairing key `K`, so nonzero means someone holding it,
     * or the device's key was replaced (ADR 0033). Not counted, as the relay alone can make them: events that open
     * under no open job's key (junk, replays, answers that came too late), events in an older envelope, which aren't
     * opened, and the online flag.
     */
    val forgeries: StateFlow<Int> = _forgeries

    val connected: StateFlow<Boolean> get() = relay.connected

    fun start() {
        // Events first: the retained online flag arrives once its own subscription is active, and a press sent on
        // seeing it must not have its reply arrive before the events subscription is.
        relay.subscribe(Topics.evt(deviceHex, controllerHex)) { _, payload -> onEvent(payload) }
        relay.subscribe(Topics.online(deviceHex)) { _, payload ->
            val online = payload.contentEquals("1".toByteArray())
            _online.value = online
            // The device never sends a status unasked (ADR 0033). The flag is retained, so this asks on every connect
            // while the device is online, and again whenever it comes back.
            if (online) askOnFlag()
        }
        relay.start()
    }

    fun stop() = relay.stop()

    /**
     * The online flag isn't authenticated, and the relay can repeat it at will. Each request costs a signature on
     * both phones and an open job here, so the flag opens one request at a time, at most one per [statusIntervalMs]:
     * flags that arrive meanwhile are answered by one more request afterwards.
     */
    private fun askOnFlag() {
        if (flagAsks.getAndIncrement() > 0) return
        scope.launch {
            do {
                val seen = flagAsks.get()
                coroutineScope {
                    launch { delay(statusIntervalMs) }
                    if (_online.value == true) ask()?.join()
                }
            } while (!flagAsks.compareAndSet(seen, 0))
        }
    }

    /**
     * Asks for the device's status, which arrives in [status] if the device answers within the ack timeout. The
     * request is a job of its own, so only an answer to it, signed by the device, counts (ADR 0033). Fails quietly,
     * also when the signing key needs the screen lock: the next press asks for it.
     */
    suspend fun requestStatus() {
        ask()
    }

    /** Sends a status request; the returned job ends when it is answered or timed out. Null if it couldn't be sent. */
    private suspend fun ask(): Job? {
        val open = openJob()
        val asked = statusAsked.incrementAndGet()
        try {
            publish { StatusRequest(seq.next(), clock(), open.job, open.epk) }
        } catch (e: CancellationException) {
            closeJob(open)
            throw e
        } catch (e: Exception) {
            closeJob(open)
            // The class only: a Keystore message can name the key's alias, which holds both topic IDs.
            log(if (e is KeyProblem.NeedsAuthentication) "status request needs the screen lock" else "status request failed: ${e.javaClass.simpleName}")
            return null
        }
        return scope.launch {
            try {
                val status = withTimeoutOrNull(ackTimeoutMs) {
                    var event = open.events.receive()
                    while (event !is Status) event = open.events.receive()
                    event
                } ?: return@launch
                showStatus(asked, status)
            } finally {
                closeJob(open)
            }
        }
    }

    /** Answers can arrive out of order; an older request's answer doesn't replace a newer one's. */
    @Synchronized
    private fun showStatus(asked: Long, status: Status) {
        if (asked <= statusShown) return
        statusShown = asked
        _status.value = status
    }

    /**
     * Binds the [signer]'s key to this pairing on the device, the first command after the scan (ADR 0033). Fails with
     * [LOCKED] if the key needs the screen lock first, `timeout` if the device doesn't answer, which is also how it
     * refuses (the QR dialog closed, the deadline passed, or another phone bound first), and `mismatch` if it answers
     * with another key's fingerprint. Without an answer it sends the same `bind` once more, with a fresh number: the
     * device answers a bound controller's `bind` with its own key again, so a lost answer doesn't fail the pairing.
     */
    suspend fun bind(): BindResult {
        val open = openJob()
        try {
            val pub = signer.publicKey
            repeat(BIND_ATTEMPTS) {
                try {
                    publish { Bind(seq.next(), clock(), open.job, open.epk, pub) }
                } catch (e: KeyProblem.NeedsAuthentication) {
                    return BindResult.Failed(LOCKED, "screen lock needed")
                }
                val event = withTimeoutOrNull(ackTimeoutMs) { open.events.receive() } ?: return@repeat
                return when {
                    event is Bound && event.fingerprint == CommandSignature.fingerprint(pub) -> {
                        // The status request sent on the online flag was dropped while the pairing was pending.
                        scope.launch { requestStatus() }
                        BindResult.Bound(event.fingerprint)
                    }
                    event is Bound -> {
                        log("bind answered with another key's fingerprint")
                        _forgeries.update { it + 1 }
                        BindResult.Failed(MISMATCH, "the device bound another key")
                    }
                    event is ErrorEvent -> BindResult.Failed(event.code, event.msg)
                    else -> BindResult.Failed("unexpected", "unexpected answer to bind")
                }
            }
            return BindResult.Failed("timeout", "the device did not bind the key")
        } finally {
            closeJob(open)
        }
    }

    fun press(delayMs: Int = DEFAULT_DELAY_MS): Flow<PressUpdate> =
        runJob { job, epk -> Press(seq.next(), clock(), job, delayMs, epk) }

    /** A photo to calibrate on; with [pressDelayMs], the device presses first and waits that long (ADR 0021). */
    fun calibrationPhoto(pressDelayMs: Int? = null): Flow<PressUpdate> =
        runJob { job, epk -> CalibPhoto(seq.next(), clock(), job, pressDelayMs, epk) }

    /** Saves [calibration] on the device, which then answers like [calibrationPhoto] with the new calibration. */
    fun setCalibration(calibration: Calibration, pressDelayMs: Int? = null): Flow<PressUpdate> =
        runJob { job, epk -> SetCalibration(seq.next(), clock(), job, calibration, pressDelayMs, epk) }

    /** A signing key that needs the screen lock ends the job as `Failed(LOCKED)`, before anything is sent (ADR 0033). */
    private fun runJob(command: (job: String, epk: ByteArray) -> Command): Flow<PressUpdate> = channelFlow {
        val open = openJob()
        val job = open.job
        try {
            try {
                publish { command(job, open.epk) }
            } catch (e: KeyProblem.NeedsAuthentication) {
                send(PressUpdate.Failed(LOCKED, "screen lock needed"))
                return@channelFlow
            }
            send(PressUpdate.Sent(job))
            val finished = withTimeoutOrNull(jobTimeoutMs) { followJob(job, open.events) { send(it) } }
            if (finished == null) send(PressUpdate.Failed("timeout", "no complete reply from device"))
        } finally {
            closeJob(open)
        }
    }

    private fun openJob(): OpenJob {
        val job = Keys.random(8).toHex()
        return OpenJob(job, JobKeyPair.generate()).also { jobs[job] = it }
    }

    private fun closeJob(open: OpenJob) {
        jobs.remove(open.job)
        open.close()
        assembler.discard(open.job)
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
        relay.publish(Topics.cmd(deviceHex, controllerHex), envelope.sealCommand(Codec.encode(command()), signer))
    }

    private fun onEvent(payload: ByteArray) {
        // Every event comes under its job's key and with the device's signature (ADR 0033). Version 1 under the
        // pairing key, or version 2 unsigned, can only be a forgery by someone holding K, or a device that is too old.
        val deviceEpk = JobEnvelope.deviceEpk(payload) ?: run {
            log("dropped event outside a version 3 job envelope")
            return
        }
        for (open in jobs.values) {
            val plain = when (val opened = open.open(envelope, payload, deviceEpk, devicePub)) {
                Opened.NotThisJob -> continue
                Opened.BadSignature -> {
                    log("dropped event not signed by the device")
                    _forgeries.update { it + 1 }
                    return
                }
                is Opened.Message -> opened.cbor
            }
            val event = try {
                Codec.decodeEvent(plain)
            } catch (e: Exception) {
                // The class only: a parser's message can quote the decrypted event.
                log("dropped malformed event: ${e.javaClass.simpleName}")
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
        is Bound -> event.job
        is Status -> event.job
    }

    private sealed interface Opened {
        /** No key of this job opens it. */
        data object NotThisJob : Opened

        /** It opens under this job's key, but isn't signed by the device for this job: someone holding K made it. */
        data object BadSignature : Opened

        class Message(val cbor: ByteArray) : Opened
    }

    /**
     * A job in progress. Its ephemeral private key lives until an event signed by the device names the device's key,
     * then only the derived job envelope remains, until the job ends (ADR 0030). The signature is checked once, on
     * the first event that opens, whichever of the job's events that is; later ones must carry the same (ADR 0033).
     */
    private class OpenJob(val job: String, keys: JobKeyPair) {
        val events = Channel<Event>(Channel.UNLIMITED)
        val epk: ByteArray = keys.public
        private var keys: JobKeyPair? = keys
        private var jobEnvelope: JobEnvelope? = null
        private var signature: ByteArray? = null

        @Synchronized
        fun open(pairEnvelope: Envelope, payload: ByteArray, deviceEpk: ByteArray, devicePub: ByteArray): Opened {
            jobEnvelope?.let { known ->
                if (!known.deviceEpk.contentEquals(deviceEpk)) return Opened.NotThisJob
                val message = try {
                    known.open(payload)
                } catch (e: EnvelopeException) {
                    return Opened.NotThisJob
                }
                return if (message.signature.contentEquals(signature)) Opened.Message(message.cbor) else Opened.BadSignature
            }
            val pair = keys ?: return Opened.NotThisJob
            val candidate = try {
                pairEnvelope.controllerJob(pair, deviceEpk)
            } catch (e: EnvelopeException) {
                return Opened.NotThisJob
            }
            val message = try {
                candidate.open(payload)
            } catch (e: EnvelopeException) {
                return Opened.NotThisJob
            }
            // A forger holding K can make a job key with its own ephemeral key, but not the device's signature over it.
            // Refused, it leaves the job waiting for the device's own answer.
            if (!candidate.verify(job, message.signature, devicePub)) return Opened.BadSignature
            jobEnvelope = candidate
            signature = message.signature
            pair.destroy()
            keys = null
            return Opened.Message(message.cbor)
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
        /** The signing key needs the screen lock: ask for it, then try again (ADR 0033). */
        const val LOCKED = "locked"
        /** The device answered a `bind` with another key's fingerprint: another phone bound in the window (ADR 0033). */
        const val MISMATCH = "mismatch"
        /** A `bind` is sent at most this often, each waiting the ack timeout for its answer. */
        const val BIND_ATTEMPTS = 2
        /** The least time between two status requests made for the online flag. */
        const val STATUS_INTERVAL_MS = 5_000L
    }
}
