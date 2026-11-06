package com.airkeys

import kotlin.math.max
import kotlin.math.min

/** One detected tap: the key it resolved to, where it landed (key units) and which finger. */
class Tap(val key: Key, val u: Float, val v: Float, val side: Char, val finger: Int)

/**
 * Turns the taps of the word being typed into the most likely word.
 *
 * Score of a candidate word = how well the taps line up with its letters
 * (distance from each tap to the letter's key, plus a penalty for an extra or a
 * missed tap) minus how common the word is. The literal letters tapped also
 * compete, as an "unknown word", so names and new words still come through
 * when the taps are clean.
 */
class WordDecoder {

    private val taps = ArrayList<Tap>()

    val count: Int get() = taps.size
    fun isEmpty() = taps.isEmpty()
    fun clear() = taps.clear()
    fun add(t: Tap) { taps.add(t) }
    fun removeLast() { if (taps.isNotEmpty()) taps.removeAt(taps.size - 1) }

    companion object {
        const val ALPHABET = "abcdefghijklmnopqrstuvwxyz'"
        private const val SIGMA_U = 0.5f          // key widths
        private const val SIGMA_V = 0.55f         // rows
        private const val ZONE_PENALTY = 1.2f     // letter belongs to a different finger
        private const val EXTRA_TAP = 4.5f        // a tap that wasn't meant (cost)
        private const val MISSED_TAP = 5.5f       // a letter with no tap (cost)
        private const val UNKNOWN_WORD = 18f      // prior cost of a word not in the list
        private const val SYMBOL_IN_WORD = 8f     // literal reading containing a stray symbol
        private const val INF = 1e9f
    }

    private fun idx(ch: Char): Int = when (ch) {
        in 'a'..'z' -> ch - 'a'
        '\'' -> 26
        else -> -1
    }

    private var cachedBoard: Board? = null
    private val letterKeys = arrayOfNulls<Key>(ALPHABET.length)

    private fun keysFor(board: Board) {
        if (cachedBoard === board) return
        cachedBoard = board
        for (i in ALPHABET.indices) {
            val ch = ALPHABET[i]
            letterKeys[i] = board.keys.firstOrNull { it.label.length == 1 && it.label[0].lowercaseChar() == ch }
        }
    }

    /** cost[i][c]: how unlikely tap i is if letter c was meant. */
    private fun tapCosts(board: Board, zones: Boolean): Array<FloatArray> {
        keysFor(board)
        return Array(taps.size) { i ->
            val t = taps[i]
            FloatArray(ALPHABET.length) { c ->
                val k = letterKeys[c]
                if (k == null) INF else {
                    val du = (t.u - k.centerU) / SIGMA_U
                    val dv = (t.v - (k.row + 0.5f)) / SIGMA_V
                    var cost = 0.5f * (du * du + dv * dv)
                    if (zones && t.finger > 0) {
                        val (s, f) = board.fingerFor(k)
                        if (s != t.side || f != t.finger) cost += ZONE_PENALTY
                    }
                    cost
                }
            }
        }
    }

    /** The literal letters (or symbols) the taps resolved to. */
    fun literal(): String = buildString {
        for (t in taps) {
            val l = t.key.label
            append(if (l.length == 1) l.lowercase() else "")
        }
    }

    /** Best guess for the current word, lowercase. */
    fun best(board: Board, zones: Boolean): String {
        val n = taps.size
        if (n == 0) return ""
        val lit = literal()
        if (!Lexicon.ready) return lit
        val cost = tapCosts(board, zones)

        // The literal reading, as an unknown word.
        var bestWord = lit
        var bestScore = UNKNOWN_WORD
        for (i in 0 until n) {
            val c = idx(lit.getOrNull(i) ?: ' ')
            bestScore += if (c >= 0) cost[i][c] else SYMBOL_IN_WORD
        }

        val minLen = max(1, n - 2)
        val maxLen = min(Lexicon.words.size - 1, n + 1)
        val row = FloatArray(maxLen + 2)
        val prevRow = FloatArray(maxLen + 2)
        for (len in minLen..maxLen) {
            val ws = Lexicon.words[len]
            val lp = Lexicon.logp[len]
            for (wi in ws.indices) {
                val prior = -lp[wi]
                if (prior >= bestScore) continue
                val w = ws[wi]
                val s = align(w, cost, n, prevRow, row, bestScore - prior)
                val total = s + prior
                if (total < bestScore) { bestScore = total; bestWord = w }
            }
        }
        return bestWord
    }

    /** Edit-distance alignment of taps to the word's letters; gives up early above [limit]. */
    private fun align(w: String, cost: Array<FloatArray>, n: Int, prev: FloatArray, cur: FloatArray, limit: Float): Float {
        val m = w.length
        // prev = row for i taps used; columns = letters used
        prev[0] = 0f
        for (j in 1..m) prev[j] = j * MISSED_TAP
        var a = prev
        var b = cur
        for (i in 1..n) {
            b[0] = i * EXTRA_TAP
            var rowMin = b[0]
            val ci = cost[i - 1]
            for (j in 1..m) {
                val c = idx(w[j - 1])
                val sub = a[j - 1] + (if (c >= 0) ci[c] else INF)
                val ins = a[j] + EXTRA_TAP
                val del = b[j - 1] + MISSED_TAP
                val v = min(sub, min(ins, del))
                b[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return INF
            val t = a; a = b; b = t
        }
        return a[m]
    }
}
