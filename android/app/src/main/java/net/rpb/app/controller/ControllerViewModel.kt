package net.rpb.app.controller

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpb.app.common.CalibrationShot
import net.rpb.app.common.StoredPairing
import net.rpb.app.common.grayOf
import net.rpb.app.rpb
import net.rpb.core.controller.ControllerClient
import net.rpb.core.controller.History
import net.rpb.core.controller.HistoryEntry
import net.rpb.core.controller.PressUpdate
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.toHex
import net.rpb.core.ocr.Calibration
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.MqttRelay

data class ControllerUi(
    val pairings: List<StoredPairing> = emptyList(),
    val selected: Int = 0,
    val connected: Boolean = false,
    val online: Boolean? = null,
    val status: Status? = null,
    val running: Boolean = false,
    val progress: String = "",
    val text: TextResult? = null,
    val photo: ByteArray? = null,
    val error: String? = null,
    /** Why the last scanned pairing QR wasn't added. */
    val scanError: String? = null,
    val history: List<HistoryEntry> = emptyList(),
)

class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.rpb.store
    private val history = app.rpb.history
    private val _ui = MutableStateFlow(ControllerUi(pairings = store.pairings.value))
    val ui: StateFlow<ControllerUi> = _ui

    private var client: ControllerClient? = null
    private var observers: Job? = null
    private var expiry: Job? = null
    private var started = false

    /** The selected device's pairing code, so the selection survives the store closing and opening (ADR 0028). */
    private var selectedCode: String? = null

    /** When the code or photo on screen arrived; it expires like the history (ADR 0026). */
    private var resultAt: Long? = null

    init {
        viewModelScope.launch { history.entries.collect { v -> _ui.update { it.copy(history = v) } } }
        viewModelScope.launch { app.rpb.locks.collect { clearResult() } }
        // Empty while the store is closed (ADR 0028): drop the client, which holds the pairing key.
        viewModelScope.launch {
            store.pairings.collect { pairings ->
                if (pairings.isEmpty()) {
                    _ui.update { it.copy(pairings = pairings) }
                    disconnect()
                } else {
                    val index = pairings.indexOfFirst { it.code == selectedCode }.takeIf { it >= 0 }
                        ?: _ui.value.selected.coerceIn(0, pairings.size - 1)
                    selectedCode = pairings[index].code
                    _ui.update { it.copy(pairings = pairings, selected = index) }
                    if (started && client == null) connect()
                }
            }
        }
    }

    /** Connect only while the screen is visible — no background work on the user's phone. */
    fun onStart() {
        started = true
        expire()
        expiry = viewModelScope.launch {
            while (true) {
                delay(1000)
                expire()
            }
        }
        connect()
    }

    fun onStop() {
        started = false
        expiry?.cancel()
        disconnect()
    }

    private fun expire() {
        history.expire()
        resultAt?.let { if (History.isExpired(it, SystemClock.elapsedRealtime())) clearResult() }
    }

    private fun clearResult() {
        resultAt = null
        _ui.update { it.copy(text = null, photo = null) }
    }

    fun select(index: Int) {
        selectedCode = _ui.value.pairings.getOrNull(index)?.code
        resultAt = null
        _ui.update { it.copy(selected = index, text = null, photo = null, error = null, progress = "") }
        if (started) connect()
    }

    /** A scanned pairing QR; [ControllerUi.scanError] says why it wasn't added. */
    fun addPairing(text: String) {
        val scanned = text.trim()
        val code = runCatching { PairingCode.decode(scanned) }.getOrElse {
            _ui.update { it.copy(scanError = "Not a valid pairing code") }
            return
        }
        // Scanning a device again keeps the delay set for it.
        val old = store.pairings.value.firstOrNull { it.code == scanned }
        // The store closes when the app is left during the scan (ADR 0028), and the result can arrive before it reopens.
        if (!store.addPairing(StoredPairing(scanned, code.deviceName.ifBlank { "Device" }, old?.delayMs ?: ControllerClient.DEFAULT_DELAY_MS))) {
            _ui.update { it.copy(scanError = "The app locked during the scan. Unlock it and scan again.") }
            return
        }
        val pairings = store.pairings.value
        _ui.update { it.copy(pairings = pairings, scanError = null) }
        select(pairings.indexOfFirst { it.code == scanned })
    }

    fun removeSelected() {
        val pairing = _ui.value.pairings.getOrNull(_ui.value.selected) ?: return
        disconnect()
        selectedCode = null
        if (store.removePairing(pairing.code)) {
            _ui.update { it.copy(pairings = store.pairings.value, selected = 0, online = null, status = null) }
        }
        if (started) connect()
    }

    fun press() = run(isCalibration = false) { it.press(delayMs = selectedDelayMs()) }

    fun selectedDelayMs() = _ui.value.pairings.getOrNull(_ui.value.selected)?.delayMs ?: ControllerClient.DEFAULT_DELAY_MS

    /** Sets how long the selected device waits between the press and the photo (ADR 0019). */
    fun setDelay(delayMs: Int) {
        val pairing = _ui.value.pairings.getOrNull(_ui.value.selected) ?: return
        if (store.updatePairing(pairing.copy(delayMs = delayMs))) _ui.update { it.copy(pairings = store.pairings.value) }
    }

    fun calibrationPhoto() = run(isCalibration = true) { it.calibrationPhoto() }

    /**
     * A photo for the calibration editor (ADR 0016); with [save], the device saves that calibration first and
     * reads with it. With [press], the device presses first and waits the delay set for it (ADR 0021). Not added to
     * the history. Throws with a message for the editor to show.
     */
    suspend fun calibrationShot(save: Calibration? = null, press: Boolean = false): CalibrationShot {
        val c = client ?: error("Not connected to the relay")
        check(!_ui.value.running) { "A press is still running" }
        _ui.update { it.copy(running = true) }
        try {
            var calibration: Calibration? = null
            var text: TextResult? = null
            var photo: ByteArray? = null
            val pressDelayMs = selectedDelayMs().takeIf { press }
            val job = if (save == null) c.calibrationPhoto(pressDelayMs) else c.setCalibration(save, pressDelayMs)
            job.collect { update ->
                when (update) {
                    is PressUpdate.Calibrated -> calibration = update.calibration
                    is PressUpdate.Text -> text = update.result
                    is PressUpdate.Photo -> photo = update.jpeg
                    PressUpdate.DeviceBusy -> error("Device is busy with another press")
                    is PressUpdate.Failed -> error("${update.code}: ${update.message}")
                    else -> Unit
                }
            }
            val jpeg = photo ?: error("No photo from the device")
            val state = calibration ?: error("The device's app is too old for remote calibration. Update it.")
            return CalibrationShot(jpeg, withContext(Dispatchers.Default) { grayOf(jpeg) }, state, text.takeIf { save != null })
        } finally {
            _ui.update { it.copy(running = false) }
        }
    }

    private fun run(isCalibration: Boolean, start: (ControllerClient) -> Flow<PressUpdate>) {
        val c = client ?: return
        if (_ui.value.running) return
        val deviceName = _ui.value.pairings.getOrNull(_ui.value.selected)?.name ?: "Device"
        val delay = seconds(selectedDelayMs())
        resultAt = null
        _ui.update { it.copy(running = true, progress = "Sending…", text = null, photo = null, error = null) }
        viewModelScope.launch {
            var text: TextResult? = null
            var photo: ByteArray? = null
            start(c).collect { update ->
                when (update) {
                    is PressUpdate.Sent -> _ui.update { it.copy(progress = "Waiting for device…") }
                    PressUpdate.Acked -> _ui.update { it.copy(progress = if (isCalibration) "Taking photo…" else "Pressing, waiting $delay…") }
                    is PressUpdate.Calibrated -> Unit
                    is PressUpdate.Text -> {
                        text = update.result
                        resultAt = resultAt ?: SystemClock.elapsedRealtime()
                        _ui.update { it.copy(text = update.result, progress = "Receiving photo…") }
                    }
                    is PressUpdate.PhotoProgress -> _ui.update { it.copy(progress = "Receiving photo ${update.received}/${update.total}…") }
                    is PressUpdate.Photo -> {
                        photo = update.jpeg
                        resultAt = resultAt ?: SystemClock.elapsedRealtime()
                        _ui.update { it.copy(photo = update.jpeg, progress = "") }
                    }
                    PressUpdate.DeviceBusy -> _ui.update { it.copy(error = "Device is busy with another press") }
                    is PressUpdate.Failed -> _ui.update { it.copy(error = "${update.code}: ${update.message}") }
                }
            }
            text?.let { t ->
                history.add(System.currentTimeMillis(), deviceName, t.text, t.valid, t.confidence, photo)
            }
            _ui.update { it.copy(running = false, progress = "") }
        }
    }

    fun historyPhoto(entry: HistoryEntry): ByteArray? = history.photo(entry)

    private fun connect() {
        disconnect()
        val pairing = _ui.value.pairings.getOrNull(_ui.value.selected) ?: return
        val code = runCatching { PairingCode.decode(pairing.code) }.getOrNull() ?: return
        val controllerHex = code.controllerId.toHex()
        val c = ControllerClient(
            pairing = code,
            // Random per connection (sessions are clean), so the ID gives away nothing (ADR 0024).
            relay = MqttRelay(code.broker, "rpb-ctl-" + Keys.random(8).toHex()),
            seq = SeqCounter(store.seqStore, "ctl_$controllerHex"),
            scope = viewModelScope,
        )
        client = c
        observers = viewModelScope.launch {
            launch { c.connected.collect { v -> _ui.update { it.copy(connected = v) } } }
            launch { c.online.collect { v -> _ui.update { it.copy(online = v) } } }
            launch { c.status.collect { v -> _ui.update { it.copy(status = v) } } }
        }
        c.start()
    }

    private fun disconnect() {
        observers?.cancel()
        client?.stop()
        client = null
        _ui.update { it.copy(connected = false) }
    }

    override fun onCleared() {
        disconnect()
    }
}

/** A delay in milliseconds as seconds for the screen: "3 s", "2.5 s". */
internal fun seconds(ms: Int) = if (ms % 1000 == 0) "${ms / 1000} s" else "${"%.1f".format(ms / 1000f)} s"
