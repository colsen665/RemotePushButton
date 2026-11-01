package net.rpb.core

import net.rpb.core.controller.BatteryWarning
import net.rpb.core.controller.batteryWarnings
import net.rpb.core.protocol.Status
import kotlin.test.Test
import kotlin.test.assertEquals

class BatteryWarningTest {
    private fun status(battery: Int, temp: Float, charging: Boolean) = Status(0, battery, temp, charging)

    @Test
    fun `no warning while the charge limit holds`() {
        assertEquals(emptyList(), status(80, 30f, true).batteryWarnings())
        assertEquals(emptyList(), status(85, 30f, true).batteryWarnings())
    }

    @Test
    fun `plugged in above the limit means the limit isn't working`() {
        assertEquals(listOf(BatteryWarning.ABOVE_CHARGE_LIMIT), status(86, 30f, true).batteryWarnings())
    }

    @Test
    fun `a full battery off the charger is fine`() {
        assertEquals(emptyList(), status(100, 30f, false).batteryWarnings())
    }

    @Test
    fun `hot battery warns whether or not it is charging`() {
        assertEquals(listOf(BatteryWarning.HOT), status(50, 40f, false).batteryWarnings())
        assertEquals(
            listOf(BatteryWarning.ABOVE_CHARGE_LIMIT, BatteryWarning.HOT),
            status(100, 42.5f, true).batteryWarnings(),
        )
    }

    @Test
    fun `unknown readings don't warn`() {
        assertEquals(emptyList(), status(-1, 0f, true).batteryWarnings())
    }
}
