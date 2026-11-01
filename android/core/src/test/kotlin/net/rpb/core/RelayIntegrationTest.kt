package net.rpb.core

import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.rpb.core.controller.ControllerClient
import net.rpb.core.controller.PressUpdate
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.toHex
import net.rpb.core.device.CalibrationStore
import net.rpb.core.device.ControllerRegistry
import net.rpb.core.device.DeviceAgent
import net.rpb.core.device.Frame
import net.rpb.core.device.FrameSource
import net.rpb.core.device.PairedController
import net.rpb.core.device.RowCutter
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Move
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.RowCrop
import net.rpb.core.protocol.InMemorySeqStore
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.Status
import net.rpb.core.relay.MqttRelay
import net.rpb.core.relay.Topics
import net.rpb.core.relay.Will
import java.net.ServerSocket
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Device and controllers talking through a real (embedded) MQTT broker. */
class RelayIntegrationTest {
    private lateinit var broker: Server
    private lateinit var brokerConfig: BrokerConfig
    /** On Android an uncaught exception in the agent's scope kills the device service. */
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })

    private val deviceId = Keys.random(16)
    private val deviceHex = deviceId.toHex()
    private val registry = ConcurrentHashMap<String, PairedController>()
    private val presses = AtomicInteger()
    private val hardware = CopyOnWriteArrayList<String>()
    private var pressDurationMs = 50L
    @Volatile
    private var statusFails = false

    private val secretMarker = "SECRET-PHOTO-MARKER".toByteArray()
    private val jpeg = Random(7).nextBytes(150_000) + secretMarker + Random(8).nextBytes(50_000)
    private val displayFrame by lazy { SyntheticDisplay("123456").render() }
    private val fullSize by lazy { displayFrame.first.enlarged(2f) }
    /** What the camera sees instead, if set: the scaled photo and the full-size image. */
    @Volatile
    private var scene: Pair<GrayImage, GrayImage>? = null
    @Volatile
    private var savedCalibration: Calibration? = null
    @Volatile
    private var savedAnchor: DisplayAnchor? = null

    private val sniffed = CopyOnWriteArrayList<Pair<String, ByteArray>>()
    private lateinit var sniffer: MqttRelay
    private lateinit var agent: DeviceAgent

    @BeforeTest
    fun setUp() {
        val port = ServerSocket(0).use { it.localPort }
        broker = Server()
        broker.startServer(
            MemoryConfig(
                Properties().apply {
                    setProperty("port", port.toString())
                    setProperty("host", "127.0.0.1")
                    setProperty("allow_anonymous", "true")
                    setProperty("persistence_enabled", "false")
                    setProperty("netty.mqtt.message_size", (1024 * 1024).toString())
                },
            ),
        )
        brokerConfig = BrokerConfig("127.0.0.1", port, tls = false)

        sniffer = MqttRelay(brokerConfig, "sniffer")
        sniffer.subscribe("#") { topic, payload -> sniffed += topic to payload }
        sniffer.start()

        val deviceRelay = MqttRelay(brokerConfig, "device", Will(Topics.online(deviceHex), "0".toByteArray()))
        agent = DeviceAgent(
            deviceId = deviceId,
            relay = deviceRelay,
            registry = ControllerRegistry { registry[it] },
            replay = ReplayGuard(InMemorySeqStore()),
            presser = {
                hardware += "press"
                delay(pressDurationMs)
                presses.incrementAndGet()
            },
            camera = object : FrameSource {
                override fun prepare() {
                    hardware += "prepare"
                }

                override suspend fun capture(): Frame {
                    hardware += "capture"
                    // By default the scaled photo is blank: the read must come from the full-size row crop.
                    val (gray, full) = scene ?: (GrayImage(800, 600) to fullSize)
                    return Frame(jpeg, gray, RowCutter { config -> RowCrop.cut(full, 0, config) })
                }
            },
            calibration = object : CalibrationStore {
                override fun load() = savedCalibration ?: Calibration(displayFrame.second)
                override fun save(calibration: Calibration) {
                    savedCalibration = calibration
                }

                override fun anchor() = savedAnchor

                override fun saveAnchor(anchor: DisplayAnchor) {
                    savedAnchor = anchor
                }
            },
            statusSource = {
                if (statusFails) throw SecurityException("no ACCESS_WIFI_STATE")
                Status(System.currentTimeMillis(), 77, 30.5f, true, 90, -55)
            },
            scope = scope,
        )
        agent.start()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { agent.stop() }
        sniffer.stop()
        scope.cancel()
        broker.stopServer()
    }

    private fun pair(name: String): PairingCode {
        val controllerId = Keys.random(16)
        val key = Keys.random(32)
        registry[controllerId.toHex()] = PairedController(controllerId.toHex(), name, key)
        return PairingCode(deviceId = deviceId, controllerId = controllerId, key = key, broker = brokerConfig, deviceName = "test")
    }

    private fun controller(pairing: PairingCode, ackTimeoutMs: Long = 5_000) = ControllerClient(
        pairing = pairing,
        relay = MqttRelay(brokerConfig, "ctrl-" + pairing.controllerId.toHex().take(8)),
        seq = SeqCounter(InMemorySeqStore(), "seq"),
        scope = scope,
        ackTimeoutMs = ackTimeoutMs,
        jobTimeoutMs = 20_000,
    ).also { it.start() }

    @Test
    fun `press returns text first, then the photo, and the broker only sees ciphertext`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        withTimeout(10_000) { client.status.first { it != null } }
        assertEquals(77, client.status.value!!.battery)

        val updates = withTimeout(20_000) { client.press().toList() }

        assertIs<PressUpdate.Sent>(updates[0])
        assertEquals(PressUpdate.Acked, updates[1])
        val text = (updates[2] as PressUpdate.Text).result
        assertEquals("123456", text.text)
        assertTrue(text.valid)
        val delay = ControllerClient.DEFAULT_DELAY_MS
        assertTrue(text.capturedAt - text.pressedAt >= delay, "photo must be taken ≥$delay ms after the press")
        val photo = updates.last()
        assertIs<PressUpdate.Photo>(photo)
        assertContentEquals(jpeg, photo.jpeg)
        assertTrue(updates.any { it is PressUpdate.PhotoProgress })
        assertEquals(1, presses.get())
        assertEquals(listOf("prepare", "press", "capture"), hardware, "camera must be woken before the press")

        // Everything on the wire except the presence flag is opaque.
        val payloads = sniffed.filterNot { it.first == Topics.online(deviceHex) }
        assertTrue(payloads.size >= 5)
        for ((topic, payload) in payloads) {
            assertFalse(payload.containsSequence(secretMarker), "photo leaked on $topic")
            assertFalse(payload.containsSequence("123456".toByteArray()), "code leaked on $topic")
            assertFalse(payload.containsSequence("press".toByteArray()), "command leaked on $topic")
        }
        client.stop()
    }

    @Test
    fun `calibration photo carries the device's calibration`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        val updates = withTimeout(20_000) { client.calibrationPhoto().toList() }
        val calibrated = updates.filterIsInstance<PressUpdate.Calibrated>().single()
        assertEquals(Calibration(displayFrame.second), calibrated.calibration)
        assertTrue(updates.indexOf(calibrated) < updates.indexOfFirst { it is PressUpdate.Text })
        assertIs<PressUpdate.Photo>(updates.last())
        assertEquals(0, presses.get())
        client.stop()
    }

    @Test
    fun `calibration presses first when asked, and the device keeps that delay for calibrating on its own`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        val updates = withTimeout(20_000) { client.calibrationPhoto(pressDelayMs = 300).toList() }
        val text = updates.filterIsInstance<PressUpdate.Text>().single().result
        assertTrue(text.pressedAt > 0 && text.capturedAt - text.pressedAt >= 300, "photo must be taken ≥300 ms after the press")
        assertEquals(listOf("prepare", "press", "capture"), hardware)
        assertEquals(300, agent.delayMs)

        hardware.clear()
        agent.calibrationFrame(press = true)
        assertEquals(listOf("prepare", "press", "capture"), hardware)
        agent.calibrationFrame(press = false)
        assertEquals(2, presses.get())
        client.stop()
    }

    @Test
    fun `controller saves a calibration, then the device reads with it`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        // Two digits only: the read must use the calibration just sent.
        val twoDigits = displayFrame.second.let { c ->
            val (tl, tr, br, bl) = c.corners
            val third = { a: Point, b: Point -> Point(a.x + (b.x - a.x) / 3, a.y + (b.y - a.y) / 3) }
            c.copy(digits = 2, corners = listOf(tl, third(tl, tr), third(bl, br), bl))
        }
        val sent = Calibration(twoDigits, rotation = 90)
        val updates = withTimeout(20_000) { client.setCalibration(sent).toList() }
        assertEquals(sent, updates.filterIsInstance<PressUpdate.Calibrated>().single().calibration)
        assertEquals(sent, savedCalibration)
        assertEquals("12", updates.filterIsInstance<PressUpdate.Text>().single().result.text)
        assertIs<PressUpdate.Photo>(updates.last())
        client.stop()
    }

    @Test
    fun `device refuses a calibration that would break reads`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        val broken = Calibration(displayFrame.second.copy(corners = List(4) { Point(0.5f, 0.5f) }))
        val updates = withTimeout(20_000) { client.setCalibration(broken).toList() }
        val failed = assertIs<PressUpdate.Failed>(updates.last())
        assertEquals("bad_calibration", failed.code)
        assertEquals(null, savedCalibration)
        client.stop()
    }

    @Test
    fun `device follows a display that moved after the first read`() = runBlocking {
        val photo = Scenes.rig[0].image
        val calibration = Calibration(rigConfig())
        savedCalibration = calibration
        scene = photo to photo.enlarged(2f)
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        assertEquals(Scenes.rig[0].code, readAfterPress(client))
        assertNotNull(savedAnchor, "the first valid read anchors the display")

        // Half a digit height: far beyond the reader's own alignment search.
        val moved = photo.moved(Move(1f, 0f, 50f, -30f))
        scene = moved to moved.enlarged(2f)
        assertEquals(Scenes.rig[0].code, readAfterPress(client))
        assertEquals(calibration, savedCalibration, "following leaves the calibration as it is")
        client.stop()
    }

    @Test
    fun `a calibration saved on the device anchors on its photo, so a move before the first press is followed`() = runBlocking {
        val photo = Scenes.rig[0].image
        val calibration = Calibration(rigConfig())
        agent.saveCalibration(calibration, GrayImage(photo.width, photo.height))
        assertEquals(calibration, savedCalibration)
        assertNull(savedAnchor, "a photo that doesn't read with the calibration makes no anchor")

        agent.saveCalibration(calibration, photo)
        assertNotNull(savedAnchor)
        val moved = photo.moved(Move(1f, 0f, 50f, -30f))
        scene = moved to moved.enlarged(2f)
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        assertEquals(Scenes.rig[0].code, readAfterPress(client))
        client.stop()
    }

    private suspend fun readAfterPress(client: ControllerClient): String {
        val result = withTimeout(20_000) { client.press(delayMs = 0).toList() }.filterIsInstance<PressUpdate.Text>().single().result
        assertTrue(result.valid)
        return result.text
    }

    @Test
    fun `a failing status request leaves the device answering`() = runBlocking {
        statusFails = true
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        client.requestStatus()
        assertIs<PressUpdate.Photo>(withTimeout(20_000) { client.press(delayMs = 100).toList() }.last())
        assertEquals(emptyList(), uncaught)
        client.stop()
    }

    @Test
    fun `second press while busy is rejected`() = runBlocking {
        pressDurationMs = 1500
        val a = controller(pair("a"))
        val b = controller(pair("b"))
        withTimeout(10_000) { a.online.first { it == true } }
        withTimeout(10_000) { b.connected.first { it } }

        val first = async { a.press(delayMs = 100).toList() }
        delay(500)
        val second = withTimeout(10_000) { b.press(delayMs = 100).toList() }
        assertTrue(second.contains(PressUpdate.DeviceBusy), "got $second")
        assertIs<PressUpdate.Photo>(withTimeout(20_000) { first.await() }.last())
        assertEquals(1, presses.get())
        a.stop(); b.stop()
    }

    @Test
    fun `revoked controller is ignored`() = runBlocking {
        val pairing = pair("lost phone")
        registry.remove(pairing.controllerId.toHex())
        val client = controller(pairing, ackTimeoutMs = 2_000)
        withTimeout(10_000) { client.online.first { it == true } }
        val updates = withTimeout(10_000) { client.press().toList() }
        assertIs<PressUpdate.Failed>(updates.last())
        assertEquals(0, presses.get())
        client.stop()
    }

    @Test
    fun `replayed command is ignored`() = runBlocking {
        val pairing = pair("phone")
        val client = controller(pairing)
        withTimeout(10_000) { client.online.first { it == true } }
        assertIs<PressUpdate.Photo>(withTimeout(20_000) { client.press(delayMs = 100).toList() }.last())
        assertEquals(1, presses.get())

        val cmdTopic = Topics.cmd(deviceHex, pairing.controllerId.toHex())
        val captured = sniffed.filter { it.first == cmdTopic }.map { it.second }
        assertTrue(captured.isNotEmpty())
        for (payload in captured) sniffer.publish(cmdTopic, payload)
        delay(1500)
        assertEquals(1, presses.get(), "replayed press must not trigger the button")
        client.stop()
    }

    @Test
    fun `device going away clears the online flag`() = runBlocking {
        val client = controller(pair("phone"))
        withTimeout(10_000) { client.online.first { it == true } }
        agent.stop()
        withTimeout(10_000) { client.online.first { it == false } }
        client.stop()
    }

    private fun ByteArray.containsSequence(needle: ByteArray): Boolean {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
