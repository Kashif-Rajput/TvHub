package com.tvhub.media.player

import android.content.ComponentName
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.RepeatModeUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.tvhub.media.R
import com.tvhub.media.core.PlaylistHolder
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.databinding.ActivityAudioBinding

/**
 * Music screen. The music itself plays in [PlaybackService], so pressing Back leaves it playing.
 * Controls stay on screen; shuffle and repeat are in the control bar. Menu = stop music.
 */
@OptIn(UnstableApi::class)
class AudioPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RESUME_ONLY = "resume_only"
    }

    private lateinit var b: ActivityAudioBinding
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var queued = false
    private var items: List<MediaEntry> = emptyList()
    private var shownBackgroundHint = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAudioBinding.inflate(layoutInflater)
        setContentView(b.root)
        queued = savedInstanceState?.getBoolean("queued") ?: intent.getBooleanExtra(EXTRA_RESUME_ONLY, false)
        if (!queued) items = PlaylistHolder.items

        b.playerView.apply {
            controllerShowTimeoutMs = 0          // always visible
            controllerHideOnTouch = false
            setShowShuffleButton(true)
            setRepeatToggleModes(RepeatModeUtil.REPEAT_TOGGLE_MODE_ONE or RepeatModeUtil.REPEAT_TOGGLE_MODE_ALL)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("queued", queued)
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val c = try {
                f.get()
            } catch (e: Exception) {
                finish()
                return@addListener
            }
            if (isFinishing || isDestroyed) {
                c.release()
                return@addListener
            }
            controller = c
            b.playerView.player = c
            c.addListener(titleListener)
            if (!queued) {
                queued = true
                val start = PlaylistHolder.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                if (items.isNotEmpty()) {
                    c.setMediaItems(items.map { toMediaItem(it) }, start, 0L)
                    c.prepare()
                    c.play()
                }
            } else if (c.mediaItemCount == 0) {
                Toast.makeText(this, R.string.nothing_playing, Toast.LENGTH_SHORT).show()
                finish()
                return@addListener
            }
            updateTitle(c.mediaMetadata)
            b.playerView.showController()
            b.playerView.requestFocus()
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        controller?.removeListener(titleListener)
        b.playerView.player = null
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        super.onStop()
    }

    override fun finish() {
        val c = controller
        if (!shownBackgroundHint && c != null && c.isPlaying) {
            shownBackgroundHint = true
            Toast.makeText(this, R.string.music_background, Toast.LENGTH_SHORT).show()
        }
        super.finish()
    }

    private val titleListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = updateTitle(mediaMetadata)
    }

    private fun updateTitle(md: MediaMetadata) {
        val parts = listOfNotNull(md.title?.toString(), md.artist?.toString()).filter { it.isNotBlank() }
        b.nowTitle.text = parts.joinToString("  —  ")
    }

    private fun toMediaItem(e: MediaEntry): MediaItem =
        MediaItem.Builder()
            .setUri(e.uri)
            .setMediaId(e.key)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(e.title)
                    .setArtist(e.artist)
                    .setAlbumTitle(e.album)
                    .build()
            )
            .build()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val c = controller
        if (c != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    AlertDialog.Builder(this)
                        .setItems(arrayOf(getString(R.string.stop_music))) { _, _ ->
                            c.stop()
                            c.clearMediaItems()
                            super.finish()
                        }
                        .show()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_STOP -> {
                    c.stop()
                    c.clearMediaItems()
                    super.finish()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (c.hasNextMediaItem()) c.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    c.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
