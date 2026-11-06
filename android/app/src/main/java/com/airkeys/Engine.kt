package com.airkeys

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shared state between the camera service, the floating window, the setup screen
 * and the keyboard. Everything lives in one process, so a singleton is enough.
 *
 * Coordinates: x/y are normalized (0..1) over the displayed camera frame
 * (already rotated upright and, for the front camera, mirrored).
 * u/v are keyboard units: u = 0..15 across, v = 0..rows down.
 */
object Engine {
    val FINGER_TIPS = intArrayOf(4, 8, 12, 16, 20)            // thumb, index, middle, ring, pinky
    val FINGER_COLORS = intArrayOf(
        0xFFFB923C.toInt(), 0xFF60A5FA.toInt(), 0xFFF472B6.toInt(), 0xFFFACC15.toInt(), 0xFFC084FC.toInt()
    )
    private val HOME_L = arrayOf("Space", "F", "D", "S", "A")
    private val HOME_R = arrayOf("Space", "J", "K", "L", ";")
    val LINKS = intArrayOf(
        0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12,
        9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 17, 18, 18, 19, 19, 20, 0, 17
    )

    class Tip(val x: Float, val y: Float, val u: Float, val v: Float, val key: Key?)
    class Hand(val side: Char, val pts: FloatArray, val tips: Array<Tip>)

    // ---- live state ----
    @Volatile var frame: Bitmap? = null
    @Volatile var aspect = 4f / 3f
    @Volatile var hands: List<Hand> = emptyList()
    @Volatile var running = false
    @Volatile var modelReady = false
    @Volatile var status = "Camera off"
    @Volatile var homeCount = -1
    @Volatile var lastPressed: Key? = null
    @Volatile var lastPressedAt = 0L
    @Volatile var typingEnabled = true

    // ---- settings ----
    @Volatile var frontCamera = true
    @Volatile var flipped = true
    @Volatile var sensitivity = 5          // 1..10
    @Volatile var showWindow = true
    @Volatile var board = Board(true)
        private set
    @Volatile var corners: FloatArray? = null
        private set
    @Volatile private var h: Homography? = null
    @Volatile private var hInv: Homography? = null

    val frameListeners = CopyOnWriteArraySet<() -> Unit>()
    val keyListeners = CopyOnWriteArraySet<(Key) -> Unit>()

    private var loaded = false
    private var appCtx: Context? = null
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------ settings

    fun load(ctx: Context) {
        if (loaded) return
        appCtx = ctx.applicationContext
        val p = prefs() ?: return
        frontCamera = p.getBoolean("front", true)
        flipped = p.getBoolean("flipped", true)
        sensitivity = p.getInt("sens", 5)
        showWindow = p.getBoolean("window", true)
        typingEnabled = p.getBoolean("typing", true)
        board = Board(p.getBoolean("numbers", true))
        val c = p.getString("corners", null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }
        corners = if (c != null && c.size == 8) c.toFloatArray() else null
        rebuild()
        loaded = true
    }

    private fun prefs(): SharedPreferences? =
        appCtx?.getSharedPreferences("airkeys", Context.MODE_PRIVATE)

    fun save() {
        val p = prefs() ?: return
        p.edit()
            .putBoolean("front", frontCamera)
            .putBoolean("flipped", flipped)
            .putInt("sens", sensitivity)
            .putBoolean("window", showWindow)
            .putBoolean("typing", typingEnabled)
            .putBoolean("numbers", board.numbers)
            .putString("corners", corners?.joinToString(","))
            .apply()
    }

    fun setCorners(c: FloatArray) {
        corners = c.copyOf()
        rebuild()
    }

    private fun rebuild() {
        val c = corners
        if (c == null) {
            h = null; hInv = null; return
        }
        val hh = Homography.squareToQuad(c)
        h = hh
        hInv = hh.inverse()
    }

    /** A roughly 3:1 keyboard across the lower part of the frame, far edge a bit narrower. */
    fun defaultCorners(): FloatArray {
        val wf = 0.9f
        val hf = min(wf * aspect / 3f * (board.rows / 5f), 0.6f)
        val cy = 0.62f
        val top = cy - hf / 2f
        val bot = cy + hf / 2f
        val inset = 0.05f
        val c = floatArrayOf(
            0.5f - wf / 2f + inset, top,
            0.5f + wf / 2f - inset, top,
            0.5f + wf / 2f, bot,
            0.5f - wf / 2f, bot
        )
        return if (flipped) flip(c) else c
    }

    /** Swap the keyboard's top and bottom edges. */
    fun flip(c: FloatArray) = floatArrayOf(c[6], c[7], c[4], c[5], c[2], c[3], c[0], c[1])

    fun resetCorners() {
        setCorners(defaultCorners()); save()
    }

    fun toggleFlip() {
        flipped = !flipped
        corners?.let { setCorners(flip(it)) }
        save()
    }

    /** Add/remove the number row while keeping the letter rows where they are. */
    fun toggleNumbers() {
        val c = corners
        val had = board.numbers
        if (c != null && h != null) {
            val newTop = if (had) 1f else -1f
            val tl = kbToNorm(0f, newTop)
            val tr = kbToNorm(Board.COLS, newTop)
            setCorners(floatArrayOf(tl.x, tl.y, tr.x, tr.y, c[4], c[5], c[6], c[7]))
        }
        board = Board(!had)
        save()
    }

    fun kbToNorm(u: Float, v: Float): PointF =
        h?.map(u / Board.COLS, v / board.rows) ?: PointF(0f, 0f)

    fun normToKb(x: Float, y: Float): PointF? {
        val q = hInv?.map(x, y) ?: return null
        return PointF(q.x * Board.COLS, q.y * board.rows)
    }

    // ------------------------------------------------------------------ tracking

    private class FingerState {
        var smoothValid = false
        var sx = 0f
        var sy = 0f
        var valid = false
        var s = 0f
        var t = 0L
        var vel = 0f
        var phase = 0
        var startS = 0f
        var startT = 0L
        var cooldownUntil = 0L

        fun reset() {
            smoothValid = false; valid = false; phase = 0; vel = 0f
        }
    }

    private val states = mapOf(
        'L' to Array(5) { FingerState() },
        'R' to Array(5) { FingerState() }
    )

    private fun contactX(p: FloatArray, f: Int): Float {
        val t = FINGER_TIPS[f]
        return p[t * 3] * 0.85f + p[(t - 1) * 3] * 0.15f
    }

    private fun contactY(p: FloatArray, f: Int): Float {
        val t = FINGER_TIPS[f]
        return p[t * 3 + 1] * 0.85f + p[(t - 1) * 3 + 1] * 0.15f
    }

    private fun handSize(p: FloatArray): Float {
        val dx = (p[0] - p[27]) * aspect      // wrist -> middle-finger knuckle
        val dy = p[1] - p[28]
        return max(hypot(dx, dy), 0.01f)
    }

    /**
     * Called by the camera service for every analysed frame.
     * @param raw one FloatArray(63) per detected hand: x,y,z for each of 21 landmarks.
     */
    fun process(bmp: Bitmap, raw: List<FloatArray>, now: Long) {
        aspect = bmp.width.toFloat() / bmp.height.toFloat()
        if (corners == null) setCorners(defaultCorners())
        frame = bmp

        // Which hand is which: by position along the keyboard (robust to mirroring).
        val withU = raw.map { p ->
            var su = 0f
            var n = 0
            for (f in 1..4) {
                val kb = normToKb(contactX(p, f), contactY(p, f))
                if (kb != null) { su += kb.x; n++ }
            }
            Pair(p, if (n > 0) su / n else Board.COLS / 2f)
        }.sortedBy { it.second }

        val assigned = HashMap<Char, FloatArray>()
        if (withU.size >= 2) {
            assigned['L'] = withU.first().first
            assigned['R'] = withU.last().first
        } else if (withU.size == 1) {
            assigned[if (withU[0].second < Board.COLS / 2f) 'L' else 'R'] = withU[0].first
        }

        val out = ArrayList<Hand>()
        var home = 0
        var seen = false
        for (side in charArrayOf('L', 'R')) {
            val st = states.getValue(side)
            val p = assigned[side]
            if (p == null) {
                st.forEach { it.reset() }
                continue
            }
            seen = true
            val hs = handSize(p)
            var bestKey: Key? = null
            var bestTravel = 0f
            val tips = Array(5) { f ->
                val fs = st[f]
                val cx = contactX(p, f)
                val cy = contactY(p, f)
                if (fs.smoothValid) {
                    fs.sx += (cx - fs.sx) * 0.55f
                    fs.sy += (cy - fs.sy) * 0.55f
                } else {
                    fs.sx = cx; fs.sy = cy; fs.smoothValid = true
                }
                val kb = normToKb(fs.sx, fs.sy)
                val key = if (kb != null) board.keyAt(kb.x, kb.y) else null
                val travel = updateTap(fs, p, f, hs, now)
                if (travel > bestTravel && key != null) {
                    bestTravel = travel; bestKey = key
                }
                Tip(fs.sx, fs.sy, kb?.x ?: -1f, kb?.y ?: -1f, key)
            }
            val homeLabels = if (side == 'L') HOME_L else HOME_R
            for (f in 1..4) if (tips[f].key?.label == homeLabels[f]) home++
            out.add(Hand(side, p, tips))
            bestKey?.let { emit(it, now) }
        }
        hands = out
        homeCount = if (seen) home else -1
        for (l in frameListeners) l()
    }

    /**
     * Tap detector for one finger. The signal is how far the fingertip has dropped
     * relative to its own knuckle, scaled by hand size, so moving the whole hand
     * doesn't count. A tap = a fast drop that then stops.
     *  - Desk-level camera (front): drop shows up as the tip moving down the image.
     *  - Overhead camera (rear): drop shows up as the tip moving away from the camera (z).
     * Returns the travel of a detected tap, or 0.
     */
    private fun updateTap(fs: FingerState, p: FloatArray, f: Int, hs: Float, now: Long): Float {
        val t = FINGER_TIPS[f]
        val k = if (f == 0) 2 else t - 3
        val s = if (frontCamera) (p[t * 3 + 1] - p[k * 3 + 1]) / hs
                else (p[t * 3 + 2] - p[k * 3 + 2]) / hs
        if (!fs.valid) {
            fs.s = s; fs.t = now; fs.vel = 0f; fs.phase = 0; fs.valid = true
            return 0f
        }
        val dt = max(now - fs.t, 1L) / 1000f
        val v = (s - fs.s) / dt
        val prevS = fs.s
        fs.vel = fs.vel * 0.4f + v * 0.6f
        fs.s = s
        fs.t = now

        val thr = 2.4f * (1.6f - sensitivity * 0.1f)            // drop speed (hand-sizes per second)
        val minTravel = 0.06f + (10 - sensitivity) * 0.008f     // how far the tip must drop
        when (fs.phase) {
            0 -> if (now >= fs.cooldownUntil && fs.vel > thr) {
                fs.phase = 1; fs.startS = prevS; fs.startT = now
            }
            1 -> {
                if (now - fs.startT > 450L) {
                    fs.phase = 0
                } else if (fs.vel < thr * 0.25f) {
                    fs.phase = 0
                    val travel = s - fs.startS
                    if (travel > minTravel) {
                        fs.cooldownUntil = now + 140L
                        return travel
                    }
                }
            }
        }
        return 0f
    }

    private fun emit(k: Key, now: Long) {
        lastPressed = k
        lastPressedAt = now
        if (!typingEnabled) return
        main.post { for (l in keyListeners) l(k) }
    }

    fun clearLive() {
        frame = null
        hands = emptyList()
        homeCount = -1
        states.values.forEach { arr -> arr.forEach { it.reset() } }
        for (l in frameListeners) l()
    }
}
