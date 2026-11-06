package com.airkeys

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The system keyboard. While the camera is running it shows no panel at all
 * (so nothing covers the app you're typing in) and types the taps the camera
 * detects. When the camera is off it shows a slim bar with status and a few
 * buttons, and when the camera stops it hands back to your previous keyboard.
 */
class AirKeyboardIme : InputMethodService() {

    private val ui = Handler(Looper.getMainLooper())
    private var shift = false
    private var caps = false
    private var statusView: TextView? = null
    private var typingBtn: Button? = null
    private var shiftBtn: Button? = null
    private var d = 1f

    private val listener: (Tap) -> Unit = { onAirKey(it) }
    private val decoder = WordDecoder()
    private var capFirst = false

    private val stateListener: () -> Unit = {
        if (Engine.running) {
            updateInputViewShown()
            requestHideSelf(0)
        } else {
            // Camera stopped: go back to the keyboard you used before.
            val switched = Build.VERSION.SDK_INT >= 28 &&
                try { switchToPreviousInputMethod() } catch (_: Exception) { false }
            if (!switched) updateInputViewShown()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateBar()
            ui.postDelayed(this, 400)
        }
    }

    private val shifted = mapOf(
        "`" to "~", "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%", "6" to "^",
        "7" to "&", "8" to "*", "9" to "(", "0" to ")", "-" to "_", "=" to "+",
        "[" to "{", "]" to "}", "\\" to "|", ";" to ":", "'" to "\"", "," to "<", "." to ">", "/" to "?"
    )

    override fun onCreate() {
        super.onCreate()
        Engine.load(this)
        Lexicon.load(this)
        Engine.keyListeners.add(listener)
        Engine.stateListeners.add(stateListener)
        Engine.imeActive = true
        d = resources.displayMetrics.density
    }

    // Report which app/field we're attached to, so the camera view can show it.
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        resetWord()
        Engine.imeActive = true
        val pkg = attribute?.packageName
        val isEditor = attribute != null && attribute.inputType != InputType.TYPE_NULL
        Engine.imeTarget = if (pkg != null && isEditor && pkg != packageName) appLabel(pkg)
                           else if (pkg == packageName && isEditor) "Air Keys test field"
                           else null
    }

    // If the cursor moves away from the word being typed, start a new word.
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (!decoder.isEmpty() && candidatesEnd != -1 && (newSelStart != newSelEnd || newSelEnd != candidatesEnd)) {
            currentInputConnection?.finishComposingText()
            resetWord()
        }
    }

    override fun onFinishInput() {
        resetWord()
        Engine.imeTarget = null
        super.onFinishInput()
    }

    private fun appLabel(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Exception) {
        pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    override fun onDestroy() {
        Engine.imeActive = false
        Engine.imeTarget = null
        Engine.keyListeners.remove(listener)
        Engine.stateListeners.remove(stateListener)
        ui.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF161A21.toInt())
            setPadding(px(6), px(4), px(6), px(6))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            maxLines = 2
        }
        top.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        typingBtn = key("") {
            Engine.typingEnabled = !Engine.typingEnabled
            Engine.save()
            updateBar()
        }
        top.addView(typingBtn)
        top.addView(key("⚙") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        root.addView(top)

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shiftBtn = key("⇧") { shift = !shift; updateBar() }
        keys.addView(shiftBtn, weight(1f))
        keys.addView(key("⌫") { currentInputConnection?.let { deleteBack(it) } }, weight(1f))
        keys.addView(key("space") { currentInputConnection?.let { finishWord(it); it.commitText(" ", 1) } }, weight(3f))
        keys.addView(key("↵") { currentInputConnection?.let { finishWord(it) }; sendKeyChar('\n') }, weight(1f))
        keys.addView(key("🌐") {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, weight(1f))
        root.addView(keys)

        updateBar()
        return root
    }

    /** No on-screen panel while the camera keyboard is active. */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return !Engine.running
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        ui.removeCallbacks(tick)
        super.onFinishInputView(finishingInput)
    }

    // ------------------------------------------------------------ typing

    private val wordSymbols = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ";", "'", "[", "]", "\\", "-", "=", "/", "`")

    private fun onAirKey(t: Tap) {
        if (!Engine.typingEnabled) return
        val ic = currentInputConnection ?: return
        val l = t.key.label
        val auto = Engine.autocorrect && Lexicon.ready
        val isLetter = l.length == 1 && l[0].isLetter()
        when {
            l == "⌫" -> deleteBack(ic)
            l == "Shift" -> shift = !shift
            l == "Caps" -> caps = !caps
            l == "Ctrl" || l == "Win" || l == "Alt" || l == "Fn" || l == "Menu" -> {}
            // Letters, and symbol keys hit by mistake in the middle of a word, go to the decoder.
            auto && (isLetter || (!decoder.isEmpty() && l in wordSymbols)) -> {
                if (decoder.isEmpty()) capFirst = shift
                decoder.add(t)
                shift = false
                showGuess(ic)
            }
            else -> {
                finishWord(ic)
                when (l) {
                    "Space" -> ic.commitText(" ", 1)
                    "Enter" -> sendKeyChar('\n')
                    "Tab" -> ic.commitText("\t", 1)
                    else -> {
                        val out = if (isLetter) {
                            if (shift xor caps) l.uppercase() else l.lowercase()
                        } else {
                            if (shift) shifted[l] ?: l else l
                        }
                        ic.commitText(out, 1)
                        shift = false
                    }
                }
            }
        }
        updateBar()
    }

    /** Backspace: inside a word, drop the last tap and re-guess; otherwise delete a character. */
    private fun deleteBack(ic: android.view.inputmethod.InputConnection) {
        if (!decoder.isEmpty()) {
            decoder.removeLast()
            if (decoder.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                resetWord()
            } else showGuess(ic)
        } else backspace()
    }

    /** Show autocorrect's current best guess, underlined, in the text field. */
    private fun showGuess(ic: android.view.inputmethod.InputConnection) {
        val w = decoder.best(Engine.board, Engine.fingerZones)
        val shown = when {
            caps -> w.uppercase()
            capFirst -> w.replaceFirstChar { it.uppercase() }
            else -> w
        }
        Engine.composing = shown
        ic.setComposingText(shown, 1)
    }

    private fun finishWord(ic: android.view.inputmethod.InputConnection) {
        if (!decoder.isEmpty()) ic.finishComposingText()
        resetWord()
    }

    private fun resetWord() {
        decoder.clear()
        capFirst = false
        Engine.composing = ""
    }

    private fun backspace() {
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    // ------------------------------------------------------------ ui

    private fun updateBar() {
        val s = StringBuilder()
        s.append(
            when {
                !Engine.running -> "Camera off — tap ⚙ to start"
                !Engine.modelReady -> "Loading hand tracking…"
                Engine.homeCount < 0 -> "No hands in view"
                else -> "Home row ${Engine.homeCount}/8"
            }
        )
        Engine.lastPressed?.let { s.append("  ·  last: ").append(it.label) }
        if (caps) s.append("  ·  CAPS")
        statusView?.text = s.toString()
        typingBtn?.text = if (Engine.typingEnabled) "typing on" else "typing off"
        typingBtn?.setTextColor(if (Engine.typingEnabled) 0xFF5EEAD4.toInt() else Color.LTGRAY)
        shiftBtn?.setTextColor(if (shift) 0xFF5EEAD4.toInt() else Color.WHITE)
    }

    private fun px(v: Int) = (v * d).toInt()

    private fun weight(w: Float) =
        LinearLayout.LayoutParams(0, px(48), w).apply { setMargins(px(2), px(2), px(2), px(2)) }

    private fun key(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF1D222B.toInt())
        setOnClickListener { onClick() }
    }
}
