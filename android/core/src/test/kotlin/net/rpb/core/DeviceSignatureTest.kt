package net.rpb.core

import com.google.crypto.tink.subtle.XChaCha20Poly1305
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.rpb.core.controller.BindResult
import net.rpb.core.controller.ControllerClient
import net.rpb.core.controller.PressUpdate
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.Direction
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.JobKeys
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.toHex
import net.rpb.core.device.CalibrationStore
import net.rpb.core.device.DeviceAgent
import net.rpb.core.device.Frame
import net.rpb.core.device.FrameSource
import net.rpb.core.device.InMemoryControllerRegistry
import net.rpb.core.device.PairedController
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.ocr.GrayImage
import net.rpb.core.protocol.Ack
import net.rpb.core.protocol.Bind
import net.rpb.core.protocol.Bound
import net.rpb.core.protocol.Codec
import net.rpb.core.protocol.Event
import net.rpb.core.protocol.InMemorySeqStore
import net.rpb.core.protocol.JobCommand
import net.rpb.core.protocol.PhotoChunk
import net.rpb.core.protocol.Press
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.StatusRequest
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.Topics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Events must carry the device's signature over their job's key exchange (ADR 0033): someone holding `K` who reads
 * commands and publishes events (the device's broker credential, or the relay) can't answer in the device's place.
 */
class DeviceSignatureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bus = LocalBus()
    private val deviceId = Keys.random(16)
    private val deviceHex = deviceId.toHex()
    private val controllerId = Keys.random(16)
    private val controllerHex = controllerId.toHex()
    private val key = Keys.random(32)
    private val deviceSigner = SoftwareSigner()
    private val controllerSigner = SoftwareSigner()
    private val pairing = PairingCode(
        deviceId = deviceId, controllerId = controllerId, key = key, devicePub = deviceSigner.publicKey, broker = BrokerConfig("relay.invalid"),
    )
    private val envelope = pairing.envelope()
    private val controllerLog = CopyOnWriteArrayList<String>()
    private val evtTopic = Topics.evt(deviceHex, controllerHex)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun client(log: MutableList<String> = controllerLog) = ControllerClient(
        pairing = pairing,
        signer = controllerSigner,
        relay = bus.relay(),
        seq = SeqCounter(InMemorySeqStore(), "seq"),
        scope = scope,
        log = { log += it },
        ackTimeoutMs = 500,
        jobTimeoutMs = 3_000,
        statusIntervalMs = 400,
    ).also { it.start() }

    /** The transcript as ADR 0033 lays it out. */
    private fun transcript(controllerEpk: ByteArray, deviceEpk: ByteArray, job: String, controller: ByteArray = controllerId) =
        "rpb3 job".toByteArray() + byteArrayOf(3) + deviceId + controller + controllerEpk + deviceEpk + job.toByteArray()

    private fun signed(message: ByteArray, by: SoftwareSigner = deviceSigner) = CommandSignature.derToRaw(by.sign(message))

    /** The events a device would answer [command] with. */
    private fun answers(command: JobCommand): List<Event> = when (command) {
        is Bind -> listOf(Bound(command.job, CommandSignature.fingerprint(command.pub)))
        is StatusRequest -> listOf(Status(System.currentTimeMillis(), 1, 20f, false, job = command.job))
        else -> listOf(Ack(command.job), TextResult(command.job, "654321", true, 0.9f), PhotoChunk(command.job, 0, 1, ByteArray(100)))
    }

    /**
     * Someone holding `K` who reads the commands and answers each in the device's place, under a job key made with an
     * ephemeral key of their own, with the signature [sign] makes for it, and the [events] chosen for it.
     */
    private fun forger(events: (JobCommand) -> List<Event> = ::answers, sign: (command: JobCommand, job: JobEnvelope) -> ByteArray) {
        val relay = bus.relay()
        relay.subscribe(Topics.cmd(deviceHex, controllerHex)) { _, payload ->
            val command = Codec.decodeCommand(envelope.openCommand(payload, controllerSigner.publicKey)) as JobCommand
            val job = envelope.deviceJob(command.epk!!)
            val signature = sign(command, job)
            scope.launch { for (event in events(command)) relay.publish(evtTopic, job.seal(Codec.encode(event), signature)) }
        }
        relay.start()
    }

    private suspend fun pressFails(client: ControllerClient) {
        val updates = withTimeout(10_000) { client.press(delayMs = 0).toList() }
        assertEquals(listOf(PressUpdate.Sent::class, PressUpdate.Failed::class), updates.map { it::class }, "got $updates")
        assertEquals("timeout", (updates.last() as PressUpdate.Failed).code)
    }

    private suspend fun awaitLog(line: String, count: Int = 1, log: List<String> = controllerLog) =
        withTimeout(5_000) { while (log.count { it == line } < count) delay(10) }

    @Test
    fun `a forger's job events are refused, signed by another key, unsigned, or signed for another job, controller or key exchange`() = runBlocking {
        val forgeries = mapOf<String, (JobCommand, JobEnvelope) -> ByteArray>(
            "another key" to { c, j -> j.sign(c.job, SoftwareSigner()) },
            "another key, over the transcript by hand" to { c, j -> signed(transcript(c.epk!!, j.deviceEpk, c.job), SoftwareSigner()) },
            "no signature" to { _, _ -> ByteArray(64) },
            "another job" to { c, j -> signed(transcript(c.epk!!, j.deviceEpk, "fedcba9876543210")) },
            "another controller" to { c, j -> signed(transcript(c.epk!!, j.deviceEpk, c.job, controller = Keys.random(16))) },
            "swapped ephemeral keys" to { c, j -> signed(transcript(j.deviceEpk, c.epk!!, c.job)) },
            "another device ephemeral key" to { c, _ -> signed(transcript(c.epk!!, JobKeyPair.generate().public, c.job)) },
        )
        var refused = 0
        for ((what, sign) in forgeries) {
            bus = LocalBus()
            val client = client()
            forger(sign = sign)
            pressFails(client)
            // The ack, the text and the photo.
            refused += 3
            awaitLog("dropped event not signed by the device", refused)
            assertEquals(3, client.forgeries.value, what)
            client.stop()
            assertTrue(controllerLog.none { it.startsWith("dropped malformed") }, what)
        }
    }

    @Test
    fun `the same forger with the device's own signature gets through, so the refusals above are for the signature`() = runBlocking {
        val client = client()
        forger { c, j -> signed(transcript(c.epk!!, j.deviceEpk, c.job)) }
        val updates = withTimeout(10_000) { client.press(delayMs = 0).toList() }
        assertEquals("654321", updates.filterIsInstance<PressUpdate.Text>().single().result.text)
        assertIs<PressUpdate.Photo>(updates.last())
    }

    @Test
    fun `a forged job event and a forged status each count once as forgeries, the device's own events don't`() = runBlocking {
        val client = client()
        val genuine = AtomicBoolean(false)
        // One forged event per command: a press's ack, a status request's answer; once genuine, every answer.
        forger(events = { if (genuine.get()) answers(it) else answers(it).take(1) }) { c, j ->
            j.sign(c.job, if (genuine.get()) deviceSigner else SoftwareSigner())
        }
        pressFails(client)
        awaitLog("dropped event not signed by the device")
        assertEquals(1, client.forgeries.value)
        client.requestStatus()
        awaitLog("dropped event not signed by the device", 2)
        assertNull(client.status.value)
        assertEquals(2, client.forgeries.value)

        genuine.set(true)
        val updates = withTimeout(10_000) { client.press(delayMs = 0).toList() }
        assertIs<PressUpdate.Photo>(updates.last())
        val over = bus.published.last { it.first == evtTopic }.second
        client.requestStatus()
        client.awaitBattery(1)
        assertEquals(2, client.forgeries.value)
        // Relay noise isn't counted: junk, an event of a job that is over, a version 2 envelope.
        val relay = bus.relay()
        relay.publish(evtTopic, Keys.random(200))
        relay.publish(evtTopic, over)
        relay.publish(evtTopic, byteArrayOf(2) + Keys.random(200))
        awaitLog("dropped unauthenticated event")
        awaitLog("dropped event outside a version 3 job envelope")
        assertEquals(2, client.forgeries.value)
    }

    @Test
    fun `a bind the device answers with another key's fingerprint counts as a forgery`() = runBlocking {
        val client = client()
        val other = SoftwareSigner().publicKey
        forger(events = { listOf(Bound(it.job, CommandSignature.fingerprint(other))) }) { c, j -> j.sign(c.job, deviceSigner) }
        assertEquals(ControllerClient.MISMATCH, assertIs<BindResult.Failed>(client.bind()).code)
        assertEquals(1, client.forgeries.value)
    }

    @Test
    fun `a forged bound, with the controller's own fingerprint, is refused`() = runBlocking {
        val client = client()
        forger { c, j -> j.sign(c.job, SoftwareSigner()) }
        assertEquals("timeout", assertIs<BindResult.Failed>(client.bind()).code)
        awaitLog("dropped event not signed by the device", ControllerClient.BIND_ATTEMPTS)
        assertEquals(ControllerClient.BIND_ATTEMPTS, client.forgeries.value)
    }

    @Test
    fun `events under the pairing key, or in the unsigned version 2 job envelope, are refused`() = runBlocking {
        val client = client()
        val relay = bus.relay()
        val evt = Keys.deriveSessionKeys(key, deviceId, controllerId).evt
        relay.subscribe(Topics.cmd(deviceHex, controllerHex)) { _, payload ->
            val command = Codec.decodeCommand(envelope.openCommand(payload, controllerSigner.publicKey)) as JobCommand
            // Version 2 (ADR 0030): a job key made with the forger's own ephemeral key, no signature.
            val own = JobKeyPair.generate()
            val jobKey = JobKeys.derive(evt, own.private, command.epk!!, command.epk!!, own.public)
            scope.launch {
                for (event in answers(command)) {
                    val cbor = Codec.encode(event)
                    relay.publish(evtTopic, byteArrayOf(1) + XChaCha20Poly1305(evt).encrypt(cbor, byteArrayOf(1) + deviceId + controllerId + Direction.EVT.code))
                    relay.publish(evtTopic, byteArrayOf(2) + own.public + XChaCha20Poly1305(jobKey).encrypt(cbor, byteArrayOf(2) + deviceId + controllerId + Direction.EVT.code))
                }
            }
        }
        relay.start()
        pressFails(client)
        awaitLog("dropped event outside a version 3 job envelope", 6)
        client.requestStatus()
        awaitLog("dropped event outside a version 3 job envelope", 8)
        delay(600)
        assertNull(client.status.value)
    }

    /** A device answering status requests with [battery], for a controller already bound to [controllerSigner]. */
    private inner class Device {
        @Volatile
        var battery = 77

        @Volatile
        var answering = true

        val registry = InMemoryControllerRegistry().apply {
            add(PairedController.pending(controllerHex, "phone", key, System.currentTimeMillis()))
            check(bind(controllerHex, controllerSigner.publicKey, System.currentTimeMillis()))
        }

        val agent = DeviceAgent(
            deviceId = deviceId,
            signer = deviceSigner,
            relay = bus.relay(),
            registry = registry,
            replay = ReplayGuard(InMemorySeqStore()),
            presser = {},
            camera = object : FrameSource {
                override suspend fun capture() = Frame(ByteArray(10), GrayImage(40, 30))
            },
            calibration = object : CalibrationStore {
                private val calibration = Calibration(SyntheticDisplay("123456").render().second)
                override fun load() = calibration
                override fun save(calibration: Calibration) = Unit
                override fun anchor(): DisplayAnchor? = null
                override fun saveAnchor(anchor: DisplayAnchor) = Unit
            },
            statusSource = {
                check(answering) { "not answering" }
                Status(System.currentTimeMillis(), battery, 30f, true)
            },
            scope = scope,
        ).also { it.start() }

        init {
            // The agent publishes its online flag from a coroutine. A client started before that would take the flag
            // and ask for a status of its own, beside the requests a test counts.
            val deadline = System.currentTimeMillis() + 5_000
            while (bus.published.none { it.first == Topics.online(deviceHex) }) {
                check(System.currentTimeMillis() < deadline) { "the device didn't come online" }
                Thread.sleep(5)
            }
        }
    }

    private suspend fun ControllerClient.awaitBattery(battery: Int) = withTimeout(5_000) { while (status.value?.battery != battery) delay(10) }

    @Test
    fun `a status counts only as the answer to its own request, so a replayed one is dropped`() = runBlocking {
        val device = Device()
        val client = client()
        client.requestStatus()
        client.awaitBattery(77)
        val old = bus.published.filter { it.first == evtTopic }.map { it.second }
        assertEquals(1, old.size)

        device.battery = 50
        client.requestStatus()
        client.awaitBattery(50)
        val replayer = bus.relay()
        // Replayed once the request it answered is over, and again while another request is waiting.
        replayer.publish(evtTopic, old.single())
        awaitLog("dropped unauthenticated event")
        device.answering = false
        client.requestStatus()
        replayer.publish(evtTopic, old.single())
        awaitLog("dropped unauthenticated event", 2)
        delay(700)
        assertEquals(50, client.status.value!!.battery)
    }

    @Test
    fun `an unsolicited status is dropped, even the device's own from an earlier request`() = runBlocking {
        Device()
        val client = client()
        client.requestStatus()
        client.awaitBattery(77)
        val genuine = bus.published.single { it.first == evtTopic }.second
        val otherLog = CopyOnWriteArrayList<String>()
        val other = client(otherLog)
        // A controller that asked nothing, given the device's signed answer to another request of the same pairing.
        bus.relay().publish(evtTopic, genuine)
        awaitLog("dropped unauthenticated event", log = otherLog)
        assertNull(other.status.value)
    }

    @Test
    fun `the controller asks for a status whenever the device's online flag reads 1`() = runBlocking {
        val device = Device()
        val client = client()
        val online = bus.relay()
        online.publish(Topics.online(deviceHex), DeviceAgent.ONLINE)
        client.awaitBattery(77)
        assertEquals(true, client.online.value)
        device.battery = 60
        online.publish(Topics.online(deviceHex), DeviceAgent.OFFLINE)
        delay(200)
        assertEquals(77, client.status.value!!.battery, "no request while offline")
        online.publish(Topics.online(deviceHex), DeviceAgent.ONLINE)
        client.awaitBattery(60)
        val requests = bus.published.filter { it.first == Topics.cmd(deviceHex, controllerHex) }
            .map { Codec.decodeCommand(envelope.openCommand(it.second, controllerSigner.publicKey)) }
        assertEquals(2, requests.count { it is StatusRequest })
        assertTrue(requests.none { it is Press })
    }

    @Test
    fun `an online flag the relay repeats opens one status request at a time, not one each`() = runBlocking {
        Device()
        val client = client()
        val relay = bus.relay()
        repeat(200) { relay.publish(Topics.online(deviceHex), DeviceAgent.ONLINE) }
        client.awaitBattery(77)
        fun requests() = bus.published.count { it.first == Topics.cmd(deviceHex, controllerHex) }
        // One for the first flag and one more for all that came meanwhile, then none until the next flag.
        withTimeout(5_000) { while (requests() < 2) delay(10) }
        delay(1_200)
        assertEquals(2, requests())
        relay.publish(Topics.online(deviceHex), DeviceAgent.ONLINE)
        withTimeout(5_000) { while (requests() < 3) delay(10) }
    }
}
