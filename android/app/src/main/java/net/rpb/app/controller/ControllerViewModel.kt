package net.rpb.app.controller

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.MissingFieldException
import net.rpb.app.common.CalibrationShot
import net.rpb.app.common.SigningKeys
import net.rpb.app.common.StoredPairing
import net.rpb.app.common.grayOf
import net.rpb.app.rpb
import net.rpb.core.controller.BindResult
import net.rpb.core.controller.ControllerClient
import net.rpb.core.controller.History
import net.rpb.core.controller.HistoryEntry
import net.rpb.core.controller.PressUpdate
import net.rpb.core.crypto.CommandSignature
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.UnsupportedPairingCode
import net.rpb.core.crypto.toHex
import net.rpb.core.ocr.Calibration
import net.rpb.core.protocol.SeqCounter
import net.rpb.core.protocol.Status
import net.rpb.core.protocol.TextResult
import net.rpb.core.relay.MqttRelay
import net.rpb.core.vault.KeyProblem

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
    /** The pairing code whose signing key is being bound, right after its scan (ADR 0033). */
    val binding: String? = null,
    /** A key the device just bound, for the user to compare its fingerprint with the device screen. */
    val bound: BoundKey? = null,
    /** A signing key needs the screen lock: the screen asks for it and answers [ControllerViewModel.onScreenLock]. */
    val screenLockNeeded: Boolean = false,
    /** The fingerprint of this phone's key for the connected pairing, as the device lists it with the controller. */
    val fingerprint: String? = null,
    /** Answers to the connected pairing dropped as not signed by the device, while this process runs (ADR 0033). */
    val forgeries: Int = 0,
) {
    /** The selected pairing was made before ADR 0033, or its bind didn't finish: it must be made again. */
    val needsPairing: Boolean get() = pairings.getOrNull(selected)?.let { !it.bound && it.code != binding } ?: false
}

data class BoundKey(val name: String, val fingerprint: String)

class ControllerViewModel(app: Application) : AndroidViewModel(app) {
    private val store = app.rpb.store
    private val history = app.rpb.history
    private val forgeries = app.rpb.forgeries
    private val _ui = MutableStateFlow(ControllerUi(pairings = store.pairings.value))
    val ui: StateFlow<ControllerUi> = _ui

    private var client: ControllerClient? = null
    /** The pairing code [client] is for. */
    private var clientCode: String? = null
    /** The controller ID of [client]'s pairing, and how many of its [ControllerClient.forgeries] are in [forgeries]. */
    private var clientId: String? = null
    private var forgeriesCounted = 0
    private var observers: Job? = null
    private var expiry: Job? = null
    private var started = false

    /** The selected device's pairing code, so the selection survives the store closing and opening (ADR 0028). */
    private var selectedCode: String? = null

    /** When the code or photo on screen arrived; it expires like the history (ADR 0026). */
    private var resultAt: Long? = null

    private var screenLockAnswer: CompletableDeferred<Boolean>? = null

    /** A bound pairing not yet saved as bound, because the store closed during the bind (ADR 0028). */
    private var unsavedBound: String? = null

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
                    unsavedBound?.let { code -> pairings.firstOrNull { it.code == code } }?.let { pairing ->
                        // The update comes back through this collector.
                        if (store.updatePairing(pairing.copy(bound = true))) {
                            unsavedBound = null
                            return@collect
                        }
                    }
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
        if (_ui.value.binding != null) return
        show(index)
    }

    private fun show(index: Int) {
        selectedCode = _ui.value.pairings.getOrNull(index)?.code
        resultAt = null
        _ui.update { it.copy(selected = index, text = null, photo = null, error = null, progress = "") }
        if (started) connect()
    }

    /**
     * A scanned pairing QR: creates this phone's signing key for it and binds it on the device (ADR 0033).
     * [ControllerUi.scanError] says why it wasn't added.
     */
    fun addPairing(text: String) {
        if (_ui.value.binding != null) return
        val scanned = text.trim()
        val code = try {
            PairingCode.decode(scanned)
        } catch (e: UnsupportedPairingCode) {
            _ui.update { it.copy(scanError = if (e.tooOld) OLDER_CODE else NEWER_CODE) }
            return
        } catch (e: MissingFieldException) {
            // A version 2 code from before the device's key was added to it (ADR 0033).
            _ui.update { it.copy(scanError = if ("devicePub" in e.missingFields) OLDER_CODE else "Not a valid pairing code") }
            return
        } catch (e: Exception) {
            _ui.update { it.copy(scanError = "Not a valid pairing code") }
            return
        }
        // Scanning a device again keeps the delay set for it.
        val old = store.pairings.value.firstOrNull { it.code == scanned }
        if (old?.bound == true) {
            _ui.update { it.copy(scanError = null) }
            show(store.pairings.value.indexOf(old))
            return
        }
        val pairing = StoredPairing(scanned, code.deviceName.ifBlank { "Device" }, old?.delayMs ?: ControllerClient.DEFAULT_DELAY_MS)
        val alias = SigningKeys.alias(code)
        // The device makes new IDs for every QR. A code that repeats a stored pairing's IDs with another key or broker
        // would sign with that pairing's key, and lose it if the bind failed.
        if (store.pairings.value.any { it.code != scanned && SigningKeys.alias(it.code) == alias }) {
            _ui.update { it.copy(scanError = SAME_IDS) }
            return
        }
        _ui.update { it.copy(binding = scanned, scanError = null, progress = "Creating this phone's key…") }
        // The store may open before the pairing is saved, and must not delete its key meanwhile.
        SigningKeys.hold(alias)
        viewModelScope.launch {
            try {
                addAndBind(scanned, pairing, alias)
            } finally {
                SigningKeys.release(alias)
            }
        }
    }

    private suspend fun addAndBind(scanned: String, pairing: StoredPairing, alias: String) {
        val created = withContext(Dispatchers.IO) { runCatching { SigningKeys.ensure(getApplication(), alias) } }
        created.exceptionOrNull()?.let { e ->
            Log.w("RPB", "signing key not created: ${e.javaClass.simpleName}")
            endBinding("Couldn't create this phone's key for the device: ${e.message}")
            return
        }
        // The store closes when the app is left during the scan (ADR 0028), and the result can arrive before it reopens.
        if (!store.addPairing(pairing)) {
            withContext(Dispatchers.IO) { SigningKeys.delete(alias) }
            endBinding("The app locked during the scan. Unlock it and scan again.")
            return
        }
        val pairings = store.pairings.value
        _ui.update { it.copy(pairings = pairings) }
        show(pairings.indexOfFirst { it.code == scanned })
        when (val result = bind(scanned)) {
            is BindResult.Bound -> {
                Log.i("RPB", "signing key bound")
                markBound(scanned)
                _ui.update { it.copy(binding = null, progress = "", bound = BoundKey(pairing.name, result.fingerprint)) }
            }
            is BindResult.Failed -> {
                Log.i("RPB", "bind failed: ${result.code}")
                forget(scanned)
                endBinding(
                    when (result.code) {
                        ControllerClient.LOCKED -> "The screen lock wasn't passed. Scan the QR again."
                        ControllerClient.MISMATCH -> BOUND_ELSEWHERE
                        else -> "Binding failed: show the QR again on the device. (${result.message})"
                    },
                )
            }
        }
    }

    private fun endBinding(message: String) {
        _ui.update { it.copy(binding = null, progress = "", scanError = message) }
        if (started && client == null) connect()
    }

    /**
     * Binds the new pairing's key once the device is online; if the key needs the screen lock first, asks for it and
     * binds again (ADR 0033).
     */
    private suspend fun bind(code: String): BindResult {
        _ui.update { it.copy(progress = "Waiting for the device…") }
        val c = readyClient(code, online = true) ?: return BindResult.Failed("offline", "the device isn't online")
        _ui.update { it.copy(progress = "Binding this phone's key…") }
        val first = bindOnce(c)
        if (first !is BindResult.Failed || first.code != ControllerClient.LOCKED) return first
        _ui.update { it.copy(progress = "Waiting for the screen lock…") }
        if (!askScreenLock()) return first
        _ui.update { it.copy(progress = "Binding this phone's key…") }
        val again = readyClient(code, online = true) ?: return BindResult.Failed("offline", "the device isn't online")
        return bindOnce(again)
    }

    private suspend fun bindOnce(c: ControllerClient): BindResult = try {
        withContext(Dispatchers.IO) { c.bind() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BindResult.Failed("error", e.message ?: e.toString())
    }

    private fun markBound(code: String) {
        val pairing = store.pairings.value.firstOrNull { it.code == code }
        if (pairing == null || !store.updatePairing(pairing.copy(bound = true))) unsavedBound = code
    }

    fun dismissBound() = _ui.update { it.copy(bound = null) }

    /**
     * The client for the pairing [code] once it is connected to the relay and, with [online], the device is online;
     * null after [READY_WAIT_MS]. After a screen-lock prompt it can be a new client: the store may have closed for it.
     */
    private suspend fun readyClient(code: String, online: Boolean): ControllerClient? = withTimeoutOrNull(READY_WAIT_MS) {
        _ui.first { clientCode == code && it.connected && (!online || it.online == true) }
        client
    }

    /** Asks the screen for the screen-lock prompt (ADR 0033); whether it was passed. */
    private suspend fun askScreenLock(): Boolean {
        val answer = CompletableDeferred<Boolean>()
        screenLockAnswer?.complete(false)
        screenLockAnswer = answer
        _ui.update { it.copy(screenLockNeeded = true) }
        return try {
            withTimeoutOrNull(SCREEN_LOCK_WAIT_MS) { answer.await() } ?: false
        } finally {
            if (screenLockAnswer === answer) {
                screenLockAnswer = null
                _ui.update { it.copy(screenLockNeeded = false) }
            }
        }
    }

    /** True once per request: the screen then shows the prompt, and answers [onScreenLock]. */
    fun takeScreenLockRequest(): Boolean {
        if (!_ui.value.screenLockNeeded) return false
        _ui.update { it.copy(screenLockNeeded = false) }
        return true
    }

    fun onScreenLock(passed: Boolean) {
        screenLockAnswer?.complete(passed)
    }

    fun removeSelected() {
        val pairing = _ui.value.pairings.getOrNull(_ui.value.selected) ?: return
        if (pairing.code == _ui.value.binding) return
        disconnect()
        selectedCode = null
        viewModelScope.launch {
            if (forget(pairing.code)) {
                _ui.update { it.copy(pairings = store.pairings.value, selected = 0, online = null, status = null) }
            }
            if (started && client == null) connect()
        }
    }

    /** Removes the pairing and then its signing key (ADR 0033); false if the store is closed. */
    private suspend fun forget(code: String): Boolean {
        if (clientCode == code) disconnect()
        if (!store.removePairing(code)) return false
        runCatching { PairingCode.decode(code).controllerId.toHex() }.getOrNull()?.let { id -> forgeries.update { it - id } }
        SigningKeys.alias(code)?.let { alias -> withContext(Dispatchers.IO) { runCatching { SigningKeys.delete(alias) } } }
        return true
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
        val code = checkNotNull(clientCode)
        check(!_ui.value.running) { "A press is still running" }
        _ui.update { it.copy(running = true) }
        try {
            var calibration: Calibration? = null
            var text: TextResult? = null
            var photo: ByteArray? = null
            val pressDelayMs = selectedDelayMs().takeIf { press }
            val job: (ControllerClient) -> Flow<PressUpdate> =
                { if (save == null) it.calibrationPhoto(pressDelayMs) else it.setCalibration(save, pressDelayMs) }
            runSigned(code, c, job) { update ->
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
        } catch (e: KeyProblem.Invalidated) {
            error(KEY_GONE)
        } finally {
            _ui.update { it.copy(running = false, progress = "") }
        }
    }

    /**
     * Runs [job] on [first], the client for the pairing [code], passing its updates to [onUpdate]. A job that ends
     * [ControllerClient.LOCKED] asks for the screen lock and runs once more (ADR 0033), on the pairing's client then,
     * new if the store closed for the prompt. The flow runs off the main thread, since signing in StrongBox is slow.
     */
    private suspend fun runSigned(
        code: String,
        first: ControllerClient,
        job: (ControllerClient) -> Flow<PressUpdate>,
        onUpdate: suspend (PressUpdate) -> Unit,
    ) {
        var c = first
        for (attempt in 0..1) {
            var locked = false
            job(c).flowOn(Dispatchers.IO).collect { update ->
                if (attempt == 0 && update is PressUpdate.Failed && update.code == ControllerClient.LOCKED) locked = true else onUpdate(update)
            }
            if (!locked) return
            _ui.update { it.copy(progress = "Waiting for the screen lock…") }
            if (!askScreenLock()) return onUpdate(PressUpdate.Failed(ControllerClient.LOCKED, "the screen lock wasn't passed"))
            _ui.update { it.copy(progress = "Sending…") }
            c = readyClient(code, online = false) ?: return onUpdate(PressUpdate.Failed("offline", "not connected to the relay"))
        }
    }

    private fun run(isCalibration: Boolean, start: (ControllerClient) -> Flow<PressUpdate>) {
        val c = client ?: return
        val code = clientCode ?: return
        if (_ui.value.running) return
        val deviceName = _ui.value.pairings.getOrNull(_ui.value.selected)?.name ?: "Device"
        val delay = seconds(selectedDelayMs())
        resultAt = null
        _ui.update { it.copy(running = true, progress = "Sending…", text = null, photo = null, error = null) }
        viewModelScope.launch {
            var text: TextResult? = null
            var photo: ByteArray? = null
            try {
                runSigned(code, c, start) { update ->
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("RPB", "job failed: ${e.javaClass.simpleName}")
                _ui.update { it.copy(error = if (e is KeyProblem.Invalidated) KEY_GONE else "Failed: ${e.message}") }
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
        // A pairing made before ADR 0033 can't be used, and shows as needing to be made again; a new one connects to bind.
        if (!pairing.bound && pairing.code != _ui.value.binding) return
        val code = runCatching { PairingCode.decode(pairing.code) }.getOrNull() ?: return
        val controllerHex = code.controllerId.toHex()
        val signer = SigningKeys.signer(getApplication(), SigningKeys.alias(code))
        val c = ControllerClient(
            pairing = code,
            signer = signer,
            // Random per connection (sessions are clean), so the ID gives away nothing (ADR 0024).
            relay = MqttRelay(code.broker, "rpb-ctl-" + Keys.random(8).toHex()),
            seq = SeqCounter(store.seqStore, "ctl_$controllerHex"),
            // The status request on connecting signs, which is slow in StrongBox.
            scope = viewModelScope + Dispatchers.IO,
            // No keys, codes, IDs or payloads in these lines; the one to look for is a dropped unsigned answer.
            log = { Log.w("RPB", it) },
        )
        client = c
        clientCode = pairing.code
        clientId = controllerHex
        forgeriesCounted = 0
        _ui.update { it.copy(online = null, status = null, forgeries = forgeries.value[controllerHex] ?: 0) }
        observers = viewModelScope.launch {
            launch { c.connected.collect { v -> _ui.update { it.copy(connected = v) } } }
            launch { c.forgeries.collect { countForgeries() } }
            launch { c.online.collect { v -> _ui.update { it.copy(online = v) } } }
            launch { c.status.collect { v -> _ui.update { it.copy(status = v) } } }
            launch {
                val fingerprint = withContext(Dispatchers.IO) { runCatching { CommandSignature.fingerprint(signer.publicKey) }.getOrNull() }
                _ui.update { it.copy(fingerprint = fingerprint) }
            }
        }
        c.start()
    }

    private fun disconnect() {
        observers?.cancel()
        client?.stop()
        countForgeries()
        client = null
        clientCode = null
        clientId = null
        _ui.update { it.copy(connected = false, fingerprint = null, forgeries = 0) }
    }

    /** Adds what [client] has counted since the last call to its pairing's count, which outlives the client. */
    private fun countForgeries() {
        val id = clientId ?: return
        val counted = client?.forgeries?.value ?: return
        if (counted <= forgeriesCounted) return
        val added = counted - forgeriesCounted
        forgeriesCounted = counted
        forgeries.update { it + (id to (it[id] ?: 0) + added) }
        _ui.update { it.copy(forgeries = forgeries.value[id] ?: 0) }
    }

    override fun onCleared() {
        disconnect()
    }

    private companion object {
        const val READY_WAIT_MS = 30_000L
        const val SCREEN_LOCK_WAIT_MS = 120_000L
        const val OLDER_CODE = "This QR code is from an older version of the app on the device phone. Update the app there, then scan its new QR code."
        const val NEWER_CODE = "This QR code is from a newer version of the app. Update the app on this phone, then scan again."
        const val SAME_IDS = "This QR code repeats a pairing this phone already has, with other contents. Add the controller again on the device phone and scan the new QR code."
        const val BOUND_ELSEWHERE = "The device bound another phone's key to this pairing, so it wasn't added. Unless " +
            "another phone of yours scanned the same QR code, someone holding it is interfering: on the device phone, " +
            "revoke this controller, then add a controller again and scan its new QR code."
        const val KEY_GONE = "This phone's key for the device is gone. Remove the device and pair again."
    }
}

/** A delay in milliseconds as seconds for the screen: "3 s", "2.5 s". */
internal fun seconds(ms: Int) = if (ms % 1000 == 0) "${ms / 1000} s" else "${"%.1f".format(ms / 1000f)} s"
