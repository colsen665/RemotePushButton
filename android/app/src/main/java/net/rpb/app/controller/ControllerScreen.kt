package net.rpb.app.controller

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import net.rpb.app.common.CalibrationEditor
import net.rpb.app.common.PinSettings
import net.rpb.app.rpb
import net.rpb.app.ui.Amber
import net.rpb.app.ui.Bad
import net.rpb.app.ui.Good
import net.rpb.core.controller.BatteryWarning
import net.rpb.core.controller.HistoryEntry
import net.rpb.core.controller.batteryWarnings
import net.rpb.core.device.DeviceAgent
import net.rpb.core.protocol.TextResult
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/** [askScreenLock] shows the screen-lock prompt and passes on whether it was passed (ADR 0033). */
@Composable
fun ControllerScreen(onSwitchMode: () -> Unit, askScreenLock: ((Boolean) -> Unit) -> Unit, vm: ControllerViewModel = viewModel()) {
    LifecycleStartEffect(Unit) {
        vm.onStart()
        onStopOrDispose { vm.onStop() }
    }
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(ui.screenLockNeeded) {
        if (vm.takeScreenLockRequest()) askScreenLock(vm::onScreenLock)
    }
    var calibrating by remember { mutableStateOf(false) }
    if (calibrating) {
        // Remote, so nobody has to touch the mounted device phone and shift it (ADR 0016).
        CalibrationEditor(
            title = "Calibrate the device's display",
            pressDelayMs = vm.selectedDelayMs(),
            takePhoto = { press -> vm.calibrationShot(press = press) },
            save = { calibration, _, press -> vm.calibrationShot(save = calibration, press = press) },
            onDone = { calibrating = false },
        )
    } else {
        ControllerHome(vm, onCalibrate = { calibrating = true }, onSwitchMode = onSwitchMode)
    }
}

@Composable
private fun ControllerHome(vm: ControllerViewModel, onCalibrate: () -> Unit, onSwitchMode: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(vm::addPairing)
    }
    val scan = {
        scanner.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the pairing QR shown on the device phone")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
    var confirmCopy by remember { mutableStateOf<TextResult?>(null) }
    var viewing by remember { mutableStateOf<HistoryEntry?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pinSettings by remember { mutableStateOf(false) }
    var editDelay by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (ui.pairings.isEmpty()) {
            Text("Pair with a device", style = MaterialTheme.typography.headlineSmall)
            Text("On the device phone, open Controllers → Add controller and scan the QR code it shows.")
            Button(onClick = scan, enabled = ui.binding == null) { Text("Scan pairing QR") }
            if (ui.binding != null) Text(ui.progress)
            ui.scanError?.let { Text(it, color = Bad) }
            TextButton(onClick = { pinSettings = true }) { Text("PIN lock") }
            TextButton(onClick = onSwitchMode) { Text("This is the device phone…") }
            return@Column
        }

        if (ui.pairings.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.pairings.forEachIndexed { i, p ->
                    FilterChip(
                        selected = i == ui.selected,
                        onClick = { vm.select(i) },
                        enabled = ui.binding == null,
                        label = { Text(if (!p.bound && p.code != ui.binding) "${p.name} (pair again)" else p.name) },
                    )
                }
            }
        }
        val name = ui.pairings.getOrNull(ui.selected)?.name ?: "Device"
        Text(name, style = MaterialTheme.typography.headlineSmall)
        if (ui.binding != null && ui.binding == ui.pairings.getOrNull(ui.selected)?.code) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("Pairing. Keep the QR code open on the device phone.")
            }
            if (ui.progress.isNotEmpty()) Text(ui.progress)
            return@Column
        }
        if (ui.needsPairing) {
            Text("Pair again", style = MaterialTheme.typography.titleMedium, color = Amber)
            Text(
                "This pairing was made by an older version of the app, or didn't finish. Each controller now has a key " +
                    "that only this phone holds. On the device phone, open Controllers → Add controller and scan the " +
                    "QR code it shows, then remove this one here.",
            )
            Button(onClick = scan) { Text("Scan pairing QR") }
            ui.scanError?.let { Text(it, color = Bad) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmRemove = true }) { Text("Remove this device") }
                TextButton(onClick = onSwitchMode) { Text("Switch mode") }
            }
            return@Column
        }
        val (presence, color) = when {
            !ui.connected -> "Connecting to relay…" to Amber
            ui.online == true -> "Device online" to Good
            ui.online == false -> "Device offline" to Bad
            else -> "Device status unknown" to Amber
        }
        Text(presence, color = color)
        if (ui.forgeries > 0) {
            Text(
                "Answers not signed by the device were dropped. Someone holding this pairing's key may be " +
                    "interfering: revoke this controller on the device and pair again.",
                style = MaterialTheme.typography.bodySmall,
                color = Bad,
            )
        }
        ui.status?.let { s ->
            Text(
                "Phone ${s.battery}%${if (s.charging) " ⚡" else ""} · ${"%.1f".format(s.temp)} °C" +
                    (s.botBattery?.let { " · Bot $it%" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            s.batteryWarnings().forEach { warning ->
                when (warning) {
                    BatteryWarning.ABOVE_CHARGE_LIMIT ->
                        Text("Charging past 80 %: the device phone's charge limit isn't working. Check Battery care on it.", color = Amber)
                    BatteryWarning.HOT ->
                        Text("Device phone battery is hot. Check for sun or poor airflow.", color = Bad)
                }
            }
        }

        Button(
            onClick = vm::press,
            enabled = ui.connected && !ui.running,
            modifier = Modifier.fillMaxWidth().height(96.dp),
        ) {
            if (ui.running) CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary)
            else Text("PRESS", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        }
        val delayMs = ui.pairings.getOrNull(ui.selected)?.delayMs
        if (delayMs != null) {
            TextButton(onClick = { editDelay = true }, enabled = !ui.running) { Text("Photo ${seconds(delayMs)} after the press") }
        }
        if (ui.progress.isNotEmpty()) Text(ui.progress)
        ui.error?.let { Text(it, color = Bad) }

        ui.text?.let { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (t.valid) MaterialTheme.colorScheme.onSurface else Amber,
                    )
                    if (!t.valid) Text("Uncertain read — check the photo", color = Amber)
                    Button(onClick = { if (t.valid) copy(context, t.text) else confirmCopy = t }) { Text("Copy") }
                }
            }
        }
        ui.photo?.let { jpeg ->
            val bitmap = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }
            bitmap?.let { Image(it, contentDescription = "Photo from device", modifier = Modifier.fillMaxWidth()) }
        }

        if (ui.history.isNotEmpty()) {
            Text("History", style = MaterialTheme.typography.titleMedium)
            Text("Kept 5 minutes, cleared when the app locks.", style = MaterialTheme.typography.bodySmall)
            ui.history.forEach { entry ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = entry.hasPhoto) { viewing = entry }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.ts)), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text(entry.text, fontFamily = FontFamily.Monospace, color = if (entry.valid) MaterialTheme.colorScheme.onSurface else Amber)
                    TextButton(onClick = { copy(context, entry.text) }) { Text("Copy") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::calibrationPhoto, enabled = ui.connected && !ui.running) { Text("Photo only") }
            OutlinedButton(onClick = onCalibrate, enabled = ui.connected && !ui.running) { Text("Calibrate") }
            OutlinedButton(onClick = scan, enabled = !ui.running) { Text("Pair another") }
        }
        ui.scanError?.let { Text(it, color = Bad) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { confirmRemove = true }) { Text("Remove this device") }
            TextButton(onClick = { pinSettings = true }) { Text("PIN lock") }
            TextButton(onClick = onSwitchMode) { Text("Switch mode") }
        }
        ui.fingerprint?.let {
            Text(
                "This phone's key fingerprint, as the device lists it under this controller: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (pinSettings) {
        AlertDialog(
            onDismissRequest = { pinSettings = false },
            confirmButton = { TextButton(onClick = { pinSettings = false }) { Text("Close") } },
            title = { Text("PIN lock") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PinSettings(context.rpb.store.pinLock, onWipe = context.rpb::wipeAndRestart)
                }
            },
        )
    }

    // Close dialogs whose code has expired or was cleared on lock (ADR 0026).
    LaunchedEffect(ui.history, ui.text) {
        if (ui.history.none { it.id == viewing?.id }) viewing = null
        if (ui.text == null) confirmCopy = null
    }
    confirmCopy?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmCopy = null },
            confirmButton = { TextButton(onClick = { copy(context, t.text); confirmCopy = null }) { Text("Copy anyway") } },
            dismissButton = { TextButton(onClick = { confirmCopy = null }) { Text("Cancel") } },
            title = { Text("Uncertain read") },
            text = { Text("\"${t.text}\" may be wrong (confidence ${"%.2f".format(t.confidence)}). Compare with the photo before using it.") },
        )
    }
    viewing?.let { entry ->
        val jpeg = remember(entry) { vm.historyPhoto(entry) }
        AlertDialog(
            onDismissRequest = { viewing = null },
            confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
            title = { Text(entry.text, fontFamily = FontFamily.Monospace) },
            text = {
                jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    ?.let { Image(it, contentDescription = "Photo", modifier = Modifier.fillMaxWidth()) }
                    ?: Text("Photo not available")
            },
        )
    }
    if (editDelay) {
        var value by remember { mutableFloatStateOf((ui.pairings.getOrNull(ui.selected)?.delayMs ?: 0) / 1000f) }
        val max = DeviceAgent.MAX_DELAY_MS / 1000f
        AlertDialog(
            onDismissRequest = { editDelay = false },
            confirmButton = { TextButton(onClick = { vm.setDelay((value * 1000).roundToInt()); editDelay = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editDelay = false }) { Text("Cancel") } },
            title = { Text("Wait before the photo") },
            text = {
                Column {
                    Text("The device photographs the display ${seconds((value * 1000).roundToInt())} after the Bot presses. Long enough for the display to show the new code.")
                    // Half-second steps up to the device's limit.
                    Slider(value = value, onValueChange = { value = it }, valueRange = 0f..max, steps = (max * 2).toInt() - 1)
                }
            },
        )
    }
    ui.bound?.let { bound ->
        AlertDialog(
            onDismissRequest = vm::dismissBound,
            confirmButton = { TextButton(onClick = vm::dismissBound) { Text("Done") } },
            title = { Text("Paired with ${bound.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Check that the device phone shows the same key fingerprint:")
                    Text(bound.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("If it shows another, remove this device here and the controller on the device phone, then pair again.")
                }
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            confirmButton = { TextButton(onClick = { vm.removeSelected(); confirmRemove = false }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
            title = { Text("Remove device?") },
            text = { Text("You'll need to scan a new QR code on the device to pair again. Also revoke this controller on the device.") },
        )
    }
}

/** Marks the clip sensitive so Android 13+ hides it in the clipboard preview (ADR 0010). */
private fun copy(context: Context, text: String) {
    val clip = ClipData.newPlainText("Code", text)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13+ shows its own copy confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
