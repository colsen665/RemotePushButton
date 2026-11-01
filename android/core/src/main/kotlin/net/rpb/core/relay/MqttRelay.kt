package net.rpb.core.relay

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.future.await
import net.rpb.core.crypto.BrokerConfig
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * MQTT 3.1.1 relay over TLS (EMQX Cloud in production, any broker in tests).
 * MQTT 3.1.1 rather than 5 keeps the broker choice open (Mosquitto, Moquette, EMQX, ...).
 */
class MqttRelay(
    private val config: BrokerConfig,
    clientId: String,
    private val will: Will? = null,
    private val log: (String) -> Unit = {},
) : Relay {
    private class Subscription(val filter: String, val handler: (String, ByteArray) -> Unit)

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val connectedActions = CopyOnWriteArrayList<() -> Unit>()
    private val _connected = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = _connected

    @Volatile
    private var stopped = false

    // Until its first connection, the HiveMQ client blocks a publishing thread while it waits to reconnect.
    @Volatile
    private var hasConnected = false

    // Login and last will go on the client, not on connect(): automatic reconnects resend only what the client holds.
    private val client: Mqtt3AsyncClient = Mqtt3Client.builder()
        .identifier(clientId)
        .serverHost(config.host)
        .serverPort(config.port)
        .apply { if (config.tls) sslWithDefaultConfig() }
        .apply {
            if (config.username.isNotEmpty()) {
                simpleAuth().username(config.username).password(config.password.toByteArray()).applySimpleAuth()
            }
        }
        .apply {
            if (will != null) {
                willPublish().topic(will.topic).payload(will.payload).qos(MqttQos.AT_LEAST_ONCE).retain(will.retain).applyWillPublish()
            }
        }
        .automaticReconnect()
        .initialDelay(1, TimeUnit.SECONDS)
        .maxDelay(60, TimeUnit.SECONDS)
        .applyAutomaticReconnect()
        .addConnectedListener {
            hasConnected = true
            if (stopped) {
                client.disconnect()
                return@addConnectedListener
            }
            log("relay connected")
            _connected.value = true
            restoreSubscriptions()
        }
        .addDisconnectedListener { ctx ->
            _connected.value = false
            // disconnect() does not cancel a reconnect that is already under way.
            if (stopped) {
                ctx.reconnector.reconnect(false)
                return@addDisconnectedListener
            }
            log("relay disconnected: ${ctx.cause.message}")
        }
        .buildAsync()

    init {
        // One global dispatcher instead of per-subscription callbacks, so re-subscribing after a
        // reconnect never registers duplicate handlers.
        client.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val topic = publish.topic.toString()
            val payload = publish.payloadAsBytes
            for (s in subscriptions) {
                if (Topics.matches(s.filter, topic)) {
                    runCatching { s.handler(topic, payload) }.onFailure { log("handler error on $topic: $it") }
                }
            }
        }
    }

    override fun start() {
        stopped = false
        // Failures are retried by automatic reconnect.
        client.connectWith().cleanSession(true).keepAlive(60).send()
            .whenComplete { _, error -> if (error != null) log("relay connect failed: ${error.message}") }
    }

    override fun stop() {
        stopped = true
        client.disconnect()
        _connected.value = false
    }

    override fun subscribe(filter: String, handler: (topic: String, payload: ByteArray) -> Unit) {
        subscriptions += Subscription(filter, handler)
        if (_connected.value) sendSubscribe(filter)
    }

    override fun onConnected(action: () -> Unit) {
        connectedActions += action
    }

    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) {
        check(hasConnected) { "relay has not connected yet" }
        client.publishWith().topic(topic).payload(payload).qos(MqttQos.AT_LEAST_ONCE).retain(retain).send().await()
    }

    private fun restoreSubscriptions() {
        val futures = subscriptions.map { sendSubscribe(it.filter) }
        java.util.concurrent.CompletableFuture.allOf(*futures.toTypedArray()).whenComplete { _, _ ->
            connectedActions.forEach { action -> runCatching(action).onFailure { log("onConnected error: $it") } }
        }
    }

    private fun sendSubscribe(filter: String) =
        client.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send()
            .whenComplete { _, error -> if (error != null) log("subscribe $filter failed: ${error.message}") }
}
