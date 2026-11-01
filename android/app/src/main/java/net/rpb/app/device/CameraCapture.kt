package net.rpb.app.device

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.rpb.core.device.Frame
import net.rpb.core.device.FrameSource
import net.rpb.core.device.RowCutter
import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.RowCrop
import net.rpb.core.ocr.SevenSegConfig
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Rear camera via CameraX, bound to the service lifecycle only while in use. [prepare] opens it when a job
 * starts, so exposure and focus settle during the press and its delay; it closes [IDLE_MS] after the last capture.
 * While open, the torch lights the display (ADR 0014). Each frame can also cut a digit row from the full-size
 * image, for the reader (ADR 0017), wherever the agent finds the display (ADR 0018). All state is touched on the
 * main thread only.
 */
class CameraCapture(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val torch: () -> Boolean,
    /** Extra clockwise turn in degrees, so the display reads upright in the photo. */
    private val rotation: () -> Int,
    private val log: (String) -> Unit = {},
    /** Every photo as the camera made it, with the clockwise turn that makes it upright. */
    private val onPhoto: (jpeg: ByteArray, turn: Int) -> Unit = { _, _ -> },
) : FrameSource {
    private val scope = owner.lifecycleScope
    private var provider: ProcessCameraProvider? = null
    private var bound: Deferred<ImageCapture>? = null
    private var boundAt = 0L
    private var users = 0
    private var closer: Job? = null

    override fun prepare() {
        scope.launch {
            open()
            scheduleClose()
        }
    }

    override suspend fun capture(): Frame {
        val (jpeg, sensorRotation) = withContext(Dispatchers.Main) {
            users++
            closer?.cancel()
            try {
                val capture = open().await()
                delay(SETTLE_MS - (SystemClock.elapsedRealtime() - boundAt))
                take(capture)
            } finally {
                users--
                scheduleClose()
            }
        }
        return withContext(Dispatchers.Default) {
            val turn = (sensorRotation + rotation()) % 360
            onPhoto(jpeg, turn)
            val frame = toFrame(jpeg, turn)
            Frame(frame.jpeg, frame.gray, RowCutter { config -> withContext(Dispatchers.Default) { cutRow(jpeg, turn, config) } })
        }
    }

    /** The current binding, or a new one if there is none or the last attempt failed. */
    private fun open(): Deferred<ImageCapture> {
        bound?.takeUnless { it.isCancelled }?.let { return it }
        return scope.async {
            val provider = provider ?: awaitProvider().also { provider = it }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                // Fixed, so the orientation doesn't follow screen rotation or how the phone is tilted.
                .setTargetRotation(Surface.ROTATION_0)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .build()
            provider.unbindAll()
            val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, capture)
            // On for as long as the camera is bound, so exposure settles under the same light as the photo.
            // Unbinding turns it off.
            if (torch() && camera.cameraInfo.hasFlashUnit()) camera.cameraControl.enableTorch(true)
            boundAt = SystemClock.elapsedRealtime()
            capture
        }.also { bound = it }
    }

    private fun scheduleClose() {
        closer?.cancel()
        closer = scope.launch {
            delay(IDLE_MS)
            val current = bound ?: return@launch
            val capture = runCatching { current.await() }.getOrNull() ?: return@launch
            if (users == 0 && bound === current) {
                bound = null
                provider?.unbind(capture)
            }
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            runCatching { future.get() }.fold(cont::resume, cont::resumeWithException)
        }, ContextCompat.getMainExecutor(context))
    }

    private suspend fun take(capture: ImageCapture): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = image.use {
                            val buffer = it.planes[0].buffer
                            ByteArray(buffer.remaining()).also(buffer::get) to it.imageInfo.rotationDegrees
                        }
                        cont.resume(result)
                    }

                    override fun onError(exception: ImageCaptureException) = cont.resumeWithException(exception)
                },
            )
        }

    /** Upright, at most [MAX_SIDE] px on the long side, re-encoded at quality 80, plus a matching greyscale copy. */
    private fun toFrame(jpeg: ByteArray, rotation: Int): Frame {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("could not decode camera JPEG")

        val scale = minOf(1f, MAX_SIDE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
        }
        val upright = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val pixels = IntArray(upright.width * upright.height)
        upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
        val gray = GrayImage.fromArgb(upright.width, upright.height, pixels)
        if (upright !== decoded) decoded.recycle()
        upright.recycle()
        return Frame(out.toByteArray(), gray)
    }

    /**
     * The row [config] calibrates, plus a margin, decoded from the full-size JPEG alone and turned upright. Null if
     * it can't be cut; the reader then uses the scaled photo.
     */
    private fun cutRow(jpeg: ByteArray, rotation: Int, config: SevenSegConfig): RowCrop? = runCatching {
        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        } ?: error("no region decoder")
        try {
            val plan = RowCrop.plan(config, rotation, decoder.width, decoder.height)
            val r = plan.source
            if (r.width.toLong() * r.height > MAX_ROW_PIXELS) {
                log("row crop ${r.width}x${r.height} of ${decoder.width}x${decoder.height} too big; reading the scaled photo")
                return null
            }
            val cut = decoder.decodeRegion(Rect(r.x0, r.y0, r.x1, r.y1), null) ?: error("region not decoded")
            // Quarter turns without filtering move pixels exactly, as GrayImage.rotated does.
            val upright = Bitmap.createBitmap(cut, 0, 0, cut.width, cut.height, Matrix().apply { postRotate(rotation.toFloat()) }, false)
            val pixels = IntArray(upright.width * upright.height)
            upright.getPixels(pixels, 0, upright.width, 0, 0, upright.width, upright.height)
            val gray = GrayImage.fromArgb(upright.width, upright.height, pixels)
            if (upright !== cut) upright.recycle()
            cut.recycle()
            RowCrop(plan.region, gray)
        } finally {
            decoder.recycle()
        }
    }.onFailure { log("row crop failed ($it); reading the scaled photo") }.getOrNull()

    companion object {
        const val MAX_SIDE = 1600
        /** Larger crops (an uncalibrated default row on a big sensor) aren't worth the memory. */
        const val MAX_ROW_PIXELS = 4_000_000L
        const val JPEG_QUALITY = 80
        const val SETTLE_MS = 1000L
        const val IDLE_MS = 10_000L
    }
}
