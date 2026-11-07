package com.motionpuppet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.max

/**
 * Live view while capturing: the camera picture (optional, for framing — never
 * recorded) with the tracked skeletons drawn over it.
 */
class SkeletonView(ctx: Context) : View(ctx) {

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * density }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
    }
    private val dst = RectF()

    @Volatile var frame: Bitmap? = null
    @Volatile var people: List<PersonPose> = emptyList()
    @Volatile var showCamera = true
    @Volatile var mirror = false
    @Volatile var aspect = 3f / 4f
    @Volatile var countdown = 0
    @Volatile var recording = false
    var rightInset = 0f

    private val colors = intArrayOf(0xFF5EEAD4.toInt(), 0xFFF472B6.toInt())
    private val links = intArrayOf(
        11, 12, 11, 13, 13, 15, 12, 14, 14, 16, 15, 19, 16, 20,
        11, 23, 12, 24, 23, 24, 23, 25, 25, 27, 24, 26, 26, 28,
        27, 29, 29, 31, 27, 31, 28, 30, 30, 32, 28, 32, 0, 7, 0, 8
    )

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF151A24.toInt())
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        // Fill the view (crop the picture's edges).
        val s = max(vw / aspect, vh)
        val w = aspect * s
        val l = (vw - rightInset) / 2f - w / 2f
        dst.set(l.coerceIn(vw - w, 0f), (vh - s) / 2f, l.coerceIn(vw - w, 0f) + w, (vh - s) / 2f + s)

        val bmp = frame
        if (showCamera && bmp != null && !bmp.isRecycled) {
            c.save()
            if (mirror) c.scale(-1f, 1f, dst.centerX(), dst.centerY())
            bmpPaint.alpha = 150
            c.drawBitmap(bmp, null, dst, bmpPaint)
            c.restore()
        }

        for (p in people) {
            val col = colors[p.id.coerceIn(0, 1)]
            line.color = col
            dot.color = col
            val n = p.norm
            var i = 0
            while (i < links.size) {
                val a = links[i]
                val b = links[i + 1]
                if (p.vis[a] > 0.4f && p.vis[b] > 0.4f) {
                    c.drawLine(x(n[a * 2]), y(n[a * 2 + 1]), x(n[b * 2]), y(n[b * 2 + 1]), line)
                }
                i += 2
            }
            for (k in intArrayOf(0, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)) {
                if (p.vis[k] > 0.4f) c.drawCircle(x(n[k * 2]), y(n[k * 2 + 1]), 5f * density, dot)
            }
            // Label above the head.
            text.textSize = 14f * density
            text.color = col
            c.drawText("Person ${p.id + 1}", x(n[0]), y(n[1]) - 24f * density, text)
        }

        if (countdown > 0) {
            text.color = Color.WHITE
            text.textSize = 120f * density
            c.drawText(countdown.toString(), (vw - rightInset) / 2f, vh / 2f + 40f * density, text)
            text.textSize = 16f * density
            c.drawText("Step back so your whole body is in view", (vw - rightInset) / 2f, vh / 2f + 80f * density, text)
        }
        if (recording) {
            dot.color = 0xFFEF4444.toInt()
            c.drawCircle(vw - rightInset - 22f * density, 22f * density, 9f * density, dot)
        }
    }

    private fun x(nx: Float) = dst.left + (if (mirror) 1f - nx else nx) * dst.width()
    private fun y(ny: Float) = dst.top + ny * dst.height()
}
