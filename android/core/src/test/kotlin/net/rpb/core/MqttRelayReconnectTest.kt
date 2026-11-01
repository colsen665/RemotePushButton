package net.rpb.core

import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.toHex
import net.rpb.core.relay.MqttRelay
import net.rpb.core.relay.Will
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The device's connection drops and comes back, against a broker that wants a password, as EMQX does. */
class MqttRelayReconnectTest {
    private lateinit var broker: Server
    private lateinit var passwords: File
    private lateinit var link: Link
    private lateinit var direct: BrokerConfig
    private lateinit var viaLink: BrokerConfig

    @BeforeTest
    fun setUp() {
        val port = ServerSocket(0).use { it.localPort }
        direct = BrokerConfig("127.0.0.1", port, tls = false, username = "rpb-device", password = UUID.randomUUID().toString())
        val hash = MessageDigest.getInstance("SHA-256").digest(direct.password.toByteArray()).toHex()
        passwords = createTempFile("passwords", ".conf").toFile().apply { writeText("${direct.username}:$hash\n") }
        broker = Server()
        broker.startServer(
            MemoryConfig(
                Properties().apply {
                    setProperty("port", port.toString())
                    setProperty("host", "127.0.0.1")
                    setProperty("allow_anonymous", "false")
                    setProperty("password_file", passwords.absolutePath)
                    setProperty("persistence_enabled", "false")
                },
            ),
        )
        link = Link(port)
        viaLink = direct.copy(port = link.port)
    }

    @AfterTest
    fun tearDown() {
        link.close()
        broker.stopServer()
        passwords.delete()
    }

    @Test
    fun `relay logs in again after the connection drops, and keeps its last will`() = runBlocking {
        val flag = MutableStateFlow<String?>(null)
        val watcher = MqttRelay(direct, "watcher")
        watcher.subscribe(TOPIC) { _, payload -> flag.value = String(payload) }
        watcher.start()
        withTimeout(10_000) { watcher.connected.first { it } }

        val device = MqttRelay(viaLink, "device", Will(TOPIC, "0".toByteArray()))
        device.start()
        withTimeout(10_000) { device.connected.first { it } }
        repeat(2) {
            device.publish(TOPIC, "1".toByteArray(), retain = true)
            withTimeout(10_000) { flag.first { it == "1" } }
            link.cut()
            withTimeout(10_000) { flag.first { it == "0" } }
            withTimeout(20_000) { device.connected.first { !it } }
            withTimeout(20_000) { device.connected.first { it } }
        }
        device.stop()
        watcher.stop()
    }

    @Test
    fun `stopped relay stays stopped while the connection is down`() = runBlocking {
        link.down = true
        val device = MqttRelay(viaLink, "stopped")
        device.start()
        delay(2_000)
        device.stop()
        link.down = false

        delay(5_000)
        assertFalse(device.connected.value, "a stopped relay must not reconnect")
        assertFalse(broker.listConnectedClients().any { it.clientID == "stopped" })
    }

    @Test
    fun `publish before the first connect fails instead of blocking`() {
        link.down = true
        val failed = CountDownLatch(1)
        val device = MqttRelay(viaLink, "never-connected") { if (it.startsWith("relay disconnected")) failed.countDown() }
        device.start()
        assertTrue(failed.await(10, TimeUnit.SECONDS))
        // Waiting to reconnect, the HiveMQ client blocks the calling thread, which no coroutine timeout can interrupt.
        val publish = CompletableFuture.supplyAsync {
            runCatching { runBlocking { device.publish(TOPIC, "0".toByteArray(), retain = true) } }
        }
        assertTrue(publish.get(5, TimeUnit.SECONDS).isFailure)
        device.stop()
    }

    /** Forwards TCP to the broker; [cut] drops every connection without an MQTT DISCONNECT, like a lost network. */
    private class Link(target: Int) : AutoCloseable {
        private val server = ServerSocket(0)
        val port = server.localPort
        private val sockets = CopyOnWriteArrayList<Socket>()

        /** While down, new connections are accepted and closed at once. */
        @Volatile
        var down = false

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    if (down) {
                        client.close()
                        continue
                    }
                    val upstream = Socket("127.0.0.1", target)
                    sockets += client
                    sockets += upstream
                    pipe(client, upstream)
                    pipe(upstream, client)
                }
            }
        }

        private fun pipe(from: Socket, to: Socket) = thread(isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { from.close() }
            runCatching { to.close() }
        }

        fun cut() {
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }

        override fun close() {
            server.close()
            cut()
        }
    }

    private companion object {
        const val TOPIC = "rpb/test/online"
    }
}
