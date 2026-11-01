package net.rpb.core.ocr

class OtsuResult(val threshold: Int, val meanLow: Float, val meanHigh: Float, val lowFraction: Float) {
    val contrast: Float get() = meanHigh - meanLow
}

object Otsu {
    /** Threshold maximising between-class variance; pixels `<= threshold` form the low class. */
    fun compute(img: GrayImage): OtsuResult {
        val hist = IntArray(256)
        for (p in img.pixels) hist[p.toInt() and 0xFF]++
        val total = img.pixels.size.toDouble()
        val sumAll = (0..255).sumOf { it.toDouble() * hist[it] }

        var weightLow = 0.0
        var sumLow = 0.0
        var best = -1.0
        var threshold = 0
        for (t in 0..255) {
            weightLow += hist[t]
            if (weightLow == 0.0) continue
            val weightHigh = total - weightLow
            if (weightHigh == 0.0) break
            sumLow += t.toDouble() * hist[t]
            val meanLow = sumLow / weightLow
            val meanHigh = (sumAll - sumLow) / weightHigh
            val between = weightLow * weightHigh * (meanLow - meanHigh) * (meanLow - meanHigh)
            if (between > best) {
                best = between
                threshold = t
            }
        }

        var lowCount = 0.0
        var lowSum = 0.0
        for (t in 0..threshold) {
            lowCount += hist[t]
            lowSum += t.toDouble() * hist[t]
        }
        val highCount = total - lowCount
        val meanLow = if (lowCount > 0) lowSum / lowCount else 0.0
        val meanHigh = if (highCount > 0) (sumAll - lowSum) / highCount else meanLow
        return OtsuResult(threshold, meanLow.toFloat(), meanHigh.toFloat(), (lowCount / total).toFloat())
    }
}
