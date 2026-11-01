package net.rpb.core.ocr

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A part of an image, as fractions (0..1) of its width and height. */
data class Region(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** [p], given in the whole image, as a fraction of this region. */
    fun map(p: Point) = Point((p.x - left) / (right - left), (p.y - top) / (bottom - top))

    /** The same part of the image after turning it [degrees] clockwise, as [GrayImage.rotated] turns it. */
    fun turned(degrees: Int): Region = when (Math.floorMod(degrees, 360)) {
        0 -> this
        90 -> Region(1 - bottom, left, 1 - top, right)
        180 -> Region(1 - right, 1 - bottom, 1 - left, 1 - top)
        270 -> Region(top, 1 - right, bottom, 1 - left)
        else -> throw IllegalArgumentException("not a quarter turn: $degrees")
    }
}

/** Pixel rectangle, [x1] and [y1] exclusive. */
data class PixelRect(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
    val width get() = x1 - x0
    val height get() = y1 - y0
}

/**
 * The digit row cut from the camera's full-size image before it is scaled down (ADR 0017), turned upright.
 * [region] is the part of the upright photo it covers, so the calibration maps onto it with [SevenSegConfig.inRegion].
 */
class RowCrop(val region: Region, val gray: GrayImage) {
    /** Where to cut a camera image: the pixels to take from it, and the upright region they cover exactly. */
    class Cut(val source: PixelRect, val region: Region)

    companion object {
        /**
         * Margin around the calibrated row, in digit heights: the reader's own margin (a quarter), its alignment
         * search and slant, and room for the mount to drift.
         */
        const val MARGIN = 0.6f

        /**
         * Where to cut a [width] × [height] camera image that is turned [rotation] degrees clockwise to be upright,
         * for the row calibrated in [config]. Rounded outwards to whole pixels.
         */
        fun plan(config: SevenSegConfig, rotation: Int, width: Int, height: Int): Cut {
            val turns = Math.floorMod(rotation, 360) % 180 != 0
            val upright = if (turns) height.toFloat() / width else width.toFloat() / height
            val wanted = config.rowRegion(upright).turned(360 - rotation)
            val px = PixelRect(
                floor(wanted.left * width).toInt().coerceIn(0, width - 1),
                floor(wanted.top * height).toInt().coerceIn(0, height - 1),
                ceil(wanted.right * width).toInt().coerceIn(1, width),
                ceil(wanted.bottom * height).toInt().coerceIn(1, height),
            )
            val exact = Region(px.x0.toFloat() / width, px.y0.toFloat() / height, px.x1.toFloat() / width, px.y1.toFloat() / height)
            return Cut(px, exact.turned(rotation))
        }

        /** Cuts the row out of a camera image, as `CameraCapture` does with the JPEG, and turns it upright. */
        fun cut(source: GrayImage, rotation: Int, config: SevenSegConfig): RowCrop {
            val plan = plan(config, rotation, source.width, source.height)
            val r = plan.source
            return RowCrop(plan.region, source.cropped(r.x0, r.y0, r.width, r.height).rotated(rotation))
        }
    }
}

/**
 * The calibrated row's bounding box plus [RowCrop.MARGIN] digit heights on every side, clamped to the photo.
 * [aspect] is the photo's width / height, so the margin is the same in pixels across and down.
 */
fun SevenSegConfig.rowRegion(aspect: Float): Region {
    val px = corners.map { Point(it.x * aspect, it.y) }
    val rowH = (hypot(px[3].x - px[0].x, px[3].y - px[0].y) + hypot(px[2].x - px[1].x, px[2].y - px[1].y)) / 2
    val m = rowH * RowCrop.MARGIN
    return Region(
        max(0f, (px.minOf { it.x } - m) / aspect),
        max(0f, px.minOf { it.y } - m),
        min(1f, (px.maxOf { it.x } + m) / aspect),
        min(1f, px.maxOf { it.y } + m),
    )
}

/** This calibration for a crop that covers [region] of the photo. */
fun SevenSegConfig.inRegion(region: Region) = copy(corners = corners.map(region::map))
