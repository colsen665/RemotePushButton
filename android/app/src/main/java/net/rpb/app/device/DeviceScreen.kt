package net.rpb.app.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.rpb.app.common.DeviceSettings
import net.rpb.app.common.PinSettings
import net.rpb.app.common.Qr
import net.rpb.app.common.StoredController
import net.rpb.app.rpb
import net.rpb.app.ui.Bad
import net.rpb.app.ui.Good
import net.rpb.app.ui.Section
import net.rpb.core.crypto.BrokerConfig
import net.rpb.core.crypto.Keys
import net.rpb.core.crypto.PairingCode
import net.rpb.core.crypto.hexToBytes
import net.rpb.core.crypto.toHex
import net.rpb.core.device.Diagnostics
import net.rpb.core.ocr.SevenSegReader
import java.util.Base64

private fun devicePermissions(): Array<String> = buildList {
    add(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

// Asked for with the others, but the service runs without it: the Bot is then connected without a scan (ADR 0031).
private fun requestedPermissions(): Array<String> = devicePermissions() + BotClient.scanPermissions()

private fun Context.hasAll(permissions: Array<String>) =
    permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

@Composable
fun DeviceScreen(onSwitchMode: () -> Unit) {
    var calibrating by remember { mutableStateOf(false) }
    if (calibrating) {
        CalibrationScreen(onDone = { calibrating = false })
    } else {
        DeviceHome(onCalibrate = { calibrating = true }, onSwitchMode = onSwitchMode)
    }
}

@Composable
private fun DeviceHome(onCalibrate: () -> Unit, onSwitchMode: () -> Unit) {
    val context = LocalContext.current
    val store = context.rpb.store
    val settings by store.device.collectAsStateWithLifecycle()
    val controllers by store.controllers.collectAsStateWithLifecycle()
    val running by DeviceRuntime.running.collectAsStateWithLifecycle()
    val connected by DeviceRuntime.relayConnected.collectAsStateWithLifecycle()
    val lastJob by DeviceRuntime.lastJob.collectAsStateWithLifecycle()
    val botBattery by DeviceRuntime.botBattery.collectAsStateWithLifecycle()
    val log by DeviceRuntime.logLines.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var permissionsOk by remember { mutableStateOf(context.hasAll(devicePermissions())) }
    var scanOk by remember { mutableStateOf(context.hasAll(BotClient.scanPermissions())) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionsOk = context.hasAll(devicePermissions())
        scanOk = context.hasAll(BotClient.scanPermissions())
    }
    // Start the service from the foreground (required for camera access, ADR 0009).
    LaunchedEffect(permissionsOk) {
        if (!permissionsOk) permissionLauncher.launch(requestedPermissions())
        else if (!DeviceRuntime.running.value) DeviceService.start(context)
    }
    // After an update from a build without the scan, only the scan permission is missing: ask for it on its own.
    LaunchedEffect(Unit) {
        if (permissionsOk && !scanOk) permissionLauncher.launch(requestedPermissions())
    }

    var testMessage by remember { mutableStateOf<String?>(null) }
    var testPhoto by remember { mutableStateOf<ImageBitmap?>(null) }
    var pairingQr by remember { mutableStateOf<Pair<String, ImageBitmap>?>(null) }
    var askName by remember { mutableStateOf(false) }
    var confirmSwitch by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Device: ${settings.name}", style = MaterialTheme.typography.headlineSmall)

        Section("Status") {
            StatusLine("Service", running)
            StatusLine("Relay", connected)
            Text("Bot battery: ${botBattery?.let { "$it%" } ?: "unknown"}")
            if (!permissionsOk) {
                Text("Camera / Bluetooth permission missing", color = Bad)
                Button(onClick = { permissionLauncher.launch(requestedPermissions()) }) { Text("Grant permissions") }
            } else if (!scanOk) {
                Text("Permission to scan for the Bot missing — it may not connect after a reboot.", color = Bad)
                OutlinedButton(onClick = { permissionLauncher.launch(requestedPermissions()) }) { Text("Allow scanning") }
            }
            val power = context.getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                Text("Battery optimisation is on — Android may kill the service.", color = Bad)
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                }) { Text("Disable battery optimisation") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = { DeviceService.stop(context) }) { Text("Stop service") }
                    OutlinedButton(onClick = { DeviceService.stop(context); DeviceService.start(context) }) { Text("Restart") }
                } else {
                    Button(onClick = { DeviceService.start(context) }, enabled = permissionsOk) { Text("Start service") }
                }
            }
            lastJob?.let { job ->
                Text("Last job: ${job.result?.text ?: job.error} for ${job.controller}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Section("Test") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = running, onClick = {
                    testMessage = "Pressing…"
                    scope.launch {
                        testMessage = runCatching { DeviceRuntime.bot?.press() ?: error("service not running") }
                            .fold({ "Press OK" }, { "Press failed: ${it.message}" })
                    }
                }) { Text("Test press") }
                Button(enabled = running, onClick = {
                    testMessage = "Taking photo…"
                    scope.launch {
                        runCatching {
                            val frame = DeviceRuntime.camera?.capture() ?: error("service not running")
                            val result = SevenSegReader(store.device.value.ocr).read(frame.gray)
                            testPhoto = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size).asImageBitmap()
                            "Read: ${result.text} (${if (result.valid) "valid" else "invalid"}, confidence ${"%.2f".format(result.confidence)})"
                        }.fold({ testMessage = it }, { testMessage = "Photo failed: ${it.message}" })
                    }
                }) { Text("Test photo") }
                OutlinedButton(enabled = running, onClick = onCalibrate) { Text("Calibrate") }
            }
            testMessage?.let { Text(it) }
            testPhoto?.let { Image(it, contentDescription = "Test photo", modifier = Modifier.fillMaxWidth()) }
        }

        Section("Controllers") {
            if (controllers.isEmpty()) Text("No controllers paired yet.")
            controllers.forEach { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, Modifier.weight(1f))
                    TextButton(onClick = { store.removeController(c.id) }) { Text("Revoke") }
                }
            }
            Button(enabled = settings.controllerBroker.host.isNotBlank(), onClick = { askName = true }) { Text("Add controller") }
            if (settings.controllerBroker.host.isBlank()) Text("Set the controller broker credentials below first.", style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(settings, onSave = { updated ->
            // Only a broker change restarts: a press sent during the restart times out on the controller (ADR 0032).
            val restart = running && settings.needsRestart(updated)
            store.updateDevice { updated }
            if (!updated.saveDiagnostics) Diagnostics(DeviceService.diagnosticsDir(context), { false }).clear()
            if (restart) {
                DeviceService.stop(context)
                DeviceService.start(context)
            }
        })

        Section("PIN lock") {
            PinSettings(store.pinLock, onWipe = context.rpb::wipeAndRestart)
        }

        Section("Log") {
            log.take(30).forEach { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        }

        TextButton(onClick = { confirmSwitch = true }) { Text("Switch this phone to Controller mode…") }
    }

    if (askName) {
        NameDialog(onDismiss = { askName = false }, onConfirm = { name ->
            askName = false
            val controllerId = Keys.random(Keys.ID_BYTES)
            val key = Keys.random(Keys.MASTER_KEY_BYTES)
            store.addController(StoredController(controllerId.toHex(), name, Base64.getEncoder().encodeToString(key), System.currentTimeMillis()))
            val code = PairingCode(
                deviceId = settings.deviceId.hexToBytes(),
                controllerId = controllerId,
                key = key,
                broker = settings.controllerBroker,
                deviceName = settings.name,
            ).encode()
            pairingQr = name to Qr.encode(code).asImageBitmap()
        })
    }
    pairingQr?.let { (name, qr) ->
        AlertDialog(
            onDismissRequest = { pairingQr = null },
            confirmButton = { TextButton(onClick = { pairingQr = null }) { Text("Done") } },
            title = { Text("Scan with \"$name\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.fillMaxWidth())
                    Text("Anyone who scans this code can press the button. Close it once paired; revoke the controller if the code leaks.")
                }
            },
        )
    }
    if (confirmSwitch) {
        AlertDialog(
            onDismissRequest = { confirmSwitch = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = false
                    DeviceService.stop(context)
                    onSwitchMode()
                }) { Text("Switch") }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = false }) { Text("Cancel") } },
            title = { Text("Leave Device mode?") },
            text = { Text("The service stops and remote presses will no longer work.") },
        )
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Text("$label: ${if (ok) "running" else "stopped"}", color = if (ok) Good else Bad)
}

@Composable
private fun NameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Create QR") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Controller name") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("e.g. Anna's phone") }, singleLine = true) },
    )
}

@Composable
private fun SettingsSection(settings: DeviceSettings, onSave: (DeviceSettings) -> Unit) {
    var name by remember(settings) { mutableStateOf(settings.name) }
    var botMac by remember(settings) { mutableStateOf(settings.botMac) }
    var botPassword by remember(settings) { mutableStateOf(settings.botPassword) }
    var torch by remember(settings) { mutableStateOf(settings.torch) }
    var diagnostics by remember(settings) { mutableStateOf(settings.saveDiagnostics) }
    var host by remember(settings) { mutableStateOf(settings.deviceBroker.host) }
    var port by remember(settings) { mutableStateOf(settings.deviceBroker.port.toString()) }
    var tls by remember(settings) { mutableStateOf(settings.deviceBroker.tls) }
    var deviceUser by remember(settings) { mutableStateOf(settings.deviceBroker.username) }
    var devicePass by remember(settings) { mutableStateOf(settings.deviceBroker.password) }
    var ctrlUser by remember(settings) { mutableStateOf(settings.controllerBroker.username) }
    var ctrlPass by remember(settings) { mutableStateOf(settings.controllerBroker.password) }

    Section("Settings") {
        OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(botMac, { botMac = it }, label = { Text("SwitchBot MAC (AA:BB:CC:DD:EE:FF)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PasswordField(botPassword, { botPassword = it }, "SwitchBot password (optional)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Torch while taking photos", Modifier.weight(1f))
            Switch(torch, { torch = it })
        }
        Text("Broker (MQTT)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            Text("TLS")
            Switch(tls, { tls = it })
        }
        OutlinedTextField(deviceUser, { deviceUser = it }, label = { Text("Device username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PasswordField(devicePass, { devicePass = it }, "Device password")
        OutlinedTextField(ctrlUser, { ctrlUser = it }, label = { Text("Controller username (goes into QR)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        PasswordField(ctrlPass, { ctrlPass = it }, "Controller password (goes into QR)")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(diagnostics, { diagnostics = it })
            Text("Save diagnostics: full-size photos, which show codes, kept on this phone for adb. Off deletes them.")
        }
        Button(onClick = {
            val p = port.toIntOrNull() ?: 8883
            onSave(
                settings.copy(
                    name = name.trim().ifEmpty { settings.name },
                    botMac = botMac.trim().uppercase(),
                    botPassword = botPassword,
                    torch = torch,
                    saveDiagnostics = diagnostics,
                    deviceBroker = BrokerConfig(host, p, tls, deviceUser, devicePass),
                    controllerBroker = BrokerConfig(host, p, tls, ctrlUser, ctrlPass),
                ),
            )
        }) { Text("Save") }
        Text("A broker change restarts the service, and a press sent meanwhile fails on the controller.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
}
