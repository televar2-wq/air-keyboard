package com.airkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small always-on-top window with the camera view and keyboard overlay.
 * Drag it to move, drag the corner grip to resize. It never takes keyboard
 * focus, so the app you're typing into keeps it.
 */
class FloatingWindow(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var typingLabel: TextView? = null
    private val ui = Handler(Looper.getMainLooper())
    private val labelTick = object : Runnable {
        override fun run() {
            updateTypingLabel()
            if (root != null) ui.postDelayed(this, 500)
        }
    }

    private val params = WindowManager.LayoutParams(
        (300 * d).toInt(),
        (210 * d).toInt(),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = (12 * d).toInt()
        y = (80 * d).toInt()
    }

    private fun pill(label: String): TextView = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        background = GradientDrawable().apply {
            setColor(0xCC0E1116.toInt())
            cornerRadius = 999f
        }
    }

    private fun updateTypingLabel() {
        typingLabel?.text = when {
            Engine.typingEnabled && !Engine.imeActive -> "⌨ choose keyboard"
            Engine.typingEnabled -> "⌨ typing on"
            else -> "⌨ typing off"
        }
        typingLabel?.setTextColor(if (Engine.typingEnabled) 0xFF5EEAD4.toInt() else Color.LTGRAY)
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null || !Settings.canDrawOverlays(ctx)) return

        val frame = FrameLayout(ctx)
        frame.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 14 * d
            setStroke((1.5f * d).toInt(), 0xFF5EEAD4.toInt())
        }
        frame.clipToOutline = true

        val view = KeyboardOverlayView(ctx, false)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Drag anywhere on the picture to move the window.
        var startX = 0f
        var startY = 0f
        var origX = 0
        var origY = 0
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; origX = params.x; origY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = origX + (e.rawX - startX).toInt()
                    params.y = origY + (e.rawY - startY).toInt()
                    update(frame)
                }
            }
            true
        }

        // Typing on/off + fit-to-hands, top left.
        val typing = pill("")
        typingLabel = typing
        updateTypingLabel()
        typing.setOnClickListener {
            if (Engine.typingEnabled && !Engine.imeActive) {
                try {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
                } catch (_: Exception) {}
            } else {
                Engine.typingEnabled = !Engine.typingEnabled
                Engine.save()
            }
            updateTypingLabel()
        }
        val fit = pill("✋")
        fit.setOnClickListener {
            if (Engine.calibrating) Engine.cancelCalibration() else Engine.startCalibration()
        }
        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(typing)
            addView(fit, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((6 * d).toInt(), 0, 0, 0) })
        }
        frame.addView(pills, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START
        ).apply { setMargins((6 * d).toInt(), 0, 0, (6 * d).toInt()) })

        // Close (hides the window; the camera keeps running until stopped).
        val close = pill("✕")
        close.setOnClickListener {
            Engine.showWindow = false
            Engine.save()
            hide()
        }
        frame.addView(close, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END
        ).apply { setMargins(0, (6 * d).toInt(), (6 * d).toInt(), 0) })

        // Resize grip.
        val grip = pill("⤡")
        var gw = 0
        var gh = 0
        grip.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; startY = e.rawY; gw = params.width; gh = params.height
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = (gw + (e.rawX - startX).toInt()).coerceAtLeast((160 * d).toInt())
                    params.height = (gh + (e.rawY - startY).toInt()).coerceAtLeast((110 * d).toInt())
                    update(frame)
                }
            }
            true
        }
        frame.addView(grip, FrameLayout.LayoutParams(
            (36 * d).toInt(), (36 * d).toInt(), Gravity.BOTTOM or Gravity.END
        ).apply { setMargins(0, 0, (4 * d).toInt(), (4 * d).toInt()) })

        try {
            wm.addView(frame, params)
            root = frame
            ui.post(labelTick)
        } catch (e: Exception) {
            root = null
        }
    }

    private fun update(v: View) {
        try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
    }

    fun hide() {
        ui.removeCallbacks(labelTick)
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        root = null
        typingLabel = null
    }
}
