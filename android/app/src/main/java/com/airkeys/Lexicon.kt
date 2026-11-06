package com.airkeys

import android.content.Context
import android.util.Log
import kotlin.math.ln

/**
 * English word list with frequencies, used by autocorrect. Loaded once in the
 * background from assets/words.txt ("word count" per line, most common first;
 * a plain one-word-per-line list also works).
 */
object Lexicon {
    @Volatile var ready = false
        private set
    @Volatile var size = 0
        private set

    /** Words grouped by length: words[len] and their log probabilities logp[len]. */
    var words: Array<Array<String>> = emptyArray()
        private set
    var logp: Array<FloatArray> = emptyArray()
        private set

    private const val MAX_LEN = 24
    private const val MAX_WORDS = 60000
    @Volatile private var loading = false

    fun load(ctx: Context) {
        if (ready || loading) return
        loading = true
        val app = ctx.applicationContext
        Thread {
            try {
                val list = ArrayList<String>()
                val counts = ArrayList<Double>()
                val seen = HashSet<String>()
                app.assets.open("words.txt").bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (list.size >= MAX_WORDS) break
                        val parts = line.trim().split(' ', '\t')
                        val w = parts[0].lowercase()
                        if (w.isEmpty() || w.length > MAX_LEN) continue
                        if (!w.all { it in 'a'..'z' || it == '\'' }) continue
                        if (w.length == 1 && w != "a" && w != "i") continue
                        if (w.startsWith('\'') || !seen.add(w)) continue
                        val c = parts.getOrNull(1)?.toDoubleOrNull()
                        list.add(w)
                        counts.add(c ?: (1e6 / (list.size + 10)))     // no counts: Zipf by rank
                    }
                }
                val total = counts.sum().coerceAtLeast(1.0)
                val byLenW = Array(MAX_LEN + 1) { ArrayList<String>() }
                val byLenP = Array(MAX_LEN + 1) { ArrayList<Float>() }
                for (i in list.indices) {
                    val w = list[i]
                    byLenW[w.length].add(w)
                    byLenP[w.length].add(ln(counts[i] / total).toFloat())
                }
                words = Array(MAX_LEN + 1) { byLenW[it].toTypedArray() }
                logp = Array(MAX_LEN + 1) { byLenP[it].toFloatArray() }
                size = list.size
                ready = list.isNotEmpty()
                Log.i("AirKeys", "Lexicon loaded: $size words")
            } catch (e: Exception) {
                Log.w("AirKeys", "No word list; autocorrect off", e)
            } finally {
                loading = false
            }
        }.start()
    }
}
