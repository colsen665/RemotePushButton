package net.rpb.app.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import net.rpb.core.vault.Vault
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

    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        acquireLocks()
        lifecycleScope.launch {
            val store = rpb.store
            var vault = withContext(Dispatchers.IO) { store.open() }
            if (vault == Vault.CLOSED) vault = waitForUnlock()
            if (vault != Vault.OPEN) return@launch giveUp(vault)
            DeviceRuntime.log("key storage: ${store.keyDescription}")
            startDevice()
        }
    }

    /**
     * Android restarted the service while the phone was locked (a crash), so the data key can't be unwrapped yet
     * (ADR 0028). Ask for an unlock, then go on: the unlock opens the Keystore key for 30 s. Returns once the
     * store is open, or can't be opened without the app.
     */
    private suspend fun waitForUnlock(): Vault {
        val store = rpb.store
        DeviceRuntime.log("waiting for the phone to be unlocked")
        updateNotification(getString(R.string.device_unlock_notification))
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                lifecycleScope.launch(Dispatchers.IO) { store.open() }
            }
        }
        unlockReceiver = receiver
        // The screen coming on also covers a phone unlocked without the keyguard, while the 30 s still run.
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT).apply { addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        // The app on screen may open the store first.
        val vault = store.vault.first { it != Vault.CLOSED }
        unregisterReceiver(receiver)
        unlockReceiver = null
        if (vault == Vault.OPEN) updateNotification(getString(R.string.device_notification))
        return vault
    }

    /**
     * No screen lock, or the keys are lost: only the app can sort that out, and its device screen starts the service
     * again. Leaves an "Open the app" notification and stops, which releases the wake and Wi-Fi locks.
     */
    private fun giveUp(vault: Vault) {
        DeviceRuntime.log(if (vault == Vault.LOST) "the app's keys can't be opened: open the app" else "no screen lock: open the app")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        updateNotification(getString(R.string.device_open_app_notification), ongoing = false)
        stopSelf()
    }

    private fun startDevice() {
        val store = rpb.store
        val settings = store.device.value
        val log: (String) -> Unit = DeviceRuntime::log

        val bot = BotClient(this, { store.device.value.botMac }, { store.device.value.botPassword }, log)
        val diagnostics = Diagnostics(diagnosticsDir(this), { store.device.value.saveDiagnostics })
        val camera = CameraCapture(this, this, { store.device.value.torch }, { store.device.value.photoRotation }, log, diagnostics::photo)
        DeviceRuntime.bot = bot
        DeviceRuntime.camera = camera

        // The MQTT client rejects an empty host, so without a broker there is no relay and no agent.
        // Saving the broker settings restarts the service; every other setting is read when used (ADR 0032).
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
                    override fun save(calibration: Calibration) {
                        store.updateDevice { it.copy(ocr = calibration.config, photoRotation = calibration.rotation) }
                    }

                    override fun anchor() = store.device.value.anchor

                    override fun saveAnchor(anchor: DisplayAnchor) {
                        store.updateDevice { it.copy(anchor = anchor) }
                    }
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
        lifecycleScope.launch {
            // Settings apply without a restart (ADR 0032), so read the battery of a newly set Bot here.
            store.device.map { it.botMac to it.botPassword }.distinctUntilChanged().drop(1).collect {
                DeviceRuntime.botBattery.value = null
                botBatteryAt = 0
                botBatteryRefresh.trySend(Unit)
            }
        }
        DeviceRuntime.running.value = true
        log("device service started")
    }

    override fun onDestroy() {
        unlockReceiver?.let(::unregisterReceiver)
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

    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .build()
    }

    private fun updateNotification(text: String, ongoing: Boolean = true) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, ongoing))
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.device_channel), NotificationManager.IMPORTANCE_LOW))
        val notification = notification(getString(R.string.device_notification))
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
