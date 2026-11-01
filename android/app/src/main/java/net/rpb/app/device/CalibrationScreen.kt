package net.rpb.app.device

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import net.rpb.app.common.CalibrationEditor
import net.rpb.app.common.CalibrationShot
import net.rpb.app.rpb
import net.rpb.core.controller.ControllerClient
import net.rpb.core.ocr.Calibration

/** Calibration on the device phone, with photos taken through the service's camera (ADR 0009). */
@Composable
fun CalibrationScreen(onDone: () -> Unit) {
    val store = LocalContext.current.rpb.store
    CalibrationEditor(
        title = "Calibrate display",
        pressDelayMs = DeviceRuntime.agent?.delayMs ?: ControllerClient.DEFAULT_DELAY_MS,
        takePhoto = { press ->
            val saved = store.device.value.let { Calibration(it.ocr, it.photoRotation) }
            val frame = DeviceRuntime.agent?.calibrationFrame(press) ?: run {
                // No broker set, so no agent, and no controller job to wait for.
                val camera = DeviceRuntime.camera ?: error("service not running")
                if (press) {
                    (DeviceRuntime.bot ?: error("service not running")).press()
                    delay(ControllerClient.DEFAULT_DELAY_MS.toLong())
                }
                camera.capture()
            }
            CalibrationShot(frame.jpeg, frame.gray, saved)
        },
        save = { calibration, photo, _ ->
            DeviceRuntime.agent?.saveCalibration(calibration, photo)
                ?: store.updateDevice { it.copy(ocr = calibration.config, photoRotation = calibration.rotation) }
            null
        },
        onDone = onDone,
    )
}
