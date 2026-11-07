package com.motionpuppet

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One tracked person in one frame. Only body landmarks: no image is ever stored. */
class PersonPose(
    val id: Int,
    val world: FloatArray,      // 33 x (x, y, z) in meters, hip-centered
    val norm: FloatArray,       // 33 x (x, y) in the camera image, 0..1
    val vis: FloatArray         // 33 visibility scores
)

class PoseFrame(val t: Long, val people: List<PersonPose>)

data class RecordingInfo(val file: File, val name: String, val durationMs: Long, val people: Int)

/** Saves and lists recordings in the app's private storage. */
object RecordingStore {

    fun dir(ctx: Context): File = File(ctx.filesDir, "public/recordings").apply { mkdirs() }

    fun save(ctx: Context, frames: List<PoseFrame>, aspect: Float): File {
        val now = Date()
        val file = File(dir(ctx), "rec_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + ".json")
        val name = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(now)
        val t0 = frames.firstOrNull()?.t ?: 0L
        val ids = HashSet<Int>()
        file.bufferedWriter().use { w ->
            w.write("{\"version\":1,\"name\":${JSONObject.quote(name)},\"aspect\":${fmt(aspect)},\"frames\":[")
            frames.forEachIndexed { i, f ->
                if (i > 0) w.write(",")
                w.write("{\"t\":${f.t - t0},\"people\":[")
                f.people.forEachIndexed { j, p ->
                    ids.add(p.id)
                    if (j > 0) w.write(",")
                    w.write("{\"id\":${p.id},\"w\":")
                    writeArray(w, p.world)
                    w.write(",\"n\":")
                    writeArray(w, p.norm)
                    w.write(",\"v\":")
                    writeArray(w, p.vis)
                    w.write("}")
                }
                w.write("]}")
            }
            w.write("],\"peopleCount\":${ids.size},\"durationMs\":${(frames.lastOrNull()?.t ?: t0) - t0}}")
        }
        return file
    }

    private fun writeArray(w: java.io.Writer, a: FloatArray) {
        w.write("[")
        for (i in a.indices) {
            if (i > 0) w.write(",")
            w.write(fmt(a[i]))
        }
        w.write("]")
    }

    private fun fmt(v: Float): String {
        if (v.isNaN() || v.isInfinite()) return "0"
        val r = Math.round(v * 10000f) / 10000f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    /** Recordings, newest first. Reads only the small summary at the end of each file. */
    fun list(ctx: Context): List<RecordingInfo> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { f ->
                var name = f.nameWithoutExtension
                var dur = 0L
                var people = 0
                try {
                    val head = f.bufferedReader().use { r -> CharArray(300).let { buf -> String(buf, 0, r.read(buf).coerceAtLeast(0)) } }
                    Regex("\"name\":\"([^\"]*)\"").find(head)?.let { name = it.groupValues[1] }
                    val tail = f.readTailString(200)
                    Regex("\"peopleCount\":(\\d+)").find(tail)?.let { people = it.groupValues[1].toInt() }
                    Regex("\"durationMs\":(\\d+)").find(tail)?.let { dur = it.groupValues[1].toLong() }
                } catch (_: Exception) {
                }
                RecordingInfo(f, name, dur, people)
            }

    private fun File.readTailString(n: Int): String {
        java.io.RandomAccessFile(this, "r").use { raf ->
            val start = (raf.length() - n).coerceAtLeast(0)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            return String(bytes)
        }
    }
}
