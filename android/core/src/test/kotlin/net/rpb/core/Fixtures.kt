package net.rpb.core

import net.rpb.core.ocr.GrayImage
import net.rpb.core.ocr.Move
import net.rpb.core.ocr.Point
import kotlin.math.cos
import kotlin.math.sin

/** The calibration the scenes from the first rig position share. */
fun rigConfig() = Scenes.rig[0].config()

/** Turned [degrees] clockwise and scaled about [centre], then shifted by [dx], [dy]. */
fun turnAbout(centre: Point, degrees: Float, scale: Float, dx: Float, dy: Float): Move {
    val r = Math.toRadians(degrees.toDouble())
    val a = (scale * cos(r)).toFloat()
    val b = (scale * sin(r)).toFloat()
    return Move(a, b, centre.x + dx - (a * centre.x - b * centre.y), centre.y + dy - (b * centre.x + a * centre.y))
}

/** As if everything in the photo made [move]: what was at p is now at move(p). Bilinear; beyond the edge repeats it. */
fun GrayImage.moved(move: Move): GrayImage {
    val out = GrayImage(width, height)
    val det = move.a * move.a + move.b * move.b
    for (y in 0 until height) for (x in 0 until width) {
        val u = x - move.tx
        val v = y - move.ty
        out[x, y] = sample((move.a * u + move.b * v) / det, (-move.b * u + move.a * v) / det).toInt()
    }
    return out
}
