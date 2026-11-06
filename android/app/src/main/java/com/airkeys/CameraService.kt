package com.airkeys

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that owns the camera and the hand tracker, so tracking
 * keeps going while you type in other apps. Also hosts the floating window.
 */
class CameraService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.airkeys.STOP"
        private const val CHANNEL = "camera"
        private const val NOTIF_ID = 7
        private const val TAG = "AirKeys"

        @Volatile
        var instance: CameraService? = null
            private set
    }

    private lateinit var executor: ExecutorService
    private lateinit var window: FloatingWindow
    private var landmarker: HandLandmarker? = null
    private var usingGpu = false
    private var lastTs = 0L
    private var provider: ProcessCameraProvider? = null
    private var suppressWindow = false
    private var analysis: ImageAnalysis? = null

    // Keep the camera image upright when the phone is rotated or folded/unfolded.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            analysis?.targetRotation = currentRotation()
        }
    }

    private fun currentRotation(): Int {
        val dm = getSystemService(DisplayManager::class.java)
        return dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Engine.load(this)
        executor = Executors.newSingleThreadExecutor()
        window = FloatingWindow(this)
        suppressWindow = MainActivity.visible
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Camera tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            Engine.status = "Couldn't start camera service: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        if (landmarker == null) executor.execute { initLandmarker(true) }
        if (!Engine.running) startCamera()
        refreshWindow()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Air Keys is watching your hands")
            .setContentText("Tap to adjust the keyboard")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    // ------------------------------------------------------------ window

    fun setWindowSuppressed(s: Boolean) {
        suppressWindow = s
        refreshWindow()
    }

    fun refreshWindow() {
        if (Engine.showWindow && !suppressWindow) window.show() else window.hide()
    }

    // ------------------------------------------------------------ camera

    fun restartCamera() {
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val a = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(640, 480),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(currentRotation())
                    .build()
                a.setAnalyzer(executor) { img -> analyze(img) }
                analysis = a
                val selector = if (Engine.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                               else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(this, selector, a)
                Engine.running = true
                Engine.status = if (Engine.frontCamera) "Front camera on" else "Rear camera on"
            } catch (e: Exception) {
                Log.e(TAG, "camera bind failed", e)
                Engine.running = false
                Engine.status = "Camera error: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ------------------------------------------------------------ tracking

    private fun initLandmarker(gpu: Boolean) {
        try {
            landmarker?.close()
            landmarker = HandLandmarker.createFromOptions(this, options(gpu))
            usingGpu = gpu
            Engine.modelReady = true
        } catch (e: Exception) {
            Log.e(TAG, "hand model init failed (gpu=$gpu)", e)
            landmarker = null
            if (gpu) initLandmarker(false)
            else Engine.status = "Hand model failed to load: ${e.message}"
        }
    }

    private fun options(gpu: Boolean): HandLandmarker.HandLandmarkerOptions {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
            .build()
        return HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(2)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
    }

    private fun analyze(img: ImageProxy) {
        try {
            val raw = img.toBitmap()
            val m = Matrix()
            m.postRotate(img.imageInfo.rotationDegrees.toFloat())
            if (Engine.frontCamera) m.postScale(-1f, 1f)      // selfie view: your left hand on the left
            val bmp = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)

            val now = SystemClock.uptimeMillis()
            val ts = if (now <= lastTs) lastTs + 1 else now
            lastTs = ts

            val hands = ArrayList<FloatArray>()
            val lm = landmarker
            if (lm != null) {
                try {
                    val res = lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
                    for (hand in res.landmarks()) {
                        val a = FloatArray(63)
                        for (i in 0 until minOf(21, hand.size)) {
                            a[i * 3] = hand[i].x()
                            a[i * 3 + 1] = hand[i].y()
                            a[i * 3 + 2] = hand[i].z()
                        }
                        hands.add(a)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "detect failed", e)
                    if (usingGpu) initLandmarker(false)
                }
            }
            Engine.process(bmp, hands, ts)
        } catch (e: Exception) {
            Log.e(TAG, "analyze failed", e)
        } finally {
            img.close()
        }
    }

    override fun onDestroy() {
        instance = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        window.hide()
        try { provider?.unbindAll() } catch (_: Exception) {}
        Engine.running = false
        Engine.status = "Camera off"
        Engine.clearLive()
        executor.execute {
            landmarker?.close()
            landmarker = null
            Engine.modelReady = false
        }
        executor.shutdown()
        super.onDestroy()
    }
}
