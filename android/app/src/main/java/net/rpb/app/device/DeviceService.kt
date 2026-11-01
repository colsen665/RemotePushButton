package net.rpb.app.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import net.rpb.app.MainActivity
import net.rpb.app.R
import net.rpb.app.rpb
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.device.CalibrationStore
import net.rpb.core.device.DeviceAgent
import net.rpb.core.device.Diagnostics
import net.rpb.core.device.PairedController
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.DisplayAnchor
import net.rpb.core.protocol.ReplayGuard
import net.rpb.core.relay.MqttRelay
import net.rpb.core.relay.Topics
import net.rpb.core.relay.Will
import java.io.File
import java.util.Base64

/**
 * Keeps the device reachable: relay connection, camera, BLE and the protocol agent.
 * Must be started while the app is in the foreground (camera FGS rule), see ADR 0009.
 */
class DeviceService : LifecycleService() {
    private var agent: DeviceAgent? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val botBatteryRefresh = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var botBatteryAt = 0L

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()

        val store = rpb.store
        val settings = store.device.value
        val log: (String) -> Unit = DeviceRuntime::log

        val bot = BotClient(this, { store.device.value.botMac }, { store.device.value.botPassword }, log)
        val diagnostics = Diagnostics(diagnosticsDir(this), { store.device.value.saveDiagnostics })
        val camera = CameraCapture(this, this, { store.device.value.torch }, { store.device.value.photoRotation }, log, diagnostics::photo)
        DeviceRuntime.bot = bot
        DeviceRuntime.camera = camera

        // The MQTT client rejects an empty host, so without a broker there is no relay and no agent.
        // Saving the broker settings restarts the service.
        val relay = settings.deviceBroker.takeIf { it.host.isNotBlank() }?.let { broker ->
            MqttRelay(
                config = broker,
                clientId = "rpb-dev-" + settings.relayId,
                will = Will(Topics.online(settings.deviceId), DeviceAgent.OFFLINE),
                log = log,
            )
        }
        val agent = relay?.let { r ->
            DeviceAgent(
                deviceId = settings.deviceId.hexToBytes(),
                relay = r,
                registry = { id ->
                    store.controllers.value.firstOrNull { it.id == id }?.let { PairedController(it.id, it.name, Base64.getDecoder().decode(it.key)) }
                },
                replay = ReplayGuard(store.seqStore),
                presser = {
                    bot.press()
                    // The Bot just answered, so a missing or old battery reading is likely to succeed now.
                    if (System.currentTimeMillis() - botBatteryAt > BOT_BATTERY_INTERVAL_MS) botBatteryRefresh.trySend(Unit)
                },
                camera = camera,
                calibration = object : CalibrationStore {
                    override fun load() = store.device.value.let { Calibration(it.ocr, it.photoRotation) }
                    override fun save(calibration: Calibration) =
                        store.updateDevice { it.copy(ocr = calibration.config, photoRotation = calibration.rotation) }

                    override fun anchor() = store.device.value.anchor

                    override fun saveAnchor(anchor: DisplayAnchor) = store.updateDevice { it.copy(anchor = anchor) }
                },
                statusSource = BatteryStatus(this) { DeviceRuntime.botBattery.value },
                scope = lifecycleScope,
                log = log,
                onJob = { record ->
                    DeviceRuntime.lastJob.value = record
                    store.device.value.let { diagnostics.job(record.job, record.controller, Calibration(it.ocr, it.photoRotation), it.anchor) }
                    // Never the code: the log goes to logcat (AGENTS.md). The screen shows it under "Last job".
                    val outcome = record.result?.let { "read ${if (it.valid) "ok" else "invalid"} (confidence ${"%.2f".format(it.confidence)})" } ?: record.error
                    log("job ${record.job} for ${record.controller}: $outcome")
                },
            )
        }
        this.agent = agent
        DeviceRuntime.agent = agent

        lifecycleScope.launch {
            if (relay == null || agent == null) {
                log("broker not configured — set it in Settings")
                return@launch
            }
            agent.start()
            relay.connected.collect { DeviceRuntime.relayConnected.value = it }
        }
        lifecycleScope.launch {
            while (true) {
                val level = bot.battery()
                if (level != null) {
                    DeviceRuntime.botBattery.value = level
                    botBatteryAt = System.currentTimeMillis()
                    log("Bot battery $level%")
                }
                val wait = if (level != null) BOT_BATTERY_INTERVAL_MS else BOT_BATTERY_RETRY_MS
                // After a press, let the job finish its photo before taking the Bot again.
                if (withTimeoutOrNull(wait) { botBatteryRefresh.receive() } != null) delay(BOT_BATTERY_AFTER_PRESS_MS)
            }
        }
        DeviceRuntime.running.value = true
        log("device service started")
    }

    override fun onDestroy() {
        // Clear the online flag before going away; the last will only covers unclean disconnects.
        runBlocking { withTimeoutOrNull(2_000) { agent?.stop() } }
        DeviceRuntime.camera = null
        DeviceRuntime.bot = null
        DeviceRuntime.agent = null
        DeviceRuntime.running.value = false
        DeviceRuntime.relayConnected.value = false
        wakeLock?.release()
        wifiLock?.release()
        DeviceRuntime.log("device service stopped")
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.device_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.device_notification))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rpb:device")
            .apply { acquire() }
        val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = getSystemService(WifiManager::class.java).createWifiLock(wifiMode, "rpb:device").apply { acquire() }
    }

    companion object {
        private const val CHANNEL = "device"
        private const val NOTIFICATION_ID = 1
        private const val BOT_BATTERY_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val BOT_BATTERY_RETRY_MS = 10 * 60 * 1000L
        private const val BOT_BATTERY_AFTER_PRESS_MS = 15_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, DeviceService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DeviceService::class.java))
        }

        /** In app-private storage, so `adb shell run-as net.rpb.app` reaches it on a debuggable build (ADR 0023). */
        fun diagnosticsDir(context: Context) = File(context.filesDir, "diagnostics")
    }
}
