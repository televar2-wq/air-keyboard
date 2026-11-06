package com.airkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Draws the camera frame, the keyboard (with perspective), lit keys under each
 * fingertip and the hand skeletons. When [editable], the four corners can be
 * dragged (or the whole keyboard moved) to line it up with your desk.
 */
@SuppressLint("ViewConstructor")
class KeyboardOverlayView(ctx: Context, startEditable: Boolean) : View(ctx) {

    var editable: Boolean = startEditable
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val accent = 0xFF5EEAD4.toInt()
    private val keyPath = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dash = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    private val dst = RectF()
    private val badgeRect = RectF()
    private val listener: () -> Unit = { postInvalidate() }

    private var dragIdx = -2          // -2 none, -1 whole keyboard, 0..3 a corner
    private var lastX = 0f
    private var lastY = 0f
    private var work: FloatArray? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Engine.frameListeners.add(listener)
    }

    override fun onDetachedFromWindow() {
        Engine.frameListeners.remove(listener)
        super.onDetachedFromWindow()
    }

    private fun layoutDst(): Boolean {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return false
        val a = Engine.aspect
        var w = vw
        var h = w / a
        if (h > vh) { h = vh; w = h * a }
        dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
        return true
    }

    private fun sx(x: Float) = dst.left + x * dst.width()
    private fun sy(y: Float) = dst.top + y * dst.height()

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        if (!layoutDst()) return
        val bmp = Engine.frame
        if (bmp != null && !bmp.isRecycled) {
            c.drawBitmap(bmp, null, dst, bmpPaint)
        } else {
            text.color = Color.GRAY
            text.textSize = 14f * density
            text.clearShadowLayer()
            c.drawText(Engine.status, width / 2f, height / 2f, text)
        }
        if (Engine.corners != null) {
            drawKeys(c)
            drawHands(c)
            if (editable) drawHandles(c)
        }
        val sinceSwipe = SystemClock.uptimeMillis() - Engine.lastSwipeAt
        if (sinceSwipe < 500L) drawSwipe(c, 1f - sinceSwipe / 500f)
        if (Engine.running && !Engine.calibrating) drawTargetBadge(c)
        if (Engine.calibrating || SystemClock.uptimeMillis() - Engine.calibDoneAt < 2000L) {
            drawCalibration(c)
            if (Engine.calibrating) postInvalidateDelayed(100)
        }
    }

    /** Big fading ⌫ when a swipe deletes a character. */
    private fun drawSwipe(c: Canvas, fade: Float) {
        text.clearShadowLayer()
        text.textSize = (dst.height() * 0.28f).coerceAtLeast(28f * density)
        val alpha = (fade * 220).toInt().coerceIn(0, 255)
        text.color = (alpha shl 24) or (if (Engine.lastDelivered) 0xFFFFFF else 0xEF4444)
        c.drawText("⌫", dst.centerX(), dst.centerY() - (text.descent() + text.ascent()) / 2f, text)
    }

    /** Small badge (top left): where taps are being typed, or why they aren't. */
    private fun drawTargetBadge(c: Canvas) {
        val ready = Engine.typingReady()
        val msg = (if (ready) "⌨ " else "⚠ ") + Engine.typingTargetText()
        text.clearShadowLayer()
        text.textSize = 12f * density
        val maxW = width - 70f * density
        var label = msg
        while (label.length > 4 && text.measureText(label) > maxW) label = label.dropLast(2) + "…"
        val tw = text.measureText(label)
        val padX = 8f * density
        val h = 22f * density
        val x0 = 6f * density
        val y0 = 6f * density
        fill.color = if (ready) 0xCC0E1116.toInt() else 0xE6B91C1C.toInt()
        badgeRect.set(x0, y0, x0 + tw + padX * 2, y0 + h)
        c.drawRoundRect(badgeRect, h / 2f, h / 2f, fill)
        text.color = if (ready) accent else Color.WHITE
        val oldAlign = text.textAlign
        text.textAlign = Paint.Align.LEFT
        c.drawText(label, x0 + padX, y0 + h / 2f - (text.descent() + text.ascent()) / 2f, text)
        text.textAlign = oldAlign
    }

    /** Banner with the hold-still prompt and a progress bar. */
    private fun drawCalibration(c: Canvas) {
        val w = width.toFloat()
        val bandH = 46f * density
        fill.color = 0xE60E1116.toInt()
        c.drawRect(0f, 0f, w, bandH, fill)

        val msg = Engine.calibMessage
        text.clearShadowLayer()
        text.color = if (Engine.calibrating) Color.WHITE else accent
        var size = 15f * density
        text.textSize = size
        while (text.measureText(msg) > w - 16f * density && size > 9f * density) {
            size -= 0.5f * density
            text.textSize = size
        }
        c.drawText(msg, w / 2f, bandH / 2f - 2f * density - (text.descent() + text.ascent()) / 2f, text)

        val barH = 5f * density
        fill.color = 0xFF2B313C.toInt()
        c.drawRect(0f, bandH - barH, w, bandH, fill)
        fill.color = accent
        val p = if (Engine.calibrating) Engine.calibProgress else 1f
        c.drawRect(0f, bandH - barH, w * p, bandH, fill)
    }

    private fun keyQuad(u0: Float, v0: Float, u1: Float, v1: Float) {
        val a = Engine.kbToNorm(u0, v0)
        val b = Engine.kbToNorm(u1, v0)
        val cc = Engine.kbToNorm(u1, v1)
        val d = Engine.kbToNorm(u0, v1)
        keyPath.reset()
        keyPath.moveTo(sx(a.x), sy(a.y))
        keyPath.lineTo(sx(b.x), sy(b.y))
        keyPath.lineTo(sx(cc.x), sy(cc.y))
        keyPath.lineTo(sx(d.x), sy(d.y))
        keyPath.close()
    }

    private fun drawKeys(c: Canvas) {
        val board = Engine.board
        val lit = HashMap<Key, Int>()
        for (hand in Engine.hands) {
            for (f in 0..4) {
                val k = hand.tips[f].key ?: continue
                if (!lit.containsKey(k)) lit[k] = Engine.FINGER_COLORS[f]
            }
        }
        val pressed = Engine.lastPressed.takeIf { SystemClock.uptimeMillis() - Engine.lastPressedAt < 250L }
        val gap = 0.07f

        for (k in board.keys) {
            keyQuad(k.u0 + gap, k.row + gap, k.u1 - gap, k.row + 1 - gap)
            val col = lit[k]
            val isPressed = k == pressed
            fill.color = when {
                isPressed && !Engine.lastDelivered -> 0xFFEF4444.toInt()
                isPressed -> Color.WHITE
                col != null -> (col and 0x00FFFFFF) or 0xC0000000.toInt()
                else -> 0x55000000
            }
            c.drawPath(keyPath, fill)
            val home = k.label in Board.HOME_KEYS
            stroke.pathEffect = null
            stroke.strokeWidth = (if (home) 2.2f else 1.1f) * density
            stroke.color = if (home) accent else 0xCCFFFFFF.toInt()
            c.drawPath(keyPath, stroke)

            val top = Engine.kbToNorm(k.centerU, k.row.toFloat())
            val bot = Engine.kbToNorm(k.centerU, k.row + 1f)
            val ctr = Engine.kbToNorm(k.centerU, k.row + 0.5f)
            val keyH = hypot(sx(bot.x) - sx(top.x), sy(bot.y) - sy(top.y))
            val ratio = if (k.label.length > 1) 0.3f else 0.45f
            text.textSize = (keyH * ratio).coerceIn(6f * density, 28f * density)
            val dark = isPressed || col != null
            text.color = if (dark) Color.BLACK else Color.WHITE
            text.setShadowLayer(2.5f * density, 0f, 0f, if (dark) 0x99FFFFFF.toInt() else Color.BLACK)
            c.drawText(k.label, sx(ctr.x), sy(ctr.y) - (text.descent() + text.ascent()) / 2f, text)
        }
        text.clearShadowLayer()
    }

    private fun drawHands(c: Canvas) {
        for (hand in Engine.hands) {
            stroke.pathEffect = null
            stroke.strokeWidth = 1.5f * density
            stroke.color = 0x99FFFFFF.toInt()
            val p = hand.pts
            var i = 0
            while (i < Engine.LINKS.size) {
                val a = Engine.LINKS[i]
                val b = Engine.LINKS[i + 1]
                c.drawLine(sx(p[a * 3]), sy(p[a * 3 + 1]), sx(p[b * 3]), sy(p[b * 3 + 1]), stroke)
                i += 2
            }
            stroke.strokeWidth = 2f * density
            stroke.color = Color.WHITE
            for (f in 0..4) {
                val t = hand.tips[f]
                fill.color = Engine.FINGER_COLORS[f]
                c.drawCircle(sx(t.x), sy(t.y), 5f * density, fill)
                c.drawCircle(sx(t.x), sy(t.y), 5f * density, stroke)
            }
        }
    }

    private fun drawHandles(c: Canvas) {
        val cs = Engine.corners ?: return
        keyQuad(0f, 0f, Board.COLS, Engine.board.rows.toFloat())
        stroke.color = accent
        stroke.strokeWidth = 2f * density
        stroke.pathEffect = dash
        c.drawPath(keyPath, stroke)
        stroke.pathEffect = null
        for (i in 0..3) {
            val x = sx(cs[i * 2])
            val y = sy(cs[i * 2 + 1])
            fill.color = if (dragIdx == i) 0x995EEAD4.toInt() else 0x445EEAD4
            c.drawCircle(x, y, 18f * density, fill)
            c.drawCircle(x, y, 18f * density, stroke)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable) return false
        val cs = Engine.corners ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragIdx = -2
                var best = -1
                var bd = Float.MAX_VALUE
                for (i in 0..3) {
                    val d = hypot(sx(cs[i * 2]) - e.x, sy(cs[i * 2 + 1]) - e.y)
                    if (d < bd) { bd = d; best = i }
                }
                if (bd < 40f * density) {
                    dragIdx = best
                } else if (dst.width() > 0f) {
                    val kb = Engine.normToKb((e.x - dst.left) / dst.width(), (e.y - dst.top) / dst.height())
                    if (kb != null && kb.x in 0f..Board.COLS && kb.y in 0f..Engine.board.rows.toFloat()) dragIdx = -1
                }
                if (dragIdx == -2) return false
                work = cs.copyOf()
                lastX = e.x
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val w = work ?: return false
                val dx = (e.x - lastX) / dst.width()
                val dy = (e.y - lastY) / dst.height()
                lastX = e.x
                lastY = e.y
                if (dragIdx >= 0) {
                    w[dragIdx * 2] = (w[dragIdx * 2] + dx).coerceIn(-0.25f, 1.25f)
                    w[dragIdx * 2 + 1] = (w[dragIdx * 2 + 1] + dy).coerceIn(-0.25f, 1.25f)
                } else {
                    for (i in 0..3) {
                        w[i * 2] = (w[i * 2] + dx).coerceIn(-0.25f, 1.25f)
                        w[i * 2 + 1] = (w[i * 2 + 1] + dy).coerceIn(-0.25f, 1.25f)
                    }
                }
                Engine.setCorners(w)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (work != null) Engine.save()
                work = null
                dragIdx = -2
                invalidate()
                return true
            }
        }
        return true
    }
}
