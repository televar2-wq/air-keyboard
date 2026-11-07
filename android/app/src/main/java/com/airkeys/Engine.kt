package com.airkeys

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

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
    /** Fired on the main thread when the camera starts or stops. */
    val stateListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile var running = false
        set(value) {
            val changed = field != value
            field = value
            if (changed) main.post { for (l in stateListeners) l() }
        }
    @Volatile var modelReady = false
    @Volatile var status = "Camera off"
    @Volatile var homeCount = -1
    @Volatile var lastPressed: Key? = null
    @Volatile var lastPressedAt = 0L
    @Volatile var lastSwipeAt = 0L
    /** False when the last tap couldn't be typed anywhere (shown as a red flash). */
    @Volatile var lastDelivered = true

    // ---- keyboard connection (reported by the Air Keys keyboard service) ----
    /** The Air Keys keyboard is the active system keyboard. */
    @Volatile var imeActive = false
    /** It's attached to a text field; the app's label (or package) is here. */
    @Volatile var imeTarget: String? = null
    @Volatile var typingEnabled = true

    // ---- settings ----
    @Volatile var frontCamera = true
    @Volatile var flipped = true
    @Volatile var sensitivity = 4          // 1..10
    @Volatile var rowScale = 1.3f          // key height, 1.0 = normal keyboard proportions
    @Volatile var fingerZones = true       // touch-typing assist
    @Volatile var followHands = true       // slowly re-center the keyboard under resting hands
    @Volatile var swipeBackspace = true
    @Volatile var autocorrect = true
    @Volatile var zoomToKeyboard = true
    @Volatile var railLeft = false          // side for the big buttons on the main screen
    /** The word being typed (autocorrect's current guess), shown on the camera view. */
    @Volatile var composing = ""
    @Volatile var showWindow = true
    @Volatile var calibratedOnce = false
    @Volatile var board = Board(true)
        private set
    @Volatile var corners: FloatArray? = null
        private set
    @Volatile private var h: Homography? = null
    @Volatile private var hInv: Homography? = null

    val frameListeners = CopyOnWriteArraySet<() -> Unit>()
    val keyListeners = CopyOnWriteArraySet<(Tap) -> Unit>()

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
        sensitivity = p.getInt("sens", 4)
        showWindow = p.getBoolean("window", true)
        typingEnabled = p.getBoolean("typing", true)
        calibratedOnce = p.getBoolean("calibrated", false)
        fingerZones = p.getBoolean("zones", true)
        followHands = p.getBoolean("follow", true)
        swipeBackspace = p.getBoolean("swipe", true)
        autocorrect = p.getBoolean("autocorrect", true)
        zoomToKeyboard = p.getBoolean("zoom", true)
        railLeft = p.getBoolean("railLeft", false)
        board = Board(p.getBoolean("numbers", true))
        val c = p.getString("corners", null)
            ?.split(",")?.mapNotNull { it.toFloatOrNull() }
        corners = if (c != null && c.size == 8) c.toFloatArray() else null
        val rs = p.getFloat("rowScale", -1f)
        rowScale = if (rs > 0f) rs else 1f
        rebuild()
        loaded = true
        // Older setups were fitted at normal key height: make the keys taller once.
        if (rs <= 0f) {
            if (corners != null) applyRowScale(1.3f) else { rowScale = 1.3f; save() }
        }
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
            .putBoolean("calibrated", calibratedOnce)
            .putBoolean("numbers", board.numbers)
            .putFloat("rowScale", rowScale)
            .putBoolean("zones", fingerZones)
            .putBoolean("follow", followHands)
            .putBoolean("swipe", swipeBackspace)
            .putBoolean("autocorrect", autocorrect)
            .putBoolean("zoom", zoomToKeyboard)
            .putBoolean("railLeft", railLeft)
            .putString("corners", corners?.joinToString(","))
            .apply()
    }

    fun homeRowV(): Float = (if (board.numbers) 2 else 1) + 0.5f

    /** Make rows taller/shorter around the home row (keys stay under resting fingers). */
    fun applyRowScale(newScale: Float) {
        val ns = newScale.coerceIn(1f, 1.8f)
        val c = corners
        if (c != null && h != null && kotlin.math.abs(ns - rowScale) > 1e-3f) {
            val f = ns / rowScale
            val hv = homeRowV()
            val top = hv - hv * f
            val bot = hv + (board.rows - hv) * f
            val tl = kbToNorm(0f, top)
            val tr = kbToNorm(Board.COLS, top)
            val br = kbToNorm(Board.COLS, bot)
            val bl = kbToNorm(0f, bot)
            setCorners(floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y))
        }
        rowScale = ns
        save()
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
        var rest = 0f            // "finger on the desk" level of the signal
        var warmUntil = 0L       // ignore taps right after a hand (re)appears
        var downFrames = 0
        var s2 = 0f              // signal two frames ago
        var liftFrames = 0       // consecutive frames the finger has been lifted
        var liftAtStart = 0
        var maxStep = 0f         // biggest single-frame drop during this tap

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

        val trackOf = updateSwipes(raw, now)

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
            // While the whole hand is moving fast (swiping, repositioning), don't type.
            val handFast = (trackOf[p]?.fastUntil ?: 0L) > now
            var bestKey: Key? = null
            var bestTap: Tap? = null
            var bestTravel = 0f
            var fired = 0
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
                if (travel > 0f) fired++
                if (travel > bestTravel && !handFast) {
                    val tapKey = if (fingerZones && kb != null) board.resolve(side, f, kb.x, kb.y) else key
                    if (tapKey != null && kb != null) {
                        bestTravel = travel; bestKey = tapKey
                        bestTap = Tap(tapKey, kb.x, kb.y, side, f)
                    }
                }
                Tip(fs.sx, fs.sy, kb?.x ?: -1f, kb?.y ?: -1f, key)
            }
            val homeLabels = if (side == 'L') HOME_L else HOME_R
            for (f in 1..4) if (tips[f].key?.label == homeLabels[f]) home++
            out.add(Hand(side, p, tips))
            // Several fingers "tapping" at once is the whole hand moving or a tracking glitch.
            val dropping = fired + st.count { it.phase == 1 }
            if (dropping < 3) bestTap?.let { emit(it, now) }
        }
        hands = out
        homeCount = if (seen) home else -1
        if (calibrating) calibrateStep(raw, now)
        else if (followHands && out.size == 2) followRestingHands(out, trackOf, now)
        for (l in frameListeners) l()
    }

    // ------------------------------------------------------------------ swipe to delete

    private class Track(var x: Float, var y: Float, var lastSeen: Long) {
        val hist = ArrayDeque<Pair<Long, Float>>()   // (time, palm u)
        var speed = 0f                                // key widths per second, smoothed
        var fastUntil = 0L
        var cooldownUntil = 0L
    }

    private val tracks = ArrayList<Track>()
    private var lastTapAt = 0L
    private val SWIPE_KEYS = 3.5f        // how far the hand must travel left (key widths)
    private val SWIPE_MS = 400L          // …within this time
    private val FAST_SPEED = 9f          // hand speed (key widths/s) that pauses typing

    /** Follows each palm between frames and turns a quick move to the left into a backspace. */
    private fun updateSwipes(raw: List<FloatArray>, now: Long): java.util.IdentityHashMap<FloatArray, Track> {
        val result = java.util.IdentityHashMap<FloatArray, Track>()
        val used = HashSet<Track>()
        for (p in raw) {
            var px = 0f
            var py = 0f
            for (i in intArrayOf(0, 5, 9, 13, 17)) { px += p[i * 3]; py += p[i * 3 + 1] }
            px /= 5f; py /= 5f
            val u = normToKb(px, py)?.x ?: continue
            var best: Track? = null
            var bd = 0.25f
            for (t in tracks) {
                if (t in used) continue
                val d = hypot((t.x - px) * aspect, t.y - py)
                if (d < bd) { bd = d; best = t }
            }
            val t = best ?: Track(px, py, now).also { tracks.add(it) }
            used.add(t)
            result[p] = t

            val last = t.hist.lastOrNull()
            if (last != null) {
                val dt = max(now - last.first, 1L) / 1000f
                t.speed = t.speed * 0.5f + kotlin.math.abs(u - last.second) / dt * 0.5f
            }
            t.x = px; t.y = py; t.lastSeen = now
            t.hist.addLast(now to u)
            while (t.hist.isNotEmpty() && now - t.hist.first().first > SWIPE_MS) t.hist.removeFirst()
            if (t.speed > FAST_SPEED) t.fastUntil = now + 250L

            val first = t.hist.first()
            if (swipeBackspace && now >= t.cooldownUntil && u - first.second < -SWIPE_KEYS) {
                t.cooldownUntil = now + 600L
                t.fastUntil = now + 350L
                t.hist.clear()
                emitBackspace(now)
            }
        }
        tracks.removeAll { now - it.lastSeen > 300L }
        return result
    }

    private val BACKSPACE = Key("⌫", -1, 0f, 0f)

    private fun emitBackspace(now: Long) {
        if (calibrating) return
        lastSwipeAt = now
        emit(Tap(BACKSPACE, -1f, -1f, 'R', 4), now)
    }

    // ------------------------------------------------------------------ follow resting hands

    private var lastFollowSave = 0L

    /**
     * When both hands rest on the home row (each finger within ~half a key of its home key,
     * not typing, not moving), nudge the keyboard a little toward them. Keeps it lined up
     * as your hands drift, without ever jumping.
     */
    private fun followRestingHands(hs: List<Hand>, trackOf: Map<FloatArray, Track>, now: Long) {
        if (now - lastTapAt < 700L) return
        for (h in hs) if ((trackOf[h.pts]?.fastUntil ?: 0L) > now) return
        val hv = homeRowV()
        var du = 0f
        var dv = 0f
        for (h in hs) {
            val labels = if (h.side == 'L') HOME_L else HOME_R
            for (f in 1..4) {
                val t = h.tips[f]
                val key = board.keys.firstOrNull { it.label == labels[f] } ?: return
                val ex = t.u - key.centerU
                val ey = t.v - hv
                if (kotlin.math.abs(ex) > 0.6f || kotlin.math.abs(ey) > 0.6f) return
                du += ex; dv += ey
            }
        }
        du /= 8f; dv /= 8f
        if (hypot(du, dv) < 0.05f) return
        val alpha = 0.04f
        val a = kbToNorm(Board.COLS / 2f, hv)
        val b = kbToNorm(Board.COLS / 2f + du * alpha, hv + dv * alpha)
        val c = corners ?: return
        val sx = b.x - a.x
        val sy = b.y - a.y
        setCorners(floatArrayOf(c[0] + sx, c[1] + sy, c[2] + sx, c[3] + sy, c[4] + sx, c[5] + sy, c[6] + sx, c[7] + sy))
        if (now - lastFollowSave > 3000L) { lastFollowSave = now; save() }
    }

    // ------------------------------------------------------------------ calibration

    /** Keyboard-unit centers of A S D F J K L ; */
    private val HOME_U = floatArrayOf(2.25f, 3.25f, 4.25f, 5.25f, 8.25f, 9.25f, 10.25f, 11.25f)
    private const val CALIB_MS = 4000L

    @Volatile var calibrating = false
        private set
    @Volatile var calibProgress = 0f
        private set
    @Volatile var calibMessage = ""
        private set
    @Volatile var calibDoneAt = 0L
        private set
    @Volatile private var calibReset = false
    private var calibSum = FloatArray(24)
    private var calibCount = 0
    private var calibHeld = 0L          // ms of "steady enough" time banked so far
    private var calibLastT = 0L
    private var lastBothHands = 0L
    private var calibSmooth: FloatArray? = null

    /** Start "rest your hands on the home row and hold steady for 4 seconds". */
    fun startCalibration() {
        calibReset = true
        calibProgress = 0f
        calibMessage = "Rest fingers on A S D F · J K L ; and thumbs on space, then hold steady"
        calibrating = true
        for (l in frameListeners) l()
    }

    fun cancelCalibration() {
        calibrating = false
        calibProgress = 0f
        calibMessage = ""
        for (l in frameListeners) l()
    }

    private fun resetCalib() {
        calibSum = FloatArray(24)
        calibCount = 0
        calibHeld = 0L
        calibSmooth = null
    }

    /**
     * Collects fingertip positions while both hands stay roughly steady.
     *
     * Forgiving on purpose (people bump tables):
     *  - a shared shift of all fingers (phone or table wobble) is ignored;
     *  - 6 of the 8 fingers being in place is enough;
     *  - a wobbly moment rewinds the countdown instead of restarting it;
     *  - losing a hand for under a second just pauses.
     *
     * Sample layout (x scaled by aspect so distances are square): 0-3 left pinky..index,
     * 4-7 right index..pinky, 8 left thumb, 9 right thumb, 10 left wrist, 11 right wrist.
     */
    private fun calibrateStep(raw: List<FloatArray>, now: Long) {
        if (calibReset) {
            resetCalib(); calibLastT = now; lastBothHands = now; calibReset = false
        }
        val dt = (now - calibLastT).coerceIn(0L, 200L)
        calibLastT = now

        if (raw.size < 2) {
            if (now - lastBothHands > 900L) {
                calibHeld = max(0L, calibHeld - dt)
                if (calibHeld == 0L) resetCalib()
                calibMessage = if (raw.isEmpty()) "Show both hands to the camera" else "I can only see one hand — show both"
            }
            calibProgress = (calibHeld.toFloat() / CALIB_MS).coerceIn(0f, 1f)
            return
        }
        lastBothHands = now

        // On screen, your left hand is on the left (front camera view is mirrored).
        val pair = raw.take(2).sortedBy { it[0] }
        val l = pair[0]
        val r = pair[1]
        val s = FloatArray(24)
        var k = 0
        for (f in intArrayOf(4, 3, 2, 1)) { s[k++] = contactX(l, f) * aspect; s[k++] = contactY(l, f) }
        for (f in intArrayOf(1, 2, 3, 4)) { s[k++] = contactX(r, f) * aspect; s[k++] = contactY(r, f) }
        s[k++] = contactX(l, 0) * aspect; s[k++] = contactY(l, 0)
        s[k++] = contactX(r, 0) * aspect; s[k++] = contactY(r, 0)
        s[k++] = l[0] * aspect; s[k++] = l[1]
        s[k++] = r[0] * aspect; s[k] = r[1]

        // Smooth out tracking jitter.
        val prev = calibSmooth
        if (prev != null) for (i in 0 until 24) s[i] = prev[i] * 0.5f + s[i] * 0.5f
        calibSmooth = s.copyOf()

        val hs = (handSize(l) + handSize(r)) / 2f
        var steady = true
        if (calibCount > 0) {
            val n = calibCount.toFloat()
            // Shared shift of all fingertips = the phone/table moved, not your fingers.
            var shx = 0f
            var shy = 0f
            for (i in 0 until 8) {
                shx += s[i * 2] - calibSum[i * 2] / n
                shy += s[i * 2 + 1] - calibSum[i * 2 + 1] / n
            }
            shx /= 8f; shy /= 8f
            var ok = 0
            for (i in 0 until 8) {
                val ex = s[i * 2] - calibSum[i * 2] / n - shx
                val ey = s[i * 2 + 1] - calibSum[i * 2 + 1] / n - shy
                if (hypot(ex, ey) < 0.3f * hs) ok++
            }
            steady = ok >= 6 && hypot(shx, shy) < 0.8f * hs
        }

        if (steady) {
            for (i in 0 until 24) calibSum[i] += s[i]
            calibCount++
            calibHeld += dt
        } else {
            calibHeld = max(0L, calibHeld - dt)
            if (calibHeld == 0L) {
                // Hands have settled somewhere new: start averaging from here.
                resetCalib()
                for (i in 0 until 24) calibSum[i] = s[i]
                calibCount = 1
                calibSmooth = s.copyOf()
            }
        }

        calibProgress = (calibHeld.toFloat() / CALIB_MS).coerceIn(0f, 1f)
        val secs = ((CALIB_MS - calibHeld) / 1000L + 1L).coerceIn(1L, 4L)
        calibMessage = when {
            !steady -> "Hold steady…"
            calibHeld < 250L -> "Fingers on A S D F · J K L ; — hold steady"
            else -> "Hold steady… $secs"
        }
        if (calibHeld >= CALIB_MS && calibCount > 0) {
            val mean = FloatArray(24) { i -> calibSum[i] / calibCount }
            finishCalibration(mean, now)
        }
    }

    /** Fit the keyboard so A S D F / J K L ; sit under the fingers and space under the thumbs. */
    private fun finishCalibration(m: FloatArray, now: Long) {
        // Line through the 8 fingertips (principal direction).
        var cx = 0f
        var cy = 0f
        for (i in 0 until 8) { cx += m[i * 2]; cy += m[i * 2 + 1] }
        cx /= 8f; cy /= 8f
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until 8) {
            val ex = m[i * 2] - cx
            val ey = m[i * 2 + 1] - cy
            sxx += ex * ex; syy += ey * ey; sxy += ex * ey
        }
        val ang = 0.5f * atan2(2f * sxy, sxx - syy)
        var dx = cos(ang)
        var dy = sin(ang)
        // Point from your left pinky toward your right pinky.
        if ((m[14] - m[0]) * dx + (m[15] - m[1]) * dy < 0f) { dx = -dx; dy = -dy }

        // Key spacing along the row: least squares of position vs. key column.
        val t = FloatArray(8) { i -> (m[i * 2] - cx) * dx + (m[i * 2 + 1] - cy) * dy }
        val mu = HOME_U.average().toFloat()
        val mt = t.average().toFloat()
        var cov = 0f
        var varU = 0f
        for (i in 0 until 8) {
            cov += (HOME_U[i] - mu) * (t[i] - mt)
            varU += (HOME_U[i] - mu) * (HOME_U[i] - mu)
        }
        val su = cov / varU
        if (su <= 0f) {
            calibMessage = "Couldn't read your hands — try again"
            resetCalib()
            return
        }
        val a = mt - su * mu

        // Across the row: toward your wrists is toward the space bar.
        var nx = -dy
        var ny = dx
        val wx = (m[20] + m[22]) / 2f - cx
        val wy = (m[21] + m[23]) / 2f - cy
        if (wx * nx + wy * ny < 0f) { nx = -nx; ny = -ny }

        val homeIdx = if (board.numbers) 2 else 1
        val homeV = homeIdx + 0.5f
        val spaceGap = (board.rows - 0.5f) - homeV
        val tx = (m[16] + m[18]) / 2f - cx
        val ty = (m[17] + m[19]) / 2f - cy
        var sv = (tx * nx + ty * ny) / spaceGap
        if (sv < 0.3f * su || sv > 1.3f * su) sv = su * (if (frontCamera) 0.55f else 0.95f)
        sv *= rowScale

        fun pt(u: Float, v: Float): PointF {
            val along = a + su * u
            val across = (v - homeV) * sv
            return PointF((cx + along * dx + across * nx) / aspect, cy + along * dy + across * ny)
        }
        val rowsF = board.rows.toFloat()
        val tl = pt(0f, 0f)
        val tr = pt(Board.COLS, 0f)
        val br = pt(Board.COLS, rowsF)
        val bl = pt(0f, rowsF)
        setCorners(floatArrayOf(tl.x, tl.y, tr.x, tr.y, br.x, br.y, bl.x, bl.y))
        flipped = ny < 0f
        calibratedOnce = true
        save()

        calibrating = false
        calibProgress = 1f
        calibDoneAt = now
        calibMessage = "Keyboard fitted to your hands ✓"
        states.values.forEach { arr -> arr.forEach { it.reset() } }
        main.post { vibrate() }
    }

    private fun vibrate() {
        val ctx = appCtx ?: return
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createOneShot(90L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /**
     * Tap detector for one finger. The signal is how far the fingertip has dropped
     * relative to its own knuckle, scaled by hand size, so moving the whole hand
     * doesn't count.
     *  - Desk-level camera (front): drop shows up as the tip moving down the image.
     *  - Overhead camera (rear): drop shows up as the tip moving away from the camera (z).
     *
     * A tap must be all of these, which is what keeps idle hands from typing:
     *  - a fast drop spread over at least two frames, not one sudden jump (tracking glitch);
     *  - a big enough drop;
     *  - starting from a finger that was lifted for a couple of frames: a finger
     *    resting on the desk can't tap;
     *  - not within half a second of the hand coming into view.
     * Returns the travel of a detected tap, or 0.
     */
    private fun updateTap(fs: FingerState, p: FloatArray, f: Int, hs: Float, now: Long): Float {
        val t = FINGER_TIPS[f]
        val k = if (f == 0) 2 else t - 3
        val s = if (frontCamera) (p[t * 3 + 1] - p[k * 3 + 1]) / hs
                else (p[t * 3 + 2] - p[k * 3 + 2]) / hs
        if (!fs.valid) {
            fs.s = s; fs.s2 = s; fs.t = now; fs.vel = 0f; fs.phase = 0; fs.valid = true
            fs.rest = s; fs.warmUntil = now + 500L; fs.liftFrames = 0
            return 0f
        }
        val dt = max(now - fs.t, 1L) / 1000f
        val v = (s - fs.s) / dt
        val prev = fs.s
        val prev2 = fs.s2
        fs.vel = fs.vel * 0.5f + v * 0.5f
        fs.s2 = prev
        fs.s = s
        fs.t = now
        // Desk level = the lowest the fingertip has been lately; forgets slowly.
        fs.rest = max(s, fs.rest - 0.04f * dt)

        val sens = (sensitivity - 1).coerceIn(0, 9) / 9f          // 0 = strict … 1 = sensitive
        val thr = 3.0f - 1.8f * sens                              // drop speed, hand-sizes per second
        val minTravel = 0.20f - 0.10f * sens                      // drop size, hand-sizes
        val minLift = 0.10f - 0.05f * sens                        // how far above the desk it must start
        when (fs.phase) {
            0 -> {
                val liftBefore = fs.liftFrames
                fs.liftFrames = if (s < fs.rest - minLift) fs.liftFrames + 1 else 0
                if (now >= fs.cooldownUntil && now >= fs.warmUntil && fs.vel > thr) {
                    // The drop may have begun a frame before we noticed it.
                    fs.phase = 1
                    fs.startS = min(prev, prev2)
                    fs.startT = now
                    fs.maxStep = max(s - prev, prev - prev2)
                    fs.downFrames = (if (v > thr * 0.4f) 1 else 0) + (if ((prev - prev2) / dt > thr * 0.4f) 1 else 0)
                    fs.liftAtStart = max(liftBefore, fs.liftFrames)
                }
            }
            1 -> {
                if (v > thr * 0.4f) fs.downFrames++
                fs.maxStep = max(fs.maxStep, s - prev)
                if (now - fs.startT > 450L) {
                    fs.phase = 0
                } else if (fs.vel < thr * 0.25f) {
                    fs.phase = 0
                    val travel = s - fs.startS
                    val ok = travel > minTravel &&          // big enough
                        fs.downFrames >= 2 &&               // moved over 2+ frames
                        fs.maxStep <= 0.75f * travel &&     // not one sudden tracking jump
                        fs.liftAtStart >= 2                 // finger was lifted first
                    if (ok) {
                        fs.cooldownUntil = now + 150L
                        return travel
                    }
                }
            }
        }
        return 0f
    }

    private fun emit(t: Tap, now: Long) {
        if (calibrating) return
        lastTapAt = now
        lastPressed = t.key
        lastPressedAt = now
        lastDelivered = typingEnabled && imeActive && imeTarget != null && keyListeners.isNotEmpty()
        if (!lastDelivered) return
        main.post { for (l in keyListeners) l(t) }
    }

    /** One-line description of where taps go right now, or why they don't. */
    fun typingTargetText(): String = when {
        !typingEnabled -> "Typing paused"
        !imeActive -> "Not typing: Air Keys isn't your keyboard"
        imeTarget == null -> "Tap a text field in the other app"
        else -> "Typing into $imeTarget"
    }

    fun typingReady(): Boolean = typingEnabled && imeActive && imeTarget != null

    fun clearLive() {
        frame = null
        hands = emptyList()
        homeCount = -1
        states.values.forEach { arr -> arr.forEach { it.reset() } }
        for (l in frameListeners) l()
    }
}
