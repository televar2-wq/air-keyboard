package com.motionpuppet

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The stage: a 3D view (three.js in a WebView) that plays a recording with a
 * character on it. The app side handles picking character files and saving videos.
 */
class StageActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE = "file"
        private const val HOST = "https://appassets.androidplatform.net"
    }

    private lateinit var web: WebView
    private var pendingSlot = 0
    private var videoTmp: File? = null
    private var videoOut: FileOutputStream? = null
    private var videoExt = "mp4"

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCharacter(pendingSlot, uri)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val publicDir = File(filesDir, "public").apply { mkdirs() }
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/files/", WebViewAssetLoader.InternalStoragePathHandler(this, publicDir))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        web.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)

        val file = intent.getStringExtra(EXTRA_FILE) ?: ""
        val rec = URLEncoder.encode("/files/recordings/$file", "UTF-8")
        web.loadUrl("$HOST/assets/web/stage.html?rec=$rec")
    }

    override fun onDestroy() {
        try { videoOut?.close() } catch (_: Exception) {}
        web.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------ characters

    private fun importCharacter(slot: Int, uri: Uri) {
        Thread {
            try {
                var name = "character.glb"
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0) ?: name
                }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val dir = File(filesDir, "public/characters").apply { mkdirs() }
                val dest = File(dir, safe)
                contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                val url = "/files/characters/" + URLEncoder.encode(safe, "UTF-8").replace("+", "%20")
                getPreferences(MODE_PRIVATE).edit().putString("char$slot", "$url|$name").apply()
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.onCharacterPicked($slot, ${JSONObject.quote(url)}, ${JSONObject.quote(name)})", null
                    )
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Couldn't open that file: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ------------------------------------------------------------ bridge for the page

    inner class Bridge {
        @JavascriptInterface
        fun pickCharacter(slot: Int) {
            runOnUiThread {
                pendingSlot = slot
                picker.launch(arrayOf("*/*"))
            }
        }

        @JavascriptInterface
        fun savedCharacter(slot: Int): String = getPreferences(MODE_PRIVATE).getString("char$slot", "") ?: ""

        @JavascriptInterface
        fun videoBegin(ext: String) {
            videoExt = if (ext == "webm") "webm" else "mp4"
            try { videoOut?.close() } catch (_: Exception) {}
            val f = File(cacheDir, "video.$videoExt")
            videoTmp = f
            videoOut = FileOutputStream(f)
        }

        @JavascriptInterface
        fun videoChunk(b64: String) {
            videoOut?.write(Base64.decode(b64, Base64.DEFAULT))
        }

        /** Moves the finished video into Movies/Motion Puppet. Returns its file name, or "" on failure. */
        @JavascriptInterface
        fun videoEnd(): String {
            return try {
                videoOut?.close()
                videoOut = null
                val tmp = videoTmp ?: return ""
                val name = "MotionPuppet_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "." + videoExt
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, name)
                    put(MediaStore.Video.Media.MIME_TYPE, if (videoExt == "mp4") "video/mp4" else "video/webm")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Motion Puppet")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return ""
                contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                tmp.delete()
                name
            } catch (e: Exception) {
                ""
            }
        }

        @JavascriptInterface
        fun close() {
            runOnUiThread { finish() }
        }
    }
}
