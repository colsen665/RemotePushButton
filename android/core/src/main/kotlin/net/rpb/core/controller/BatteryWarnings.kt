package net.rpb.core.controller

import net.rpb.core.protocol.Status

enum class BatteryWarning { ABOVE_CHARGE_LIMIT, HOT }

/** Above this level while plugged in, the device's 80 % charge limit (docs/setup.md) isn't holding. */
const val MAX_PLUGGED_BATTERY = 85

/** Battery temperature (°C) from which ageing speeds up noticeably. */
const val MAX_BATTERY_TEMP = 40f

fun Status.batteryWarnings(): List<BatteryWarning> = buildList {
    if (charging && battery > MAX_PLUGGED_BATTERY) add(BatteryWarning.ABOVE_CHARGE_LIMIT)
    if (temp >= MAX_BATTERY_TEMP) add(BatteryWarning.HOT)
}
