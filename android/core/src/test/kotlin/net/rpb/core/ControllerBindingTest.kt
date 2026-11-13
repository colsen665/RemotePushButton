package net.rpb.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.rpb.core.controller.BindResult
import net.rpb.core.controller.ControllerClient
import net.rpb.core.controller.PressUpdate
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.JobEnvelope
import net.rpb.core.crypto.JobKeyPair
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.Signer
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
import net.rpb.core.protocol.Bind
import net.rpb.core.protocol.Bound
import net.rpb.core.protocol.Codec
import net.rpb.core.protocol.Command
import net.rpb.core.protocol.InMemorySeqStore
import net.rpb.core.protocol.Press
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.StatusRequest
import net.rpb.core.relay.Relay
import net.rpb.core.relay.Topics
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The device rules of ADR 0033 (who may bind, and which commands a controller's state lets through), over an in-process relay. */
class ControllerBindingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = LocalBus()
    private val deviceId = Keys.random(16)
    private val deviceHex = deviceId.toHex()
    private val registry = InMemoryControllerRegistry()
    private val presses = AtomicInteger()
    private val deviceLog = CopyOnWriteArrayList<String>()
    private val broker = BrokerConfig("relay.invalid", 8883)
    private val deviceSigner = SoftwareSigner()

    private val agent = DeviceAgent(
        deviceId = deviceId,
        signer = deviceSigner,
        relay = bus.relay(),
        registry = registry,
        replay = ReplayGuard(InMemorySeqStore()),
        presser = { presses.incrementAndGet() },
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
        statusSource = { Status(System.currentTimeMillis(), 77, 30f, true) },
        scope = scope,
        log = { deviceLog += it },
    ).also { it.start() }

    @AfterTest
    fun tearDown() = scope.cancel()

    private class Pairing(val code: PairingCode, val controller: PairedController)

    /** A new pairing as the device makes it when it shows the QR, [age] ms ago. */
    private fun pairPending(name: String = "phone", age: Long = 0): Pairing {
        val controllerId = Keys.random(16)
        val key = Keys.random(32)
        val controller = PairedController.pending(controllerId.toHex(), name, key, System.currentTimeMillis() - age)
        registry.add(controller)
        return Pairing(PairingCode(deviceId = deviceId, controllerId = controllerId, key = key, devicePub = deviceSigner.publicKey, broker = broker), controller)
    }

    /** A pairing made before ADR 0033: no key, never pending. */
    private fun pairOld(name: String = "old phone"): Pairing {
        val code = pairPending().code
        val controller = PairedController(code.controllerId.toHex(), name, code.key)
        registry.add(controller)
        return Pairing(code, controller)
    }

    private fun client(pairing: Pairing, signer: Signer, log: (String) -> Unit = {}, relay: Relay = bus.relay()) = ControllerClient(
        pairing = pairing.code,
        signer = signer,
        relay = relay,
        seq = SeqCounter(InMemorySeqStore(), "seq"),
        scope = scope,
        log = log,
        ackTimeoutMs = 1_000,
        jobTimeoutMs = 5_000,
    ).also { it.start() }

    private fun keyOf(pairing: Pairing) = registry.find(pairing.controller.id)?.publicKey

    /** Seals [command] for [pairing] with [signer] and sends it as the controller would, then waits for the device to log [expect]. */
    private suspend fun send(pairing: Pairing, command: Command, signer: Signer, expect: String? = null) {
        val before = deviceLog.size
        bus.relay().publish(Topics.cmd(deviceHex, pairing.controller.id), pairing.code.envelope().sealCommand(Codec.encode(command), signer))
        if (expect != null) withTimeout(5_000) { while (deviceLog.drop(before).none { it == expect }) delay(10) }
    }

    private fun bind(signer: Signer, carried: ByteArray = signer.publicKey): Bind {
        val now = System.currentTimeMillis()
        return Bind(now, now, Keys.random(8).toHex(), JobKeyPair.generate().public, carried)
    }

    private fun press(): Press = System.currentTimeMillis().let { Press(it, it, Keys.random(8).toHex(), 0, JobKeyPair.generate().public) }

    @Test
    fun `a bind within the window binds the key it carries, and both sides show its fingerprint`() = runBlocking {
        val pairing = pairPending(age = PairedController.BIND_WINDOW_MS - 5_000)
        val signer = SoftwareSigner()
        val client = client(pairing, signer)
        val result = assertIs<BindResult.Bound>(client.bind())
        assertEquals(CommandSignature.fingerprint(signer.publicKey), result.fingerprint)
        assertContentEquals(signer.publicKey, keyOf(pairing))
        val bound = registry.find(pairing.controller.id)!!
        assertNull(bound.bindUntil)
        assertEquals(result.fingerprint, bound.fingerprint)
        assertTrue(deviceLog.none { it.contains(result.fingerprint) || it.contains(signer.publicKey.toHex()) }, "keys stay out of the log")

        assertIs<PressUpdate.Photo>(withTimeout(10_000) { client.press(delayMs = 0).toList() }.last())
        assertEquals(1, presses.get())

        // A bound controller whose answer was lost asks again with the same key, and gets the same answer.
        assertEquals(result, client.bind())
        assertContentEquals(signer.publicKey, keyOf(pairing))
    }

    @Test
    fun `a second bind, from another phone that scanned the same QR, is refused and can't press`() = runBlocking {
        val pairing = pairPending()
        val first = SoftwareSigner()
        assertIs<BindResult.Bound>(client(pairing, first).bind())

        val thief = SoftwareSigner()
        send(pairing, bind(thief), thief, "dropped command for phone: not signed by its key")
        assertContentEquals(first.publicKey, keyOf(pairing))
        // Even signed by the bound key, a bind can't swap in another key.
        send(pairing, bind(first, carried = thief.publicKey), first, "refused bind for phone: already bound")
        assertContentEquals(first.publicKey, keyOf(pairing))

        val other = client(pairing, thief)
        assertEquals("timeout", assertIs<BindResult.Failed>(other.bind()).code)
        assertIs<PressUpdate.Failed>(withTimeout(10_000) { other.press(delayMs = 0).toList() }.last())
        assertEquals(0, presses.get())
    }

    @Test
    fun `a bind after the deadline is refused`() = runBlocking {
        val pairing = pairPending(age = PairedController.BIND_WINDOW_MS + 1_000)
        val signer = SoftwareSigner()
        send(pairing, bind(signer), signer, "dropped command for phone: past its bind deadline")
        assertNull(keyOf(pairing))
        assertTrue(registry.find(pairing.controller.id)!!.expired(System.currentTimeMillis()))
        assertEquals("timeout", assertIs<BindResult.Failed>(client(pairing, signer).bind()).code)
    }

    @Test
    fun `a bind not signed by the key it carries is refused, and the pairing waits on for the real one`() = runBlocking {
        val pairing = pairPending()
        val claimed = SoftwareSigner()
        val actual = SoftwareSigner()
        send(pairing, bind(claimed), actual, "dropped command for phone: not signed by its key")
        assertNull(keyOf(pairing))
        assertTrue(registry.find(pairing.controller.id)!!.pending)

        assertIs<BindResult.Bound>(client(pairing, claimed).bind())
        assertContentEquals(claimed.publicKey, keyOf(pairing))
    }

    @Test
    fun `a bind that can't be answered uses up no number, so it can't shut out the real bind`() = runBlocking {
        val pairing = pairPending()
        val thief = SoftwareSigner()
        val now = System.currentTimeMillis()
        // From someone who photographed the QR: self-signed, numbered as high as numbers go, with a job key of no use.
        val junk = Bind(Long.MAX_VALUE, now, Keys.random(8).toHex(), ByteArray(5), thief.publicKey)
        send(pairing, junk, thief, "dropped command with a bad job key from phone")
        assertNull(keyOf(pairing))

        val signer = SoftwareSigner()
        assertIs<BindResult.Bound>(client(pairing, signer).bind())
        assertContentEquals(signer.publicKey, keyOf(pairing))
    }

    @Test
    fun `a bind to a pairing that isn't pending, one made before ADR 0033, is refused`() = runBlocking {
        val pairing = pairOld()
        assertTrue(pairing.controller.needsPairingAgain)
        val signer = SoftwareSigner()
        send(pairing, bind(signer), signer, "dropped command for old phone: not bound: pair again")
        assertNull(keyOf(pairing))
    }

    @Test
    fun `an old pairing takes no command at all`() = runBlocking {
        val pairing = pairOld()
        val signer = SoftwareSigner()
        send(pairing, press(), signer, "dropped command for old phone: not bound: pair again")
        val now = System.currentTimeMillis()
        send(pairing, StatusRequest(now, now, Keys.random(8).toHex(), JobKeyPair.generate().public), signer, "dropped command for old phone: not bound: pair again")
        delay(200)
        assertEquals(0, presses.get())
        assertTrue(bus.published.none { it.first == Topics.evt(deviceHex, pairing.controller.id) }, "no answer of any kind")
    }

    @Test
    fun `a pending controller takes only a bind`() = runBlocking {
        val pairing = pairPending()
        val signer = SoftwareSigner()
        send(pairing, press(), signer, "dropped command for phone: not a bind, from a pending controller")
        assertEquals(0, presses.get())
        assertNull(keyOf(pairing))
    }

    @Test
    fun `a bound controller's commands must be signed with the bound key`() = runBlocking {
        val pairing = pairPending()
        val signer = SoftwareSigner()
        assertIs<BindResult.Bound>(client(pairing, signer).bind())

        send(pairing, press(), SoftwareSigner(), "dropped command for phone: not signed by its key")
        // A signature in range but made by no one.
        val forged = object : Signer {
            override val publicKey = signer.publicKey
            override fun sign(message: ByteArray) = CommandSignature.rawToDer(ByteArray(31) + 1 + ByteArray(31) + 1)
        }
        send(pairing, press(), forged, "dropped command for phone: not signed by its key")
        delay(200)
        assertEquals(0, presses.get())

        send(pairing, press(), signer)
        withTimeout(5_000) { while (presses.get() < 1) delay(10) }
    }

    @Test
    fun `a locked signing key ends a press as locked and sends nothing, and a status request fails quietly`() = runBlocking {
        val pairing = pairPending()
        val signer = SoftwareSigner()
        val logged = CopyOnWriteArrayList<String>()
        val client = client(pairing, signer, log = { logged += it })
        assertIs<BindResult.Bound>(client.bind())
        // The bind, and the status request after it; this bus keeps no online flag to ask on.
        val cmdTopic = Topics.cmd(deviceHex, pairing.controller.id)
        withTimeout(5_000) { while (client.status.value == null || bus.published.count { it.first == cmdTopic } < 2) delay(10) }
        val sent = bus.published.count { it.first == cmdTopic }

        signer.authenticated = false
        assertEquals(listOf<PressUpdate>(PressUpdate.Failed(ControllerClient.LOCKED, "screen lock needed")), client.press().toList())
        assertEquals(ControllerClient.LOCKED, assertIs<PressUpdate.Failed>(client.calibrationPhoto().toList().single()).code)
        client.requestStatus()
        assertTrue("status request needs the screen lock" in logged)
        assertEquals(BindResult.Failed(ControllerClient.LOCKED, "screen lock needed"), client.bind())
        assertEquals(sent, bus.published.count { it.first == cmdTopic })

        signer.authenticated = true
        assertIs<PressUpdate.Photo>(withTimeout(10_000) { client.press(delayMs = 0).toList() }.last())
    }

    @Test
    fun `the device answers a bind under the job key, not the pairing key`() = runBlocking {
        val pairing = pairPending()
        val signer = SoftwareSigner()
        val keys = JobKeyPair.generate()
        val now = System.currentTimeMillis()
        val command = Bind(now, now, "0123456789abcdef", keys.public, signer.publicKey)
        send(pairing, command, signer, "bound phone")
        val evtTopic = Topics.evt(deviceHex, pairing.controller.id)
        val reply = withTimeout(5_000) {
            var found: ByteArray? = null
            while (found == null) {
                found = bus.published.firstOrNull { it.first == evtTopic }?.second
                delay(10)
            }
            found
        }
        val envelope = pairing.code.envelope()
        val deviceEpk = JobEnvelope.deviceEpk(reply)!!
        val job = envelope.controllerJob(keys, deviceEpk)
        val opened = job.open(reply)
        assertTrue(job.verify("0123456789abcdef", opened.signature, deviceSigner.publicKey), "signed by the device's key")
        val bound = assertIs<Bound>(Codec.decodeEvent(opened.cbor))
        assertEquals(Bound("0123456789abcdef", CommandSignature.fingerprint(signer.publicKey)), bound)
    }

    @Test
    fun `a bind whose answer is lost is sent once more, and the device answers it again`() = runBlocking {
        val pairing = pairPending()
        val signer = SoftwareSigner()
        val evtTopic = Topics.evt(deviceHex, pairing.controller.id)
        val client = client(pairing, signer, relay = bus.relay().droppingFirst(evtTopic))
        val result = assertIs<BindResult.Bound>(client.bind())
        assertEquals(CommandSignature.fingerprint(signer.publicKey), result.fingerprint)
        assertContentEquals(signer.publicKey, keyOf(pairing))
        val binds = deviceCommands(pairing).filterIsInstance<Bind>()
        assertEquals(2, binds.size)
        assertEquals(binds[0].job, binds[1].job)
        assertContentEquals(binds[0].epk, binds[1].epk)
        assertContentEquals(binds[0].pub, binds[1].pub)
        assertTrue(binds[1].seq > binds[0].seq, "a fresh number, or the device drops it as a replay")
    }

    @Test
    fun `a bind with no answer is sent twice, then fails`() = runBlocking {
        val pairing = pairPending()
        registry.remove(pairing.controller.id)
        val client = client(pairing, SoftwareSigner())
        assertEquals("timeout", assertIs<BindResult.Failed>(client.bind()).code)
        assertEquals(ControllerClient.BIND_ATTEMPTS, deviceCommands(pairing).filterIsInstance<Bind>().size)
    }

    /** What the controller of [pairing] sent the device, opened with the key of each command's [Bind]. */
    private fun deviceCommands(pairing: Pairing): List<Command> {
        val envelope = pairing.code.envelope()
        return bus.published.filter { it.first == Topics.cmd(deviceHex, pairing.controller.id) }.map { (_, payload) ->
            Codec.decodeCommand(envelope.openCommand(payload) { cbor -> (Codec.decodeCommand(cbor) as? Bind)?.pub ?: keyOf(pairing) })
        }
    }

    @Test
    fun `of many binds at once, exactly one wins`() {
        val registry = InMemoryControllerRegistry()
        val now = System.currentTimeMillis()
        registry.add(PairedController.pending("c", "phone", ByteArray(32), now))
        val start = CountDownLatch(1)
        val winners = CopyOnWriteArrayList<ByteArray>()
        val keys = List(16) { SoftwareSigner().publicKey }
        keys.map { key -> thread { start.await(); if (registry.bind("c", key, now)) winners += key } }.also { start.countDown() }.forEach { it.join() }
        assertEquals(1, winners.size)
        assertContentEquals(winners.single(), registry.find("c")!!.publicKey)
        assertFalse(registry.bind("c", keys[0], now))
    }

    @Test
    fun `the registry removes a pending pairing when its dialog closes or its deadline passes, never a bound one`() {
        val registry = InMemoryControllerRegistry()
        val now = System.currentTimeMillis()
        registry.add(PairedController.pending("closed", "a", ByteArray(32), now))
        registry.add(PairedController.pending("late", "b", ByteArray(32), now - PairedController.BIND_WINDOW_MS - 1))
        registry.add(PairedController.pending("waiting", "c", ByteArray(32), now))
        registry.add(PairedController.pending("bound", "d", ByteArray(32), now - PairedController.BIND_WINDOW_MS + 1_000))
        registry.add(PairedController("old", "e", ByteArray(32)))
        assertTrue(registry.bind("bound", SoftwareSigner().publicKey, now))
        assertFalse(registry.bind("late", SoftwareSigner().publicKey, now))
        assertFalse(registry.bind("old", SoftwareSigner().publicKey, now))

        assertTrue(registry.removeIfPending("closed"))
        assertFalse(registry.removeIfPending("bound"))
        assertFalse(registry.removeIfPending("old"))
        assertEquals(listOf("late"), registry.purgeExpired(now))
        assertEquals(listOf("waiting", "bound", "old"), registry.all().map { it.id })
        assertEquals(listOf("waiting"), registry.purgeExpired(now + PairedController.BIND_WINDOW_MS + 1))
        assertEquals(listOf("bound", "old"), registry.all().map { it.id })
        assertFalse(registry.bind("closed", SoftwareSigner().publicKey, now), "a pairing whose dialog closed can't come back")
    }
}

/** This relay with the first message on [topic] lost on its way in. */
fun Relay.droppingFirst(topic: String): Relay = object : Relay by this {
    private val lost = java.util.concurrent.atomic.AtomicBoolean()

    override fun subscribe(filter: String, handler: (topic: String, payload: ByteArray) -> Unit) =
        this@droppingFirst.subscribe(filter) { t, payload -> if (t != topic || !lost.compareAndSet(false, true)) handler(t, payload) }
}

/** An in-process relay: a publish reaches every matching subscription at once. */
class LocalBus {
    private val subscriptions = CopyOnWriteArrayList<Pair<String, (String, ByteArray) -> Unit>>()
    val published = CopyOnWriteArrayList<Pair<String, ByteArray>>()

    fun relay(): Relay = object : Relay {
        private val own = CopyOnWriteArrayList<Pair<String, (String, ByteArray) -> Unit>>()
        private val onConnect = CopyOnWriteArrayList<() -> Unit>()
        override val connected = MutableStateFlow(false)

        override fun start() {
            subscriptions += own
            connected.value = true
            onConnect.forEach { it() }
        }

        override fun stop() {
            subscriptions -= own.toSet()
            connected.value = false
        }

        override fun subscribe(filter: String, handler: (topic: String, payload: ByteArray) -> Unit) {
            own += filter to handler
        }

        override fun onConnected(action: () -> Unit) {
            onConnect += action
        }

        override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) {
            published += topic to payload
            for ((filter, handler) in subscriptions) if (Topics.matches(filter, topic)) handler(topic, payload)
        }
    }
}
