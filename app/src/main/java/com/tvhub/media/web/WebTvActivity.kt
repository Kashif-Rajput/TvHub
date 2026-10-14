package com.tvhub.media.web

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvhub.media.R
import com.tvhub.media.core.Prefs
import com.tvhub.media.data.AppLauncher
import com.tvhub.media.databinding.ActivityWebtvBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Opens the Watcho website (watcho.pk: Movies, Shows, Live TV) inside TV Hub.
 *
 * Nothing is scraped or bypassed: this is a browser window on Watcho's own site.
 * The user signs in with their own Watcho account, and Watcho's own web player plays the
 * video (including DRM-protected video, through the TV's Widevine, the same as Chrome).
 *
 * Websites are made for a mouse, so the remote drives an on-screen pointer:
 *   ▲▼◀▶ = move pointer (hold = faster; at the screen edge the page scrolls)
 *   OK = click (hold OK = long-press / drag) · CH+/CH− = scroll a page
 *   ⏯ = play/pause video · ⏪ ⏩ = 10 s back/forward
 *   Menu = Home, Type text, Zoom, Save start page, Desktop/Mobile site, Watcho app…
 *   Back = full-screen video → previous page → close
 */
class WebTvActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebtvBinding
    private var web: WebView? = null

    private var key = KEY_MOVIES
    private var pageTitle = ""

    // Full-screen <video> from the page
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // Pointer, in pixels relative to the root view (= WebView, both fill the screen)
    private var cx = 0f
    private var cy = 0f
    private var tipOffset = 0f
    private var pressed = false
    private var downTime = 0L
    private var errorShown = false

    private val main = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { b.hint.visibility = View.GONE }
    private val hideCursor = Runnable { if (customView != null) b.cursor.visibility = View.INVISIBLE }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebtvBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        key = intent.getStringExtra(EXTRA_KEY) ?: KEY_MOVIES
        pageTitle = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.tile_movies)
        tipOffset = dp(2.5f) // tip of ic_cursor sits at (2,2) of its 24-unit box, drawn at 30dp

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        val w = try {
            createWebView()
        } catch (e: Throwable) {
            null // Some TVs ship without a working "Android System WebView".
        }
        if (w == null) {
            showError(getString(R.string.web_err_webview))
            return
        }
        web = w
        b.webHolder.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.requestFocus()

        b.root.post {
            cx = b.root.width / 2f
            cy = b.root.height / 2f
            placeCursor()
        }
        showHint()

        if (savedInstanceState != null) w.restoreState(savedInstanceState)
        if (w.url == null) w.loadUrl(startUrl())
    }

    private fun startUrl(): String = Prefs.webStart(key) ?: DEFAULT_URL

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val w = WebView(this)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.setBackgroundColor(0xFF000000.toInt())
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false) // links that open a new tab load here instead
        }
        applyUserAgent(w)

        // Stay signed in to Watcho between visits.
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                b.pageBar.progress = newProgress
                b.pageBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // Lets Watcho's web player use the TV's Widevine DRM, like Chrome does.
            // Only "protected media" is granted; camera/microphone requests are refused.
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val allowed = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (allowed.isNotEmpty()) request.grant(allowed.toTypedArray()) else request.deny()
                }
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                b.fullHolder.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                b.fullHolder.visibility = View.VISIBLE
                b.webHolder.visibility = View.INVISIBLE
                bumpCursor()
            }

            override fun onHideCustomView() = exitFullScreen(notifyPage = false)

            // Hides the grey "play" placeholder some WebViews draw before a video starts.
            override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme == "http" || scheme == "https") return false
                if (scheme == "about" || scheme == "data" || scheme == "blob" || scheme == "javascript") return false
                if (request.isForMainFrame) openExternal(url.toString())
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                hideError()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                showError(getString(R.string.web_err_load, error.description) + "\n\n" + getString(R.string.web_err_hint))
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                // Low-memory TVs can kill the web renderer; close cleanly instead of crashing.
                web = null
                b.webHolder.removeAllViews()
                view.destroy()
                Toast.makeText(this@WebTvActivity, R.string.yt_err_memory, Toast.LENGTH_LONG).show()
                finish()
                return true
            }
        }
        return w
    }

    /** Desktop site by default (big screen). The "; wv" WebView marker is removed, as some sites block it. */
    private fun applyUserAgent(w: WebView) {
        val def = runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault("")
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: "Chrome/120.0.0.0"
        w.settings.userAgentString = if (Prefs.webDesktop) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
        } else {
            def.replace("; wv", "").replace(" Version/4.0", "")
        }
    }

    /** intent:, market:, tel: links etc. (e.g. "Open in app"). Never crashes if nothing handles them. */
    private fun openExternal(url: String) {
        try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                val i = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                val fallback = i.getStringExtra("browser_fallback_url")
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    if (fallback != null && (fallback.startsWith("http://") || fallback.startsWith("https://"))) {
                        web?.loadUrl(fallback)
                    }
                }
                return
            }
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.web_link_unsupported, Toast.LENGTH_SHORT).show()
        }
    }

    private fun js(code: String, cb: ((String?) -> Unit)? = null) {
        web?.evaluateJavascript(code) { cb?.invoke(it) }
    }

    // ------------------------------------------------------------------ pointer

    private fun placeCursor() {
        b.cursor.translationX = cx - tipOffset
        b.cursor.translationY = cy - tipOffset
    }

    /** Shows the pointer; during full-screen video it hides itself again after a few seconds. */
    private fun bumpCursor() {
        b.cursor.visibility = View.VISIBLE
        main.removeCallbacks(hideCursor)
        if (customView != null) main.postDelayed(hideCursor, 4000)
    }

    private fun move(dirX: Int, dirY: Int, repeat: Int) {
        bumpCursor()
        val w = b.root.width.toFloat()
        val h = b.root.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val step = dp(12f + min(repeat, 10) * 5f) // speeds up while the key is held
        val nx = cx + dirX * step
        val ny = cy + dirY * step
        val maxX = w - 1f
        val maxY = h - 1f

        // Pushing past an edge scrolls the page instead.
        var sx = 0f
        var sy = 0f
        if (nx < 0f && cx <= 0f) sx = -step * 3
        if (nx > maxX && cx >= maxX) sx = step * 3
        if (ny < 0f && cy <= 0f) sy = -step * 3
        if (ny > maxY && cy >= maxY) sy = step * 3

        cx = nx.coerceIn(0f, maxX)
        cy = ny.coerceIn(0f, maxY)
        placeCursor()
        if (pressed) touch(MotionEvent.ACTION_MOVE)
        if (sx != 0f || sy != 0f) scrollAt(sx, sy)
    }

    /** Sends a finger touch at the pointer to the page (or to the full-screen video). */
    private fun touch(action: Int) {
        val target = customView ?: web ?: return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        target.getLocationOnScreen(loc)
        b.root.getLocationOnScreen(rootLoc)
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        }
        val coords = MotionEvent.PointerCoords().apply {
            x = cx - (loc[0] - rootLoc[0])
            y = cy - (loc[1] - rootLoc[1])
            pressure = 1f
            size = 1f
        }
        val ev = MotionEvent.obtain(
            downTime, now, action, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        target.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Scrolls whatever is scrollable under the pointer (rows of movies, lists), else the page. */
    private fun scrollAt(dxPx: Float, dyPx: Float) {
        if (customView != null) return
        val w = web ?: return
        val vw = w.width.coerceAtLeast(1)
        js(
            "(function(px,py,dx,dy,W){var k=window.innerWidth/W;var x=px*k,y=py*k;dx*=k;dy*=k;" +
                "var e=document.elementFromPoint(x,y);" +
                "while(e&&e!==document.body&&e!==document.documentElement){var s=getComputedStyle(e);" +
                "if(dy&&e.scrollHeight>e.clientHeight+2&&/(auto|scroll|overlay)/.test(s.overflowY)){var a=e.scrollTop;e.scrollTop+=dy;if(e.scrollTop!==a)return;}" +
                "if(dx&&e.scrollWidth>e.clientWidth+2&&/(auto|scroll|overlay)/.test(s.overflowX)){var c=e.scrollLeft;e.scrollLeft+=dx;if(e.scrollLeft!==c)return;}" +
                "e=e.parentElement;}window.scrollBy(dx,dy);})" +
                "(${cx.roundToInt()},${cy.roundToInt()},${dxPx.roundToInt()},${dyPx.roundToInt()},$vw)"
        )
    }

    /** Play/pause/seek the first video on the page. [arg]: "t" toggle, "p" play, "s" pause, or seconds. */
    private fun video(arg: String) {
        js(
            "(function(a){var v=document.querySelector('video');if(!v)return;" +
                "if(a==='t'){if(v.paused)v.play();else v.pause();}else if(a==='p')v.play();" +
                "else if(a==='s')v.pause();else v.currentTime=Math.max(0,v.currentTime+Number(a));})('$arg')"
        )
    }

    // ------------------------------------------------------------------ remote

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (down) move(0, -1, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (down) move(0, 1, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (down) move(-1, 0, event.repeatCount); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (down) move(1, 0, event.repeatCount); return true }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (errorShown) {
                    if (up) { hideError(); web?.reload() }
                    return true
                }
                if (down && event.repeatCount == 0) {
                    bumpCursor()
                    pressed = true
                    touch(MotionEvent.ACTION_DOWN)
                } else if (up && pressed) {
                    pressed = false
                    touch(MotionEvent.ACTION_UP)
                }
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) scrollAt(0f, -b.root.height * 0.8f); return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) scrollAt(0f, b.root.height * 0.8f); return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { if (down && event.repeatCount == 0) video("t"); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) video("p"); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> { if (down) video("s"); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) video("-10"); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) video("10"); return true }

            KeyEvent.KEYCODE_MENU -> { if (up) showMenu(); return true }
            KeyEvent.KEYCODE_ESCAPE -> { if (up) goBack(); return true }
        }
        return super.dispatchKeyEvent(event) // Back, volume, numbers behave normally
    }

    private fun goBack() {
        val w = web
        when {
            customView != null -> exitFullScreen(notifyPage = true)
            w != null && w.canGoBack() -> w.goBack()
            else -> finish()
        }
    }

    private fun exitFullScreen(notifyPage: Boolean) {
        val v = customView ?: return
        val cb = customCallback
        customView = null
        customCallback = null
        b.fullHolder.removeView(v)
        b.fullHolder.visibility = View.GONE
        b.webHolder.visibility = View.VISIBLE
        bumpCursor()
        if (notifyPage) cb?.onCustomViewHidden()
        web?.requestFocus()
    }

    // ------------------------------------------------------------------ menu

    private fun showMenu() {
        val w = web ?: return
        val items = listOf(
            getString(R.string.web_home) to { w.loadUrl(startUrl()) },
            getString(R.string.web_type_text) to { askText() },
            getString(R.string.web_reload) to { hideError(); w.reload() },
            getString(R.string.web_zoom_in) to { w.zoomIn(); Unit },
            getString(R.string.web_zoom_out) to { w.zoomOut(); Unit },
            getString(R.string.web_save_start, pageTitle) to { saveStart() },
            getString(R.string.web_address) to { askAddress() },
            getString(if (Prefs.webDesktop) R.string.web_use_mobile else R.string.web_use_desktop) to {
                Prefs.webDesktop = !Prefs.webDesktop
                applyUserAgent(w)
                w.reload()
            },
            getString(R.string.web_open_watcho_app) to { openWatchoApp() },
            getString(R.string.web_sign_out) to { confirmSignOut() },
            getString(R.string.web_help) to { showHint(long = true) }
        )
        AlertDialog.Builder(this)
            .setTitle(w.title?.takeIf { it.isNotBlank() } ?: pageTitle)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveStart() {
        val url = web?.url ?: return
        if (!url.startsWith("http")) return
        Prefs.setWebStart(key, url)
        Toast.makeText(this, getString(R.string.web_saved_start, pageTitle), Toast.LENGTH_LONG).show()
    }

    private fun askAddress() {
        val input = EditText(this).apply {
            setText(web?.url ?: startUrl())
            selectAll()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.web_address)
            .setMessage(R.string.web_address_msg)
            .setView(input)
            .setPositiveButton(R.string.web_go) { _, _ ->
                var u = input.text.toString().trim()
                if (u.isEmpty()) return@setPositiveButton
                if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
                web?.loadUrl(u)
            }
            .setNeutralButton(R.string.web_reset_start) { _, _ ->
                Prefs.setWebStart(key, null)
                web?.loadUrl(DEFAULT_URL)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** For TVs whose keyboard doesn't pop up on web pages: type here, it goes into the selected box. */
    private fun askText() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val send = { enter: Boolean ->
            val t = JSONObject.quote(input.text.toString())
            js(
                "(function(t,enter){var e=document.activeElement;" +
                    "while(e&&e.shadowRoot&&e.shadowRoot.activeElement)e=e.shadowRoot.activeElement;" +
                    "if(!e||!(e.tagName==='INPUT'||e.tagName==='TEXTAREA'||e.isContentEditable))return 'none';" +
                    "if(e.isContentEditable){e.textContent=t;}else{" +
                    "var p=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;" +
                    "Object.getOwnPropertyDescriptor(p,'value').set.call(e,t);}" +
                    "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
                    "if(enter){var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};" +
                    "var ok=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));" +
                    "e.dispatchEvent(new KeyboardEvent('keyup',o));" +
                    "if(ok&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}}" +
                    "return 'ok';})($t,$enter)"
            ) { res ->
                if (res?.contains("none") == true) {
                    Toast.makeText(this, R.string.web_type_no_box, Toast.LENGTH_LONG).show()
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.web_type_text)
            .setMessage(R.string.web_type_msg)
            .setView(input)
            .setPositiveButton(R.string.web_type_enter) { _, _ -> send(true) }
            .setNeutralButton(R.string.web_type_only) { _, _ -> send(false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openWatchoApp() {
        val ctx = applicationContext
        lifecycleScope.launch {
            val pkg = withContext(Dispatchers.IO) {
                val svc = AppLauncher.services.firstOrNull { it.id == "watcho" } ?: return@withContext null
                AppLauncher.resolve(ctx, svc, AppLauncher.installedApps(ctx, withIcons = false))
            }
            if (pkg == null || !AppLauncher.launch(this@WebTvActivity, pkg)) {
                Toast.makeText(this@WebTvActivity, getString(R.string.app_missing_title, getString(R.string.tile_watcho)), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle(R.string.web_sign_out)
            .setMessage(R.string.web_sign_out_msg)
            .setPositiveButton(R.string.web_sign_out_yes) { _, _ ->
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                web?.clearCache(true)
                web?.clearHistory()
                web?.loadUrl(startUrl())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ messages

    private fun showHint(long: Boolean = false) {
        b.hint.setText(if (long) R.string.web_help_text else R.string.web_hint)
        b.hint.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, if (long) 12_000 else 6_000)
    }

    private fun showError(msg: String) {
        errorShown = true
        b.error.text = msg
        b.error.visibility = View.VISIBLE
    }

    private fun hideError() {
        errorShown = false
        b.error.visibility = View.GONE
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web?.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web?.onResume()
    }

    override fun onPause() {
        if (pressed) {
            pressed = false
            touch(MotionEvent.ACTION_CANCEL)
        }
        web?.onPause()
        CookieManager.getInstance().flush() // keep the Watcho sign-in
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (customView != null) exitFullScreen(notifyPage = true)
        web?.let { w ->
            w.stopLoading()
            w.loadUrl("about:blank")
            b.webHolder.removeView(w)
            w.destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_URL = "http://watcho.pk/"
        const val KEY_MOVIES = "movies"
        const val KEY_LIVE = "live"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_TITLE = "title"

        fun open(context: Context, key: String, title: String) {
            context.startActivity(
                Intent(context, WebTvActivity::class.java)
                    .putExtra(EXTRA_KEY, key)
                    .putExtra(EXTRA_TITLE, title)
            )
        }
    }
}
