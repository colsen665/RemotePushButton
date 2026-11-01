package net.rpb.app.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rpb.app.controller.seconds
import net.rpb.app.ui.Amber
import net.rpb.app.ui.Good
import net.rpb.core.ocr.Calibration
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Point
import net.rpb.core.ocr.Polarity
import net.rpb.core.ocr.SevenSegCalibrator
import net.rpb.core.ocr.SevenSegConfig
import net.rpb.core.ocr.SevenSegLocator
import net.rpb.core.ocr.SevenSegReader
import net.rpb.core.ocr.SevenSegResult
import net.rpb.core.protocol.TextResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A photo to calibrate on, as the device took it: [Calibration.rotation] is already applied, and [calibration]
 * is what the device had saved then. After a remote save, [deviceRead] is the device's own read with it.
 */
class CalibrationShot(
    val jpeg: ByteArray,
    val gray: GrayImage,
    val calibration: Calibration,
    val deviceRead: TextResult? = null,
)

/**
 * Calibration on still photos (ADRs 0009, 0013, 0016), on the device itself or from a controller over the relay.
 * The user enters the code shown and Find digits locates the digit row, the turn and the polarity. Corners,
 * Refine and the sliders stay under "Adjust by hand". [save] gets the shot turned as calibrated, so the device can
 * anchor the display on it (ADR 0022), and returns a new shot to keep editing (remote), or null when calibration is
 * finished. Both take whether to press the button first and wait [pressDelayMs], so the display shows a code for
 * the photo (ADR 0021).
 */
@Composable
fun CalibrationEditor(
    title: String,
    pressDelayMs: Int,
    takePhoto: suspend (press: Boolean) -> CalibrationShot,
    save: suspend (Calibration, photo: GrayImage, press: Boolean) -> CalibrationShot?,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var shot by remember { mutableStateOf<CalibrationShot?>(null) }
    // Extra clockwise turn of the shot, tried here before saving it into the device's photo rotation.
    var turn by remember { mutableIntStateOf(0) }
    var config by remember { mutableStateOf<SevenSegConfig?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }
    var press by remember { mutableStateOf(true) }

    /** Keeps the rotation the user has chosen, whatever rotation the new shot was taken with. */
    fun accept(next: CalibrationShot, saved: Boolean) {
        val absolute = shot?.let { it.calibration.rotation + turn } ?: next.calibration.rotation
        turn = Math.floorMod(absolute - next.calibration.rotation, 360)
        if (config == null || saved) config = next.calibration.config
        shot = next
    }

    fun perform(label: String, action: suspend () -> Unit) {
        busy = label
        error = null
        notice = null
        scope.launch {
            runCatching { action() }.onFailure { error = it.message ?: it.toString() }
            busy = null
        }
    }

    fun retake() = perform(if (press) "Pressing, then taking a photo…" else "Taking photo…") { accept(takePhoto(press), saved = false) }
    LaunchedEffect(Unit) { retake() }
    BackHandler(onBack = onDone)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Make sure the display shows a code, enter it and tap Find digits. The app finds the digits, " +
                    "which way up they are and the display type, then tap Save.",
                style = MaterialTheme.typography.bodySmall,
            )
            error?.let { Text(it, color = Amber) }
            notice?.let { Text(it, color = Good) }

            val s = shot
            val c = config
            if (s != null && c != null) {
                val gray = remember(s, turn) { s.gray.rotated(turn) }
                val bitmap = remember(s, turn) { decodeTurned(s.jpeg, turn) }
                // As the device reads, with the alignment search; the shift shows how far the calibration is off.
                var result by remember { mutableStateOf<SevenSegResult?>(null) }
                LaunchedEffect(c, gray) { result = withContext(Dispatchers.Default) { SevenSegReader(c).read(gray) } }
                result?.let { r ->
                    Text(r.text, fontFamily = FontFamily.Monospace, fontSize = 40.sp, color = if (r.valid) Good else Amber)
                    val shift = maxOf(abs(r.shift.x), abs(r.shift.y))
                    Text(
                        "${if (r.valid) "Valid" else "Not valid"} · confidence ${"%.2f".format(r.confidence)} · " +
                            "re-aligned by ${(shift * 100).roundToInt()}% of the digit height",
                    )
                }
                CornerEditor(bitmap, gray.width, gray.height, c, result?.digits?.map { it.pattern }.orEmpty()) {
                    config = c.copy(corners = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        code,
                        { code = it.filter(Char::isDigit).take(Calibration.MAX_DIGITS) },
                        label = { Text("Code shown") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Button(enabled = busy == null && code.isNotEmpty(), onClick = {
                        perform("Finding the digits…") {
                            // The current turn first: after the first calibration it is usually right.
                            val turns = listOf(turn) + listOf(0, 90, 270, 180).filter { it != turn }
                            val located = withContext(Dispatchers.Default) {
                                SevenSegLocator.locate(s.gray, code, c.copy(digits = code.length), turns)
                            } ?: error("Didn't find that code in the photo. Check the code and the focus, retake the photo, or adjust by hand.")
                            turn = located.turn
                            config = located.config
                            if (located.upsideDownToo) {
                                error = "This code reads the same upside down, so it can't tell which way up the display is. " +
                                    "Check the photo, or use a code with a 1, 3, 4 or 7."
                            } else {
                                notice = "Found. Check the read above, then Save."
                            }
                        }
                    }) { Text("Find digits") }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(press, { press = it })
                Text("Press the button first, then wait ${seconds(pressDelayMs)}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null && s != null && c != null, onClick = {
                    val calibration = Calibration(c!!, Math.floorMod(s!!.calibration.rotation + turn, 360))
                    val problem = calibration.problem()
                    if (problem != null) {
                        error = "Can't save: $problem"
                    } else {
                        val photo = s.gray.rotated(turn)
                        perform("Saving…") {
                            val next = save(calibration, photo, press)
                            if (next == null) {
                                onDone()
                            } else {
                                accept(next, saved = true)
                                notice = next.deviceRead?.let { "Saved on the device. It reads ${it.text}${if (it.valid) "" else " (not valid)"}." }
                                    ?: "Saved on the device."
                            }
                        }
                    }
                }) { Text("Save") }
                OutlinedButton(enabled = busy == null, onClick = ::retake) { Text("Retake photo") }
                TextButton(onClick = onDone) { Text("Close") }
            }

            TextButton(onClick = { manual = !manual }) { Text(if (manual) "Hide manual adjustment" else "Adjust by hand…") }
            if (manual && s != null && c != null) {
                Text(
                    "Drag the yellow corners roughly around the digit row (pinch to zoom), enter the code and tap Refine.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null && code.isNotEmpty(), onClick = {
                        val gray = s.gray.rotated(turn)
                        perform("Refining…") {
                            config = withContext(Dispatchers.Default) {
                                SevenSegCalibrator.refine(listOf(gray), c.copy(digits = code.length), expected = listOf(code))
                            }
                        }
                    }) { Text("Refine") }
                    OutlinedButton(onClick = {
                        // The corners belong to the old orientation.
                        turn = (turn + 90) % 360
                        config = c.copy(corners = SevenSegConfig().corners)
                    }) { Text("Turn 90°") }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Digits: ${c.digits}", Modifier.weight(1f))
                    OutlinedButton(onClick = { if (c.digits > 1) config = c.copy(digits = c.digits - 1) }) { Text("−") }
                    OutlinedButton(onClick = { if (c.digits < Calibration.MAX_DIGITS) config = c.copy(digits = c.digits + 1) }) { Text("+") }
                }
                LabeledSlider("Slant", c.slant, -0.3f..0.3f) { config = c.copy(slant = it) }
                LabeledSlider("Segment thickness", c.segmentThickness, 0.1f..0.4f) { config = c.copy(segmentThickness = it) }
                LabeledSlider("Digit gap", c.digitGap, 0f..0.5f) { config = c.copy(digitGap = it) }
                LabeledSlider("On threshold", c.onThreshold, 0.3f..0.7f) { config = c.copy(onThreshold = it) }
                LabeledSwitch("Light segments on dark (LED)", c.polarity == Polarity.LIGHT_ON_DARK) {
                    config = c.copy(polarity = if (it) Polarity.LIGHT_ON_DARK else Polarity.DARK_ON_LIGHT)
                }
                LabeledSwitch("Multiplexed LED (combine frames)", c.ledMultiplex) { config = c.copy(ledMultiplex = it) }
            }
        }
        busy?.let { BusyOverlay(it) }
    }
}

/** Covers the editor while [step] runs, so it shows wherever the page is scrolled, and holds taps until it ends. */
@Composable
private fun BusyOverlay(step: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Card {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(step, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}

/** The photo turned [turn] degrees clockwise, as [GrayImage.rotated] turns the grey copy. */
private fun decodeTurned(jpeg: ByteArray, turn: Int): ImageBitmap {
    val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("could not decode photo")
    if (turn == 0) return decoded.asImageBitmap()
    val turned = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    decoded.recycle()
    return turned.asImageBitmap()
}

/** Greyscale copy of a JPEG for the reader, as `CameraCapture` makes it on the device. */
fun grayOf(jpeg: ByteArray): GrayImage {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("could not decode photo")
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return GrayImage.fromArgb(bitmap.width, bitmap.height, pixels).also { bitmap.recycle() }
}

private const val MAX_ZOOM = 8f

/** View transform of the corner editor: the photo is drawn [scale] times the box size, shifted by [offset] px. */
private data class Zoom(val scale: Float = 1f, val offset: Offset = Offset.Zero) {
    fun toScreen(p: Point, box: Size) = Offset(offset.x + p.x * box.width * scale, offset.y + p.y * box.height * scale)

    /** Zooms by [factor] around [centroid] and moves by [pan], keeping the photo covering the box. */
    fun transformed(centroid: Offset, factor: Float, pan: Offset, box: Size): Zoom {
        if (!centroid.isSpecified) return this
        val s = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val o = centroid - (centroid - offset) * (s / scale) + pan
        return Zoom(s, Offset(o.x.coerceIn(box.width * (1 - s), 0f), o.y.coerceIn(box.height * (1 - s), 0f)))
    }
}

/**
 * Photo with the digit overlay and draggable corners. One finger on a corner moves it; two fingers zoom and pan,
 * and once zoomed one finger off a corner pans too. Unzoomed, a drag off the corners scrolls the page.
 */
@Composable
private fun CornerEditor(
    bitmap: ImageBitmap,
    width: Int,
    height: Int,
    config: SevenSegConfig,
    patterns: List<Int>,
    onCorners: (List<Point>) -> Unit,
) {
    val overlay = remember(config, width, height) { SevenSegReader(config).overlay(width, height) }
    // A turned photo starts unzoomed; a retake keeps the zoom.
    var zoom by remember(width, height) { mutableStateOf(Zoom()) }
    // The gesture handler outlives recompositions; always read the latest corners/callback.
    val corners by rememberUpdatedState(config.corners)
    val updateCorners by rememberUpdatedState(onCorners)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(width.toFloat() / height)
            .clipToBounds()
            .pointerInput(width, height) {
                awaitEachGesture {
                    val box = size.toSize()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val touch = 48.dp.toPx()
                    var corner = corners.indices
                        .map { i -> i to (zoom.toScreen(corners[i], box) - down.position).getDistance() }
                        .filter { it.second < touch }
                        .minByOrNull { it.second }?.first ?: -1
                    var transforming = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } > 1) {
                            corner = -1
                            transforming = true
                        } else if (corner < 0 && zoom.scale > 1f) {
                            transforming = true
                        }
                        if (transforming) {
                            zoom = zoom.transformed(event.calculateCentroid(), event.calculateZoom(), event.calculatePan(), box)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (corner >= 0) {
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.positionChanged()) {
                                // Read before consuming: a consumed change reports no movement.
                                val d = change.positionChange()
                                change.consume()
                                val c = corners[corner]
                                val moved = Point(
                                    (c.x + d.x / (box.width * zoom.scale)).coerceIn(0f, 1f),
                                    (c.y + d.y / (box.height * zoom.scale)).coerceIn(0f, 1f),
                                )
                                updateCorners(corners.toMutableList().also { it[corner] = moved })
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Calibration photo" }) {
            withTransform({
                translate(zoom.offset.x, zoom.offset.y)
                scale(zoom.scale, zoom.scale, Offset.Zero)
            }) {
                drawImage(bitmap, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
            }
            val sx = size.width * zoom.scale / width
            val sy = size.height * zoom.scale / height
            for (seg in overlay) {
                val on = patterns.getOrNull(seg.digit)?.let { it and (1 shl seg.segment) != 0 } ?: false
                val path = Path().apply {
                    seg.points.forEachIndexed { i, p ->
                        val x = zoom.offset.x + p.x * sx
                        val y = zoom.offset.y + p.y * sy
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                }
                drawPath(path, if (on) Color(0xAA00E676) else Color(0x66FF1744), style = Stroke(width = 2f))
            }
            val quad = Path().apply {
                corners.forEachIndexed { i, p ->
                    val o = zoom.toScreen(p, size)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
                close()
            }
            drawPath(quad, Color.Yellow, style = Stroke(width = 3f))
            corners.forEach { p ->
                drawCircle(Color.Yellow, radius = 14f, center = zoom.toScreen(p, size), style = Stroke(width = 4f))
            }
        }
        if (zoom.scale > 1f) {
            FilledTonalButton(onClick = { zoom = Zoom() }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Text("Reset zoom")
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
