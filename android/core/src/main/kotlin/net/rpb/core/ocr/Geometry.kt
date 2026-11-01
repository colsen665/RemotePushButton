package net.rpb.core.ocr

import kotlinx.serialization.Serializable

@Serializable
data class Point(val x: Float, val y: Float)

/**
 * Projective map from the unit square to a quadrilateral (Heckbert's square-to-quad).
 * Corners are TL, TR, BR, BL — (0,0), (1,0), (1,1), (0,1) in unit-square coordinates.
 */
class Homography(corners: List<Point>) {
    private val a: Float
    private val b: Float
    private val c: Float
    private val d: Float
    private val e: Float
    private val f: Float
    private val g: Float
    private val h: Float

    init {
        require(corners.size == 4) { "need 4 corners" }
        val (x0, y0) = corners[0]
        val (x1, y1) = corners[1]
        val (x2, y2) = corners[2]
        val (x3, y3) = corners[3]
        val dx3 = x0 - x1 + x2 - x3
        val dy3 = y0 - y1 + y2 - y3
        if (dx3 == 0f && dy3 == 0f) {
            g = 0f; h = 0f
        } else {
            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val det = dx1 * dy2 - dx2 * dy1
            g = (dx3 * dy2 - dx2 * dy3) / det
            h = (dx1 * dy3 - dx3 * dy1) / det
        }
        a = x1 - x0 + g * x1
        b = x3 - x0 + h * x3
        c = x0
        d = y1 - y0 + g * y1
        e = y3 - y0 + h * y3
        f = y0
    }

    fun map(u: Float, v: Float): Point {
        val w = g * u + h * v + 1f
        return Point((a * u + b * v + c) / w, (d * u + e * v + f) / w)
    }
}
