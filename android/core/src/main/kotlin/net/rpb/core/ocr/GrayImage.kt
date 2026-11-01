package net.rpb.core.ocr

/** 8-bit greyscale image, row-major. Platform code converts camera frames into this. */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray = ByteArray(width * height)) {
    init {
        require(pixels.size == width * height) { "pixel buffer size mismatch" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    operator fun set(x: Int, y: Int, value: Int) {
        pixels[y * width + x] = value.coerceIn(0, 255).toByte()
    }

    /** Bilinear sample at a sub-pixel position, clamped to the image edges. */
    fun sample(x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = this[x0, y0] * (1 - fx) + this[x1, y0] * fx
        val bottom = this[x0, y1] * (1 - fx) + this[x1, y1] * fx
        return top * (1 - fy) + bottom * fy
    }

    fun boxBlur3(): GrayImage {
        val out = GrayImage(width, height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until width && ny in 0 until height) {
                        sum += this[nx, ny]
                        count++
                    }
                }
                out[x, y] = sum / count
            }
        }
        return out
    }

    /** Turned [degrees] clockwise (a multiple of 90), as `Matrix.postRotate` turns a photo. */
    fun rotated(degrees: Int): GrayImage {
        val turn = Math.floorMod(degrees, 360)
        require(turn % 90 == 0) { "not a quarter turn: $degrees" }
        if (turn == 0) return this
        val out = if (turn == 180) GrayImage(width, height) else GrayImage(height, width)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = pixels[y * width + x]
                when (turn) {
                    90 -> out.pixels[x * out.width + (height - 1 - y)] = v
                    180 -> out.pixels[(height - 1 - y) * width + (width - 1 - x)] = v
                    else -> out.pixels[(width - 1 - x) * out.width + y] = v
                }
            }
        }
        return out
    }

    fun cropped(x: Int, y: Int, w: Int, h: Int): GrayImage {
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= width && y + h <= height) { "crop outside the image" }
        val out = GrayImage(w, h)
        for (row in 0 until h) System.arraycopy(pixels, (y + row) * width + x, out.pixels, row * w, w)
        return out
    }

    /** Box-averaged down by an integer [factor]; the remainder at the right and bottom edges is dropped. */
    fun downscaled(factor: Int): GrayImage {
        require(factor >= 1)
        if (factor == 1) return this
        val out = GrayImage(width / factor, height / factor)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                var sum = 0
                for (dy in 0 until factor) for (dx in 0 until factor) sum += this[x * factor + dx, y * factor + dy]
                out[x, y] = sum / (factor * factor)
            }
        }
        return out
    }

    companion object {
        /** From packed ARGB pixels (e.g. `Bitmap.getPixels`). */
        fun fromArgb(width: Int, height: Int, argb: IntArray): GrayImage {
            val img = GrayImage(width, height)
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                img.pixels[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
            return img
        }
    }
}
