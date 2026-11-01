package net.rpb.app.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import net.rpb.core.device.StatusSource
import net.rpb.core.protocol.Status

class BatteryStatus(private val context: Context, private val botBattery: () -> Int?) : StatusSource {
    override fun status(): Status {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        @Suppress("DEPRECATION")
        val rssi = context.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi
        return Status(
            ts = System.currentTimeMillis(),
            battery = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            temp = temp,
            charging = plugged,
            botBattery = botBattery(),
            rssi = rssi,
        )
    }
}
