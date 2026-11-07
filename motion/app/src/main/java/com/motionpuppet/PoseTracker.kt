package com.motionpuppet

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.hypot

/**
 * Finds up to two people's body landmarks in camera frames and keeps a stable
 * id (0 or 1) for each person from frame to frame.
 */
class PoseTracker(private val ctx: Context) {

    private var landmarker: PoseLandmarker? = null
    private var usingGpu = false
    private var lastTs = 0L

    private class Track(val id: Int, var x: Float, var y: Float, var lastSeen: Long)
    private val tracks = ArrayList<Track>()

    var ready = false
        private set
    var error: String? = null
        private set

    fun init() {
        create(true)
    }

    private fun create(gpu: Boolean) {
        try {
            landmarker?.close()
            val base = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_full.task")
                .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
                .build()
            val opts = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(2)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = PoseLandmarker.createFromOptions(ctx, opts)
            usingGpu = gpu
            ready = true
            error = null
        } catch (e: Exception) {
            Log.e("MotionPuppet", "pose model init failed (gpu=$gpu)", e)
            landmarker = null
            if (gpu) create(false) else { ready = false; error = e.message ?: "Body tracking failed to start" }
        }
    }

    /** Runs tracking on one upright, un-mirrored camera frame. */
    fun detect(bmp: Bitmap, now: Long): List<PersonPose> {
        val lm = landmarker ?: return emptyList()
        val ts = if (now <= lastTs) lastTs + 1 else now
        lastTs = ts
        val result = try {
            lm.detectForVideo(BitmapImageBuilder(bmp).build(), ts)
        } catch (e: Exception) {
            Log.e("MotionPuppet", "detect failed", e)
            if (usingGpu) create(false)
            return emptyList()
        }
        val norms = result.landmarks()
        val worlds = result.worldLandmarks()
        val out = ArrayList<PersonPose>()
        val used = HashSet<Track>()
        for (k in norms.indices) {
            val n = norms[k]
            val w = worlds.getOrNull(k) ?: continue
            if (n.size < 33 || w.size < 33) continue
            val norm = FloatArray(66)
            val world = FloatArray(99)
            val vis = FloatArray(33)
            for (i in 0 until 33) {
                norm[i * 2] = n[i].x()
                norm[i * 2 + 1] = n[i].y()
                world[i * 3] = w[i].x()
                world[i * 3 + 1] = w[i].y()
                world[i * 3 + 2] = w[i].z()
                vis[i] = n[i].visibility().orElse(1f)
            }
            val hx = (norm[46] + norm[48]) / 2f
            val hy = (norm[47] + norm[49]) / 2f
            // Same person as last frame = nearest hip position.
            var best: Track? = null
            var bd = 0.3f
            for (t in tracks) {
                if (t in used) continue
                val d = hypot(t.x - hx, t.y - hy)
                if (d < bd) { bd = d; best = t }
            }
            if (best == null) {
                val free = (0..1).firstOrNull { id -> tracks.none { it.id == id } } ?: continue
                best = Track(free, hx, hy, now).also { tracks.add(it) }
            }
            used.add(best)
            best.x = hx; best.y = hy; best.lastSeen = now
            out.add(PersonPose(best.id, world, norm, vis))
        }
        tracks.removeAll { now - it.lastSeen > 1500L }
        return out.sortedBy { it.id }
    }

    fun close() {
        try { landmarker?.close() } catch (_: Exception) {}
        landmarker = null
        ready = false
    }
}
