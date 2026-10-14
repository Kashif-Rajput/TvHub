package com.tvhub.media.viewer

import android.app.ActivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import coil.dispose
import coil.imageLoader
import coil.load
import coil.request.ImageRequest
import coil.size.Precision
import com.tvhub.media.R
import com.tvhub.media.core.PlaylistHolder
import com.tvhub.media.core.Prefs
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.databinding.ActivityImageBinding

/**
 * Full-screen pictures, decoded at screen size (never full camera resolution).
 *   Left/Right  previous/next (or pan when zoomed)    OK  zoom 1× → 2× → 3×
 *   Play/Pause  slideshow                              Menu  slideshow options
 *   Back        reset zoom, then close
 */
class ImageViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityImageBinding
    private var items: List<MediaEntry> = emptyList()
    private var index = 0
    private var zoom = 1f
    private var slideshow = false
    private var lowRam = false
    private val handler = Handler(Looper.getMainLooper())

    private val hideInfo = Runnable { b.info.visibility = View.GONE }
    private val tick = object : Runnable {
        override fun run() {
            if (!slideshow) return
            show(index + 1)
            handler.postDelayed(this, Prefs.slideshowSeconds * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityImageBinding.inflate(layoutInflater)
        setContentView(b.root)
        items = PlaylistHolder.items
        if (items.isEmpty()) {
            finish()
            return
        }
        val am = getSystemService(ActivityManager::class.java)
        lowRam = am.isLowRamDevice || am.memoryClass <= 128
        index = savedInstanceState?.getInt("index") ?: PlaylistHolder.index
        b.imageRoot.requestFocus()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (zoom > 1f) resetZoom() else finish()
            }
        })
        show(index)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun onStop() {
        setSlideshow(false)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::b.isInitialized) b.image.dispose()
        super.onDestroy()
    }

    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun show(i: Int) {
        val n = items.size
        index = ((i % n) + n) % n
        resetZoom()
        val e = items[index]
        b.image.load(e.uri) {
            size(screenW(), screenH())
            precision(Precision.INEXACT)
            allowRgb565(false) // keep full colour quality for photos
            crossfade(Prefs.animations)
            error(R.drawable.ic_broken)
            listener(onError = { _, _ -> showInfo(getString(R.string.image_error) + "  ·  " + e.displayName) })
        }
        showInfo("${index + 1} / $n   ·   ${e.displayName}")
        if (!lowRam && n > 1) preload(items[(index + 1) % n])
    }

    private fun preload(e: MediaEntry) {
        imageLoader.enqueue(
            ImageRequest.Builder(this)
                .data(e.uri)
                .size(screenW(), screenH())
                .precision(Precision.INEXACT)
                .allowRgb565(false)
                .build()
        )
    }

    private fun showInfo(text: String) {
        b.info.removeCallbacks(hideInfo)
        b.info.text = text
        b.info.visibility = View.VISIBLE
        b.info.postDelayed(hideInfo, 3000)
    }

    private fun resetZoom() {
        zoom = 1f
        b.image.scaleX = 1f
        b.image.scaleY = 1f
        b.image.translationX = 0f
        b.image.translationY = 0f
    }

    private fun cycleZoom() {
        zoom = when (zoom) {
            1f -> 2f
            2f -> 3f
            else -> 1f
        }
        if (zoom == 1f) {
            resetZoom()
        } else {
            b.image.scaleX = zoom
            b.image.scaleY = zoom
            clampPan()
        }
        showInfo("${zoom.toInt()}×")
    }

    private fun pan(dx: Float, dy: Float) {
        b.image.translationX += dx
        b.image.translationY += dy
        clampPan()
    }

    private fun clampPan() {
        val maxX = b.image.width * (zoom - 1f) / 2f
        val maxY = b.image.height * (zoom - 1f) / 2f
        b.image.translationX = b.image.translationX.coerceIn(-maxX, maxX)
        b.image.translationY = b.image.translationY.coerceIn(-maxY, maxY)
    }

    private fun setSlideshow(on: Boolean) {
        slideshow = on
        handler.removeCallbacks(tick)
        if (on) handler.postDelayed(tick, Prefs.slideshowSeconds * 1000L)
    }

    private fun toggleSlideshow() {
        setSlideshow(!slideshow)
        showInfo(getString(if (slideshow) R.string.slideshow_on else R.string.slideshow_off))
    }

    private fun showMenu() {
        val speeds = intArrayOf(3, 5, 10)
        val next = speeds[(speeds.indexOf(Prefs.slideshowSeconds) + 1).mod(speeds.size)]
        val labels = arrayOf(
            getString(R.string.slideshow_toggle),
            getString(R.string.slideshow_interval, next),
            getString(R.string.zoom_reset)
        )
        AlertDialog.Builder(this)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> toggleSlideshow()
                    1 -> {
                        Prefs.slideshowSeconds = next
                        if (slideshow) setSlideshow(true)
                    }
                    2 -> resetZoom()
                }
            }
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = screenW() * 0.15f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (zoom > 1f) pan(step, 0f) else { stopSlideshowByUser(); show(index - 1) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (zoom > 1f) pan(-step, 0f) else { stopSlideshowByUser(); show(index + 1) }
            KeyEvent.KEYCODE_DPAD_UP -> if (zoom > 1f) pan(0f, step) else showInfo("${index + 1} / ${items.size}   ·   ${items[index].displayName}")
            KeyEvent.KEYCODE_DPAD_DOWN -> if (zoom > 1f) pan(0f, -step) else showInfo("${index + 1} / ${items.size}   ·   ${items[index].displayName}")
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> cycleZoom()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> toggleSlideshow()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> show(index + 1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_REWIND -> show(index - 1)
            KeyEvent.KEYCODE_MENU -> showMenu()
            KeyEvent.KEYCODE_ESCAPE -> onBackPressedDispatcher.onBackPressed()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun stopSlideshowByUser() {
        if (slideshow) setSlideshow(false)
    }
}
