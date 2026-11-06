package com.airkeys

import android.graphics.PointF

/** One key on the virtual keyboard, in key units (a normal letter key is 1 x 1). */
data class Key(val label: String, val row: Int, val u0: Float, val u1: Float) {
    val centerU: Float get() = (u0 + u1) / 2f
}

/** A 15-unit-wide QWERTY layout, with or without the number row. */
class Board(val numbers: Boolean) {
    val keys: List<Key>
    val rows: Int

    init {
        val all = if (numbers) listOf(NUM_ROW) + LETTER_ROWS else LETTER_ROWS
        val out = ArrayList<Key>()
        all.forEachIndexed { r, row ->
            var u = 0f
            for ((label, w) in row) {
                out.add(Key(label, r, u, u + w))
                u += w
            }
        }
        keys = out
        rows = all.size
    }

    fun keyAt(u: Float, v: Float): Key? {
        if (u < 0f || v < 0f || u >= COLS || v >= rows.toFloat()) return null
        val r = v.toInt()
        return keys.firstOrNull { it.row == r && u >= it.u0 && u < it.u1 }
    }

    companion object {
        const val COLS = 15f

        val NUM_ROW: List<Pair<String, Float>> = listOf(
            "`" to 1f, "1" to 1f, "2" to 1f, "3" to 1f, "4" to 1f, "5" to 1f, "6" to 1f,
            "7" to 1f, "8" to 1f, "9" to 1f, "0" to 1f, "-" to 1f, "=" to 1f, "⌫" to 2f
        )

        val LETTER_ROWS: List<List<Pair<String, Float>>> = listOf(
            listOf(
                "Tab" to 1.5f, "Q" to 1f, "W" to 1f, "E" to 1f, "R" to 1f, "T" to 1f, "Y" to 1f,
                "U" to 1f, "I" to 1f, "O" to 1f, "P" to 1f, "[" to 1f, "]" to 1f, "\\" to 1.5f
            ),
            listOf(
                "Caps" to 1.75f, "A" to 1f, "S" to 1f, "D" to 1f, "F" to 1f, "G" to 1f, "H" to 1f,
                "J" to 1f, "K" to 1f, "L" to 1f, ";" to 1f, "'" to 1f, "Enter" to 2.25f
            ),
            listOf(
                "Shift" to 2.25f, "Z" to 1f, "X" to 1f, "C" to 1f, "V" to 1f, "B" to 1f, "N" to 1f,
                "M" to 1f, "," to 1f, "." to 1f, "/" to 1f, "Shift" to 2.75f
            ),
            listOf(
                "Ctrl" to 1.25f, "Win" to 1.25f, "Alt" to 1.25f, "Space" to 6.25f,
                "Alt" to 1.25f, "Fn" to 1.25f, "Menu" to 1.25f, "Ctrl" to 1.25f
            )
        )

        val HOME_KEYS = setOf("A", "S", "D", "F", "J", "K", "L", ";")
    }
}

/** Projective transform between the unit square and an arbitrary quad (for desk perspective). */
class Homography(private val m: DoubleArray) {

    fun map(x: Float, y: Float): PointF {
        val w = m[6] * x + m[7] * y + m[8]
        return PointF(
            ((m[0] * x + m[1] * y + m[2]) / w).toFloat(),
            ((m[3] * x + m[4] * y + m[5]) / w).toFloat()
        )
    }

    fun inverse(): Homography {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val ca = e * i - f * h
        val cb = -(d * i - f * g)
        val cc = d * h - e * g
        val cd = -(b * i - c * h)
        val ce = a * i - c * g
        val cf = -(a * h - b * g)
        val cg = b * f - c * e
        val ch = -(a * f - c * d)
        val ci = a * e - b * d
        val det = a * ca + b * cb + c * cc
        return Homography(
            doubleArrayOf(
                ca / det, cd / det, cg / det,
                cb / det, ce / det, ch / det,
                cc / det, cf / det, ci / det
            )
        )
    }

    companion object {
        /** corners = [TLx, TLy, TRx, TRy, BRx, BRy, BLx, BLy] -> (0,0) (1,0) (1,1) (0,1) */
        fun squareToQuad(c: FloatArray): Homography {
            val x0 = c[0].toDouble(); val y0 = c[1].toDouble()
            val x1 = c[2].toDouble(); val y1 = c[3].toDouble()
            val x2 = c[4].toDouble(); val y2 = c[5].toDouble()
            val x3 = c[6].toDouble(); val y3 = c[7].toDouble()
            val dx1 = x1 - x2; val dx2 = x3 - x2; val dx3 = x0 - x1 + x2 - x3
            val dy1 = y1 - y2; val dy2 = y3 - y2; val dy3 = y0 - y1 + y2 - y3
            val det = dx1 * dy2 - dx2 * dy1
            var g = 0.0
            var h = 0.0
            if (Math.abs(det) > 1e-12) {
                g = (dx3 * dy2 - dx2 * dy3) / det
                h = (dx1 * dy3 - dx3 * dy1) / det
            }
            return Homography(
                doubleArrayOf(
                    x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
                    y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
                    g, h, 1.0
                )
            )
        }
    }
}
