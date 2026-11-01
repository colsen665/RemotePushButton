package net.rpb.app.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.rpb.core.device.DeviceAgent
import net.rpb.core.device.JobRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide link between [DeviceService] and the device screens (single-process app). */
object DeviceRuntime {
    val running = MutableStateFlow(false)
    val relayConnected = MutableStateFlow(false)
    val lastJob = MutableStateFlow<JobRecord?>(null)
    val botBattery = MutableStateFlow<Int?>(null)

    @Volatile
    var camera: CameraCapture? = null

    @Volatile
    var bot: BotClient? = null

    /** Null while no broker is set. */
    @Volatile
    var agent: DeviceAgent? = null

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _log

    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(line: String) {
        val entry = "${time.format(Date())}  $line"
        android.util.Log.i("RPB", line)
        _log.value = (listOf(entry) + _log.value).take(100)
    }
}
