package com.tvhub.media.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.tvhub.media.R
import com.tvhub.media.core.Format
import com.tvhub.media.core.YtPlaylist
import com.tvhub.media.data.AppLauncher
import com.tvhub.media.data.YtVideo
import com.tvhub.media.databinding.ActivityYoutubeBinding

/**
 * Plays YouTube videos inside TV Hub using YouTube's official embedded player (IFrame API).
 * The remote is handled natively; the page itself never takes focus.
 *
 * Remote: OK = play/pause · ◀ ▶ = 10 s back/forward · ▲ ▼ = show info
 *         Menu = next / previous / open in YouTube app · CH+/CH− or ⏭ ⏮ = next / previous
 */
class YouTubePlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityYoutubeBinding
    private var web: WebView? = null

    private var list: List<YtVideo> = emptyList()
    private var index = 0
    private val current get() = list.getOrNull(index)

    private var ready = false
    private var state = -1          // YT.PlayerState: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued
    private var errorShown = false
    private var posSec = 0.0
    private var durSec = 0.0

    private val main = Handler(Looper.getMainLooper())
    private val hideOsd = Runnable { b.osd.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            if (b.osd.visibility == View.VISIBLE) {
                js("tvTick()")
                main.postDelayed(this, 1000)
            }
        }
    }
    private val readyTimeout = Runnable {
        if (!ready) showError(getString(R.string.yt_err_webview))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityYoutubeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        list = YtPlaylist.items
        index = YtPlaylist.index
        if (list.isEmpty()) {
            finish()
            return
        }

        val w = try {
            createWebView()
        } catch (e: Throwable) {
            // Some TVs ship without a working "Android System WebView".
            null
        }
        if (w == null) {
            showError(getString(R.string.yt_err_webview))
            return
        }
        web = w
        b.webHolder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        loadPage(current!!.id)
        showOsd()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.isFocusable = false
        w.isFocusableInTouchMode = false
        w.setBackgroundColor(0xFF000000.toInt())
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false)
        }
        w.addJavascriptInterface(Bridge(), "TV")
        w.webChromeClient = object : WebChromeClient() {
            // Hides the grey "play" placeholder some WebViews draw before the video starts.
            override fun getDefaultVideoPoster(): Bitmap? =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        w.webViewClient = object : WebViewClient() {
            // Never navigate the page away from our player (e.g. tapping the YouTube logo); let the embed iframe load.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Low-memory TVs can kill the web renderer; close cleanly instead of crashing.
                web = null
                b.webHolder.removeAllViews()
                view.destroy()
                Toast.makeText(this@YouTubePlayerActivity, R.string.yt_err_memory, Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        return w
    }

    private fun loadPage(videoId: String) {
        ready = false
        state = -1
        hideError()
        b.loading.visibility = View.VISIBLE
        // A real https base URL gives the embed a proper Referer/origin, which YouTube requires.
        web?.loadDataWithBaseURL(ORIGIN + "/", html(videoId), "text/html", "utf-8", null)
        main.removeCallbacks(readyTimeout)
        main.postDelayed(readyTimeout, 25_000)
    }

    private fun playIndex(i: Int) {
        if (i !in list.indices) return
        index = i
        YtPlaylist.index = i
        posSec = 0.0
        durSec = 0.0
        hideError()
        val id = list[i].id
        if (ready) {
            b.loading.visibility = View.VISIBLE
            js("tvLoad('$id')")
        } else {
            loadPage(id)
        }
        showOsd()
    }

    private fun next() {
        if (index + 1 < list.size) playIndex(index + 1)
        else Toast.makeText(this, R.string.yt_last_video, Toast.LENGTH_SHORT).show()
    }

    private fun previous() {
        if (index > 0) playIndex(index - 1)
        else Toast.makeText(this, R.string.yt_first_video, Toast.LENGTH_SHORT).show()
    }

    private fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    // ------------------------------------------------------------------ remote

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                    if (event.repeatCount == 0) {
                        if (errorShown) next() else js("tvToggle()")
                    }
                    showOsd(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { js("tvPlay()"); showOsd(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { js("tvPause()"); showOsd(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    js("tvSeek(${if (event.repeatCount > 3) -30 else -10})"); showOsd(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    js("tvSeek(${if (event.repeatCount > 3) 30 else 10})"); showOsd(); return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                    showOsd(); return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (event.repeatCount == 0) next(); return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (event.repeatCount == 0) previous(); return true
                }
                KeyEvent.KEYCODE_MENU -> return true
                KeyEvent.KEYCODE_ESCAPE -> { finish(); return true }
            }
        } else if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_MENU) {
            showMenu(); return true
        }
        return super.dispatchKeyEvent(event) // Back and volume keys behave normally
    }

    private fun showMenu() {
        val v = current ?: return
        val items = arrayOf(
            getString(R.string.yt_next),
            getString(R.string.yt_previous),
            getString(R.string.yt_open_in_app)
        )
        AlertDialog.Builder(this)
            .setTitle(v.title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> next()
                    1 -> previous()
                    2 -> {
                        js("tvPause()")
                        if (!openInYouTubeApp(this, v.id)) {
                            Toast.makeText(this, R.string.yt_app_missing, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ on-screen info

    private fun showOsd() {
        val v = current ?: return
        b.osdTitle.text = "${index + 1}/${list.size} · ${v.title}"
        renderOsd()
        b.osd.visibility = View.VISIBLE
        main.removeCallbacks(hideOsd)
        main.postDelayed(hideOsd, 4500)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun renderOsd() {
        val live = current?.live == true
        b.osdState.text = getString(
            when {
                errorShown -> R.string.yt_state_error
                state == 1 -> if (live) R.string.yt_live else R.string.yt_state_playing
                state == 2 -> R.string.yt_state_paused
                state == 3 || !ready -> R.string.yt_state_loading
                state == 0 -> R.string.yt_state_ended
                else -> R.string.yt_state_loading
            }
        )
        if (durSec > 0 && !live) {
            b.osdBar.visibility = View.VISIBLE
            b.osdBar.progress = ((posSec / durSec) * 1000).toInt().coerceIn(0, 1000)
            b.osdTime.text = "${Format.duration((posSec * 1000).toLong())} / ${Format.duration((durSec * 1000).toLong())}"
        } else {
            b.osdBar.visibility = View.INVISIBLE
            b.osdTime.text = if (posSec > 0) Format.duration((posSec * 1000).toLong()) else ""
        }
    }

    private fun showError(msg: String) {
        errorShown = true
        b.loading.visibility = View.GONE
        b.error.text = msg
        b.error.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorShown = false
        b.error.visibility = View.GONE
    }

    // ------------------------------------------------------------------ JS → app (called on a background thread)

    private inner class Bridge {
        @JavascriptInterface
        fun onReady() = runOnUiThread {
            ready = true
            main.removeCallbacks(readyTimeout)
        }

        @JavascriptInterface
        fun onState(s: Int) = runOnUiThread {
            state = s
            if (s == 1 || s == 2 || s == 0) b.loading.visibility = View.GONE
            if (s == 3) b.loading.visibility = View.VISIBLE
            if (s == 1) hideError()
            if (s == 0) {
                if (index + 1 < list.size) playIndex(index + 1) else showOsd()
            }
            if (b.osd.visibility == View.VISIBLE) renderOsd()
        }

        @JavascriptInterface
        fun onTime(pos: Double, dur: Double) = runOnUiThread {
            posSec = pos
            durSec = dur
            if (b.osd.visibility == View.VISIBLE) renderOsd()
        }

        @JavascriptInterface
        fun onError(code: Int) = runOnUiThread {
            val msg = when (code) {
                101, 150, 152 -> getString(R.string.yt_err_embed)
                100 -> getString(R.string.yt_err_removed)
                153 -> getString(R.string.yt_err_config)
                else -> getString(R.string.yt_err_play, code)
            }
            showError(msg + "\n\n" + getString(R.string.yt_err_hint))
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        js("tvPause()")
        web?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        web?.let { w ->
            w.stopLoading()
            w.loadUrl("about:blank")
            b.webHolder.removeView(w)
            w.removeJavascriptInterface("TV")
            w.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        private const val ORIGIN = "https://com.tvhub.media"

        /** Opens a video (or just the app when [videoId] is null) in the official YouTube app. */
        fun openInYouTubeApp(context: Context, videoId: String?): Boolean {
            val uri = if (videoId != null) Uri.parse("https://www.youtube.com/watch?v=$videoId") else null
            val pm = context.packageManager
            val yt = AppLauncher.services.first { it.id == "youtube" }
            val pkg = yt.knownPackages.firstOrNull { AppLauncher.isInstalled(pm, it) }
            val attempts = ArrayList<Intent>()
            if (pkg != null) {
                if (uri != null) attempts += Intent(Intent.ACTION_VIEW, uri).setPackage(pkg)
                AppLauncher.launchIntent(pm, pkg)?.let { attempts += it }
            }
            if (uri != null) attempts += Intent(Intent.ACTION_VIEW, uri)
            for (i in attempts) {
                try {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    return true
                } catch (_: ActivityNotFoundException) {
                } catch (_: SecurityException) {
                }
            }
            return false
        }

        private fun html(videoId: String) = """
<!DOCTYPE html>
<html><head>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
#p{position:absolute;top:0;left:0;width:100%;height:100%}
</style>
</head><body>
<div id="p"></div>
<script>
var player = null, ready = false;
function onYouTubeIframeAPIReady() {
  player = new YT.Player('p', {
    width: '100%', height: '100%',
    videoId: '$videoId',
    playerVars: {
      autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, playsinline: 1,
      iv_load_policy: 3, enablejsapi: 1, origin: '$ORIGIN', widget_referrer: '$ORIGIN'
    },
    events: {
      onReady: function (e) { ready = true; TV.onReady(); e.target.playVideo(); },
      onStateChange: function (e) { TV.onState(e.data); if (e.data == 1) tvTick(); },
      onError: function (e) { TV.onError(e.data); }
    }
  });
}
function tvLoad(id) { if (player && ready) player.loadVideoById(id); }
function tvPlay() { if (player && ready) player.playVideo(); }
function tvPause() { if (player && ready) player.pauseVideo(); }
function tvToggle() {
  if (!player || !ready) return;
  var s = player.getPlayerState();
  if (s == 1 || s == 3) player.pauseVideo(); else player.playVideo();
}
function tvSeek(d) {
  if (!player || !ready) return;
  var t = (player.getCurrentTime() || 0) + d;
  if (t < 0) t = 0;
  player.seekTo(t, true);
  tvTick();
}
function tvTick() {
  if (!player || !ready) return;
  TV.onTime(player.getCurrentTime() || 0, player.getDuration() || 0);
}
</script>
<script src="https://www.youtube.com/iframe_api"></script>
</body></html>
"""
    }
}
