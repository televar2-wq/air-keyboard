package com.motionpuppet

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Capture screen: tracks up to two bodies and records only their landmarks.
 * The camera picture is shown for framing but never saved.
 */
class MainActivity : ComponentActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var executor: ExecutorService
    private lateinit var tracker: PoseTracker
    private lateinit var view: SkeletonView
    private lateinit var status: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnCamera: Button
    private lateinit var btnPreview: Button
    private lateinit var btnList: Button
    private var d = 1f

    private var front = true
    private var provider: ProcessCameraProvider? = null

    @Volatile private var recording = false
    private val frames = ArrayList<PoseFrame>()
    private var recStart = 0L
    private var lastPeople = 0
    @Volatile private var aspect = 3f / 4f

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else status.text = "Camera permission is needed to track movement."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        d = resources.displayMetrics.density
        executor = Executors.newSingleThreadExecutor()
        tracker = PoseTracker(this)
        executor.execute { tracker.init() }
        front = getPreferences(MODE_PRIVATE).getBoolean("front", true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = SkeletonView(this)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(px(10), px(6), px(10), px(6))
            setBackgroundColor(0x990E1116.toInt())
            text = "Starting camera…"
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(px(8), px(8), 0, 0)
        })

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(4), px(4), px(4), px(4))
            setBackgroundColor(0x990E1116.toInt())
        }
        btnRecord = railButton { toggleRecording() }
        btnCamera = railButton {
            front = !front
            getPreferences(MODE_PRIVATE).edit().putBoolean("front", front).apply()
            startCamera()
            refresh()
        }
        btnPreview = railButton {
            view.showCamera = !view.showCamera
            refresh()
        }
        btnList = railButton { startActivity(Intent(this, RecordingsActivity::class.java)) }
        rail.addView(btnRecord)
        rail.addView(btnCamera)
        rail.addView(btnPreview)
        rail.addView(btnList)
        root.addView(rail, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        rail.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> view.rightInset = v.width.toFloat() }

        setContentView(root)
        refresh()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    // ------------------------------------------------------------ camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(view.display?.rotation ?: 0)
                    .build()
                analysis.setAnalyzer(executor) { img -> analyze(img) }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(this, selector, analysis)
                view.mirror = front
            } catch (e: Exception) {
                Log.e("MotionPuppet", "camera failed", e)
                status.text = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(img: ImageProxy) {
        try {
            val raw = img.toBitmap()
            val m = Matrix().apply { postRotate(img.imageInfo.rotationDegrees.toFloat()) }
            // Upright but NOT mirrored, so left/right body parts are the person's real left/right.
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            aspect = bmp.width.toFloat() / bmp.height.toFloat()
            val now = SystemClock.uptimeMillis()
            val people = if (tracker.ready) tracker.detect(bmp, now) else emptyList()
            if (recording) synchronized(frames) { frames.add(PoseFrame(now, people)) }
            lastPeople = people.size
            view.frame = bmp
            view.people = people
            view.aspect = aspect
            view.postInvalidate()
        } catch (e: Exception) {
            Log.e("MotionPuppet", "analyze failed", e)
        } finally {
            img.close()
        }
    }

    // Rotating or folding/unfolding: rebind so the picture stays upright.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (provider != null && !recording) ui.postDelayed({ startCamera() }, 300)
    }

    // ------------------------------------------------------------ recording

    private fun toggleRecording() {
        if (recording) { stopRecording(); return }
        if (view.countdown > 0) { view.countdown = 0; ui.removeCallbacksAndMessages(COUNTDOWN); refresh(); return }
        // 3-second countdown so you can step into place.
        view.countdown = 3
        refresh()
        fun step() {
            ui.postAtTime({
                view.countdown -= 1
                view.invalidate()
                if (view.countdown <= 0) startRecording() else step()
                refresh()
            }, COUNTDOWN, SystemClock.uptimeMillis() + 1000)
        }
        step()
    }

    private fun startRecording() {
        synchronized(frames) { frames.clear() }
        recStart = SystemClock.uptimeMillis()
        recording = true
        view.recording = true
    }

    private fun stopRecording() {
        recording = false
        view.recording = false
        val copy = synchronized(frames) { ArrayList(frames).also { frames.clear() } }
        refresh()
        if (copy.none { it.people.isNotEmpty() }) {
            Toast.makeText(this, "Nobody was tracked — nothing saved", Toast.LENGTH_LONG).show()
            return
        }
        val a = aspect
        Thread {
            try {
                val f = RecordingStore.save(this, copy, a)
                ui.post {
                    Toast.makeText(this, "Saved — opening the stage", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, StageActivity::class.java).putExtra(StageActivity.EXTRA_FILE, f.name))
                }
            } catch (e: Exception) {
                ui.post { Toast.makeText(this, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ------------------------------------------------------------ ui

    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 250)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        if (recording) stopRecording()
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        executor.execute { tracker.close() }
        executor.shutdown()
        super.onDestroy()
    }

    private fun updateStatus() {
        status.text = when {
            tracker.error != null -> "Body tracking failed: ${tracker.error}"
            !tracker.ready -> "Loading body tracking…"
            recording -> {
                val s = (SystemClock.uptimeMillis() - recStart) / 1000
                "● Recording ${s / 60}:${"%02d".format(s % 60)} · $lastPeople ${if (lastPeople == 1) "person" else "people"}"
            }
            view.countdown > 0 -> "Get ready…"
            lastPeople == 0 -> "Step into view — whole body if you can"
            else -> "Tracking $lastPeople ${if (lastPeople == 1) "person" else "people"} · only the movement is recorded"
        }
    }

    private fun refresh() {
        when {
            recording -> setRail(btnRecord, "■", "Stop")
            view.countdown > 0 -> setRail(btnRecord, "✕", "Cancel")
            else -> setRail(btnRecord, "⏺", "Record")
        }
        btnRecord.setTextColor(if (recording) 0xFFEF4444.toInt() else Color.WHITE)
        setRail(btnCamera, "⟲", if (front) "Front cam" else "Rear cam")
        setRail(btnPreview, "👁", if (view.showCamera) "Picture on" else "Picture off")
        setRail(btnList, "📁", "Recordings")
        updateStatus()
    }

    private fun px(v: Int) = (v * d).toInt()

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
        layoutParams = LinearLayout.LayoutParams(px(72), px(68)).apply { setMargins(0, px(4), 0, px(4)) }
        setOnClickListener { onClick() }
    }

    private fun setRail(b: Button, icon: String, label: String) {
        val sp = SpannableString("$icon\n$label")
        sp.setSpan(RelativeSizeSpan(1.7f), 0, icon.length, 0)
        b.text = sp
    }

    companion object {
        private val COUNTDOWN = Any()
    }
}
