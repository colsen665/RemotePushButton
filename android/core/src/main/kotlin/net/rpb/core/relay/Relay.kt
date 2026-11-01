package net.rpb.core.relay

import kotlinx.coroutines.flow.StateFlow

/**
 * Topics are built from random IDs only, so the broker learns nothing beyond "some pair is talking".
 *
 * - `rpb/{deviceId}/c/{controllerId}/cmd`  controller → device (encrypted)
 * - `rpb/{deviceId}/c/{controllerId}/evt`  device → controller (encrypted)
 * - `rpb/{deviceId}/online`                retained plain "1"/"0", "0" set by the device's last will
 */
object Topics {
    fun cmd(deviceId: String, controllerId: String) = "rpb/$deviceId/c/$controllerId/cmd"
    fun evt(deviceId: String, controllerId: String) = "rpb/$deviceId/c/$controllerId/evt"
    fun allCmds(deviceId: String) = "rpb/$deviceId/c/+/cmd"
    fun online(deviceId: String) = "rpb/$deviceId/online"

    /** Extracts the controller ID from a command topic, or null if the topic isn't one. */
    fun controllerOfCmd(deviceId: String, topic: String): String? {
        val parts = topic.split('/')
        if (parts.size != 5 || parts[0] != "rpb" || parts[1] != deviceId || parts[2] != "c" || parts[4] != "cmd") return null
        return parts[3]
    }

    /** MQTT topic-filter matching (`+` one level, `#` the rest). */
    fun matches(filter: String, topic: String): Boolean {
        val f = filter.split('/')
        val t = topic.split('/')
        for (i in f.indices) {
            when (f[i]) {
                "#" -> return true
                "+" -> if (i >= t.size) return false
                else -> if (i >= t.size || f[i] != t[i]) return false
            }
        }
        return f.size == t.size
    }
}

class Will(val topic: String, val payload: ByteArray, val retain: Boolean = true)

/** A connection to the (untrusted) message relay. Implementations reconnect automatically. */
interface Relay {
    val connected: StateFlow<Boolean>

    fun start()
    fun stop()

    /** Registers a subscription; it is (re)established on every connect. */
    fun subscribe(filter: String, handler: (topic: String, payload: ByteArray) -> Unit)

    /** Runs after every successful (re)connect, after subscriptions are restored. */
    fun onConnected(action: () -> Unit)

    suspend fun publish(topic: String, payload: ByteArray, retain: Boolean = false)
}
