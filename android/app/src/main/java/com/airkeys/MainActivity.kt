package com.airkeys

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Setup + live view. In split screen (e.g. one half of the Fold) or after tapping
 * "Full view", the setup controls fold away and the camera view fills the window,
 * so you can watch your hands while typing in the app in the other half.
 */
class MainActivity : ComponentActivity() {

    companion object {
        @Volatile
        var visible = false
            private set
    }

    private val ui = Handler(Looper.getMainLooper())
    private var d = 1f
    private var showSetup = true

    private lateinit var status: TextView
    private lateinit var setupPanel: View
    private lateinit var overlay: KeyboardOverlayView
    private lateinit var btnSetup: Button
    private lateinit var btnAdjust: Button
    private lateinit var btnFit: Button
    private lateinit var btnTypingTop: Button
    private lateinit var btnCamera: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnEnable: Button
    private lateinit var btnPick: Button
    private lateinit var btnStart: Button
    private lateinit var btnFlip: Button
    private lateinit var btnRows: Button
    private lateinit var btnWindow: Button
    private lateinit var sensLabel: TextView
    private lateinit var heightLabel: TextView
    private lateinit var btnZones: Button
    private lateinit var btnFollow: Button
    private lateinit var btnSwipe: Button
    private lateinit var btnAuto: Button
    private lateinit var btnZoom: Button
    private lateinit var btnRailSide: Button
    private lateinit var rail: LinearLayout
    private var lastRailWidth = 0

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh()
        if (result[Manifest.permission.CAMERA] == true) startTracking()
        else status.text = "Camera permission is needed. If no prompt appeared, allow it in Settings → Apps → Air Keys → Permissions."
    }

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.load(this)
        d = resources.displayMetrics.density

        // Camera view fills the whole screen; status floats top-left, buttons sit in a
        // rail down one side (easy to reach with the phone half folded).
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        status = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Color.WHITE)
            maxLines = 2
            setPadding(px(8), px(4), px(8), px(4))
            setBackgroundColor(0x990E1116.toInt())
        }

        rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            setBackgroundColor(0x990E1116.toInt())
        }
        btnFit = railButton {
            if (Engine.calibrating) Engine.cancelCalibration() else fitToHands()
            refresh()
        }
        btnAdjust = railButton {
            overlay.editable = !overlay.editable
            refresh()
        }
        btnTypingTop = railButton {
            if (Engine.typingEnabled && !Engine.imeActive) {
                // The usual reason taps don't type: Air Keys isn't the active keyboard.
                if (imeEnabled()) imm().showInputMethodPicker()
                else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            } else {
                Engine.typingEnabled = !Engine.typingEnabled
                Engine.save()
            }
            refresh()
        }
        btnSetup = railButton {
            showSetup = !showSetup
            applyMode()
        }
        rail.addView(btnFit)
        rail.addView(btnAdjust)
        rail.addView(btnTypingTop)
        rail.addView(btnSetup)

        // ---- setup panel (folds away in full view) ----
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        panel.addView(label("Setup"))
        btnCamera = button("1 · Camera") { requestCamera() }
        btnOverlay = button("2 · Floating window") { openOverlaySettings() }
        btnEnable = button("3 · Turn on keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        btnPick = button("4 · Choose keyboard") { imm().showInputMethodPicker() }
        panel.addView(row(btnCamera, btnOverlay, btnEnable, btnPick))

        panel.addView(label("Where is the phone?"))
        panel.addView(row(
            button("Standing in front of my hands") { preset(front = true) },
            button("Looking down from above") { preset(front = false) }
        ))

        panel.addView(label("Keyboard"))
        btnStart = button("Start camera") { if (Engine.running) stopTracking() else startTracking() }
        btnFlip = button("⇅ Flip") { Engine.toggleFlip(); refresh() }
        btnRows = button("123 row") { Engine.toggleNumbers(); refresh() }
        val btnReset = button("Reset shape") { Engine.resetCorners() }
        btnWindow = button("Window") {
            Engine.showWindow = !Engine.showWindow
            Engine.save()
            CameraService.instance?.refreshWindow()
            refresh()
        }
        val btnFull = button("⛶ Full view") {
            showSetup = false
            applyMode()
        }
        panel.addView(row(btnStart, btnFull, btnFlip, btnRows, btnReset, btnWindow))

        sensLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        val seek = SeekBar(this).apply {
            max = 9
            progress = Engine.sensitivity - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Engine.sensitivity = p + 1
                    sensLabel.text = "Tap sensitivity: ${Engine.sensitivity}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { Engine.save() }
            })
        }
        sensLabel.text = "Tap sensitivity: ${Engine.sensitivity}"
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sensLabel)
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        // Key height
        heightLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        val heightSeek = SeekBar(this).apply {
            max = 16                                   // 100% … 180% in 5% steps
            progress = ((Engine.rowScale - 1f) / 0.05f).toInt().coerceIn(0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    heightLabel.text = "Key height: ${100 + p * 5}%"
                    if (fromUser) Engine.applyRowScale(1f + p * 0.05f)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        heightLabel.text = "Key height: ${(Engine.rowScale * 100).toInt()}%"
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heightLabel)
            addView(heightSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        panel.addView(label("Accuracy"))
        btnZones = button("Finger zones") {
            Engine.fingerZones = !Engine.fingerZones; Engine.save(); refresh()
        }
        btnFollow = button("Follow hands") {
            Engine.followHands = !Engine.followHands; Engine.save(); refresh()
        }
        btnSwipe = button("Swipe ⌫") {
            Engine.swipeBackspace = !Engine.swipeBackspace; Engine.save(); refresh()
        }
        btnAuto = button("Autocorrect") {
            Engine.autocorrect = !Engine.autocorrect; Engine.save(); refresh()
        }
        btnZoom = button("Zoom") {
            Engine.zoomToKeyboard = !Engine.zoomToKeyboard; Engine.save(); refresh(); overlay.invalidate()
        }
        btnRailSide = button("Buttons side") {
            Engine.railLeft = !Engine.railLeft; Engine.save(); placeRail(rail.width); refresh()
        }
        panel.addView(row(btnAuto, btnZones, btnFollow, btnSwipe, btnZoom, btnRailSide))

        panel.addView(TextView(this).apply {
            text = "Finger zones: each finger only types its own touch-typing keys, so a slightly " +
                "off tap still lands right. Follow hands: when your fingers rest on the home row, " +
                "the keyboard quietly re-centers under them. Swipe ⌫: sweep a hand quickly toward " +
                "the left to delete a character."
            textSize = 12f
            setTextColor(0xFF9AA3AF.toInt())
            setPadding(0, px(2), 0, px(4))
        })

        panel.addView(TextView(this).apply {
            text = "Tap ✋ Fit, rest your fingers on the home row (A S D F · J K L ;) with thumbs on " +
                "the space bar, and hold still for 4 seconds — the keyboard lines itself up with " +
                "your hands. ✥ Adjust lets you fine-tune the corners by hand. For typing, put " +
                "Air Keys in split screen next to another app; it switches to full view automatically."
            textSize = 12f
            setTextColor(0xFF9AA3AF.toInt())
            setPadding(0, px(4), 0, px(4))
        })

        panel.addView(EditText(this).apply {
            hint = "Test typing here (choose Air Keys as your keyboard first)"
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6B7280.toInt())
        })

        setupPanel = ScrollView(this).apply {
            setBackgroundColor(0xF20E1116.toInt())
            setPadding(px(10), px(6), px(10), px(8))
            addView(panel)
        }

        // ---- live view ----
        overlay = KeyboardOverlayView(this, false)
        root.addView(overlay, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(setupPanel, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(status, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(rail, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Badges and zoom keep clear of the status line and the rail.
        status.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> overlay.topInset = v.bottom.toFloat() }
        rail.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width != lastRailWidth) { lastRailWidth = v.width; placeRail(v.width) }
        }
        placeRail(px(76))

        setContentView(root)

        // Start in full view if already set up, or if we open in split screen.
        showSetup = !(isSetUp() || isInMultiWindowMode)
        applyMode()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        CameraService.instance?.setWindowSuppressed(true)
        refresh()
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onPause() {
        // In split screen we stay visible while the other app has focus,
        // so keep the floating window hidden unless we're really gone.
        if (!isInMultiWindowMode) {
            visible = false
            CameraService.instance?.setWindowSuppressed(false)
            ui.removeCallbacks(tick)
        }
        super.onPause()
    }

    override fun onStop() {
        visible = false
        CameraService.instance?.setWindowSuppressed(false)
        ui.removeCallbacks(tick)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        CameraService.instance?.setWindowSuppressed(true)
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onMultiWindowModeChanged(inMultiWindow: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(inMultiWindow, newConfig)
        if (inMultiWindow) {
            showSetup = false
            applyMode()
        }
    }

    // ------------------------------------------------------------ modes

    private fun applyMode() {
        setupPanel.visibility = if (showSetup) View.VISIBLE else View.GONE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (!showSetup && !isInMultiWindowMode) {
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        refresh()
    }

    private fun isSetUp() = hasCamera() && imeEnabled() && imeSelected()

    // ------------------------------------------------------------ actions

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestCamera() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permLauncher.launch(perms.toTypedArray())
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun startTracking() {
        if (!hasCamera()) { requestCamera(); return }
        ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        if (!Engine.calibratedOnce) Engine.startCalibration()
        // So your normal keyboard doesn't pop up while typing with the camera.
        if (imeEnabled() && !imeSelected()) ui.postDelayed({ imm().showInputMethodPicker() }, 600)
        ui.postDelayed({ refresh() }, 800)
    }

    private fun fitToHands() {
        overlay.editable = false
        if (!Engine.running) {
            if (!hasCamera()) { requestCamera(); return }
            ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
        }
        Engine.startCalibration()
    }

    private fun stopTracking() {
        stopService(Intent(this, CameraService::class.java))
        ui.postDelayed({ refresh() }, 300)
    }

    private fun preset(front: Boolean) {
        Engine.frontCamera = front
        Engine.flipped = front
        Engine.resetCorners()
        Engine.save()
        CameraService.instance?.restartCamera() ?: startTracking()
        refresh()
    }

    private fun imm() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun imeEnabled() = imm().enabledInputMethodList.any { it.packageName == packageName }

    private fun imeSelected(): Boolean {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: return false
        return id.startsWith("$packageName/")
    }

    // ------------------------------------------------------------ ui

    private fun refresh() {
        btnCamera.text = if (hasCamera()) "1 · Camera ✓" else "1 · Camera"
        btnOverlay.text = if (Settings.canDrawOverlays(this)) "2 · Floating window ✓" else "2 · Floating window"
        btnEnable.text = if (imeEnabled()) "3 · Keyboard on ✓" else "3 · Turn on keyboard"
        btnPick.text = if (imeSelected()) "4 · Keyboard chosen ✓" else "4 · Choose keyboard"
        btnFlip.text = if (Engine.flipped) "⇅ Flipped" else "⇅ Flip"
        btnRows.text = if (Engine.board.numbers) "123 row: on" else "123 row: off"
        btnWindow.text = if (Engine.showWindow) "Window: on" else "Window: off"
        btnZones.text = if (Engine.fingerZones) "Finger zones: on" else "Finger zones: off"
        btnFollow.text = if (Engine.followHands) "Follow hands: on" else "Follow hands: off"
        btnSwipe.text = if (Engine.swipeBackspace) "Swipe ⌫: on" else "Swipe ⌫: off"
        btnAuto.text = if (Engine.autocorrect) "Autocorrect: on" else "Autocorrect: off"
        btnZoom.text = if (Engine.zoomToKeyboard) "Zoom to keyboard: on" else "Zoom to keyboard: off"
        if (Engine.calibrating) setRail(btnFit, "✕", "Cancel") else setRail(btnFit, "✋", "Fit")
        btnFit.setTextColor(if (Engine.calibrating) 0xFF5EEAD4.toInt() else Color.WHITE)
        if (overlay.editable) setRail(btnAdjust, "✓", "Done") else setRail(btnAdjust, "✥", "Adjust")
        setRail(btnSetup, "⚙", if (showSetup) "Close" else "Setup")
        btnRailSide.text = if (Engine.railLeft) "Buttons: left side" else "Buttons: right side"
        btnAdjust.setTextColor(if (overlay.editable) 0xFF5EEAD4.toInt() else Color.WHITE)
        updateTypingButton()
        btnSetup.setTextColor(if (showSetup) 0xFF5EEAD4.toInt() else Color.WHITE)
        updateStatus()
    }

    private fun updateTypingButton() {
        val needsKeyboard = Engine.typingEnabled && !Engine.imeActive
        when {
            needsKeyboard -> setRail(btnTypingTop, "⌨", "Choose")
            Engine.typingEnabled -> setRail(btnTypingTop, "⌨", "On")
            else -> setRail(btnTypingTop, "⌨", "Off")
        }
        btnTypingTop.setTextColor(
            when {
                needsKeyboard -> 0xFFFCA5A5.toInt()
                Engine.typingEnabled -> 0xFF5EEAD4.toInt()
                else -> Color.LTGRAY
            }
        )
    }

    private fun updateStatus() {
        val parts = mutableListOf<String>()
        if (!Engine.running) {
            parts.add(if (hasCamera()) "Camera off — tap ⚙ then Start camera" else "Tap ⚙ to set up")
        } else if (!Engine.modelReady) {
            parts.add("Loading hand tracking…")
        } else {
            val hc = Engine.homeCount
            parts.add(if (hc < 0) "No hands in view" else if (hc == 8) "Home row ✓ 8/8" else "Home row $hc/8")
            Engine.lastPressed?.let { parts.add("last: ${it.label}" + if (Engine.lastDelivered) "" else " (not typed)") }
        }
        if (overlay.editable) parts.add("drag the corners")
        if (!Engine.calibratedOnce && Engine.running && !Engine.calibrating) parts.add("tap ✋ Fit to line up the keyboard")
        status.text = parts.joinToString("  ·  ")
        if (Engine.calibrating) setRail(btnFit, "✕", "Cancel") else setRail(btnFit, "✋", "Fit")
        updateTypingButton()
        btnStart.text = if (Engine.running) "■ Stop camera" else "▶ Start camera"
        if (Engine.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setTextColor(0xFF5EEAD4.toInt())
        setPadding(0, px(8), 0, px(2))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        setOnClickListener { onClick() }
    }

    /** Big square button for the side rail. */
    private fun railButton(onClick: () -> Unit) = Button(this).apply {
        isAllCaps = false
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xCC1D222B.toInt())
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(px(2), px(4), px(2), px(4))
        layoutParams = LinearLayout.LayoutParams(px(68), px(66)).apply { setMargins(0, px(4), 0, px(4)) }
        setOnClickListener { onClick() }
    }

    /** Icon on top (larger), label underneath. */
    private fun setRail(b: Button, icon: String, label: String) {
        val sp = android.text.SpannableString("$icon\n$label")
        sp.setSpan(android.text.style.RelativeSizeSpan(1.7f), 0, icon.length, 0)
        b.text = sp
    }

    /** Put the rail on the chosen side; the setup panel and camera zoom keep clear of it. */
    private fun placeRail(railWidth: Int) {
        val left = Engine.railLeft
        (rail.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.gravity = (if (left) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            rail.layoutParams = it
        }
        (status.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.TOP or (if (left) Gravity.END else Gravity.START)
            it.setMargins(if (left) 0 else px(6), px(6), if (left) px(6) else 0, 0)
            status.layoutParams = it
        }
        (setupPanel.layoutParams as? android.widget.FrameLayout.LayoutParams)?.let {
            it.setMargins(if (left) railWidth else 0, 0, if (left) 0 else railWidth, 0)
            setupPanel.layoutParams = it
        }
        overlay.leftInset = if (left) railWidth.toFloat() else 0f
        overlay.rightInset = if (left) 0f else railWidth.toFloat()
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = px(44)
        setPadding(px(10), 0, px(10), 0)
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF1D222B.toInt())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)).apply {
            setMargins(px(3), 0, 0, 0)
        }
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): View {
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) line.addView(v)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(line)
        }
    }
}
