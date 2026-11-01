package net.rpb.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor
import net.rpb.core.ocr.Calibration

/** Controller → device. Every command carries a strictly increasing [seq] and a wall-clock [ts] for replay protection. */
@Serializable
sealed interface Command {
    val seq: Long
    val ts: Long
}

/** [delayMs] is 1000 when absent: older controllers leave out their fixed 1 s, which equals this default (ADR 0019). */
@Serializable
@SerialName("press")
data class Press(override val seq: Long, override val ts: Long, val job: String, val delayMs: Int = 1000) : Command

@Serializable
@SerialName("status?")
data class StatusRequest(override val seq: Long, override val ts: Long) : Command

/**
 * Take a photo and read it, for calibrating remotely. With [pressDelayMs], press first and wait that long, so the
 * display shows a code; absent (older controllers), no press (ADR 0021).
 */
@Serializable
@SerialName("calib_photo")
data class CalibPhoto(override val seq: Long, override val ts: Long, val job: String, val pressDelayMs: Int? = null) : Command

/** Save a calibration made on the controller, then take a photo and read it with it, as `calib_photo` does (ADR 0016). */
@Serializable
@SerialName("set_calib")
data class SetCalibration(
    override val seq: Long,
    override val ts: Long,
    val job: String,
    val calibration: Calibration,
    val pressDelayMs: Int? = null,
) : Command

/** Device → controller. */
@Serializable
sealed interface Event

@Serializable
@SerialName("ack")
data class Ack(val job: String) : Event

@Serializable
@SerialName("busy")
data class Busy(val job: String) : Event

@Serializable
data class DigitResult(val d: String, val conf: Float)

@Serializable
@SerialName("text")
data class TextResult(
    val job: String,
    val text: String,
    val valid: Boolean,
    val confidence: Float,
    val digits: List<DigitResult> = emptyList(),
    /** Device clock (ms) when the Bot confirmed the press; 0 if the job didn't press. */
    val pressedAt: Long = 0,
    /** Device clock (ms) when the photo was taken. */
    val capturedAt: Long = 0,
) : Event

/** The device's calibration as saved, sent before `text` in `calib_photo` and `set_calib` jobs (ADR 0016). */
@Serializable
@SerialName("calib")
data class CalibrationState(val job: String, val calibration: Calibration) : Event

@Serializable
@SerialName("photo")
class PhotoChunk(val job: String, val i: Int, val n: Int, @ByteString val bytes: ByteArray) : Event

@Serializable
@SerialName("error")
data class ErrorEvent(val job: String? = null, val code: String, val msg: String = "") : Event

@Serializable
@SerialName("status")
data class Status(
    val ts: Long,
    val battery: Int,
    val temp: Float,
    val charging: Boolean,
    val botBattery: Int? = null,
    val rssi: Int? = null,
) : Event

object ErrorCodes {
    const val PRESS_FAILED = "press_failed"
    const val CAMERA_FAILED = "camera_failed"
    const val INTERNAL = "internal"
    const val BAD_CALIBRATION = "bad_calibration"
}

object Codec {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    fun encode(command: Command): ByteArray = cbor.encodeToByteArray(Command.serializer(), command)
    fun decodeCommand(bytes: ByteArray): Command = cbor.decodeFromByteArray(Command.serializer(), bytes)
    fun encode(event: Event): ByteArray = cbor.encodeToByteArray(Event.serializer(), event)
    fun decodeEvent(bytes: ByteArray): Event = cbor.decodeFromByteArray(Event.serializer(), bytes)
}
