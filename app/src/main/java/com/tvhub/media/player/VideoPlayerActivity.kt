package com.tvhub.media.player

import android.content.ContentResolver
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import com.tvhub.media.R
import com.tvhub.media.core.Format
import com.tvhub.media.core.PlaylistHolder
import com.tvhub.media.core.Prefs
import com.tvhub.media.core.UsbMonitor
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.data.PlaybackStore
import com.tvhub.media.databinding.ActivityVideoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Full-screen video. Remote keys:
 *   OK / Up / Down   show controls          Left / Right (controls hidden)  seek ∓10 s (hold = faster)
 *   Play/Pause, ⏪ ⏩, ⏮ ⏭                    CH+ / CH-  next / previous file
 *   Menu             audio, subtitles, video size
 *   Back             hide controls, then close the player (position is saved)
 */
@OptIn(UnstableApi::class)
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityVideoBinding
    private var player: ExoPlayer? = null
    private var items: List<MediaEntry> = emptyList()
    private var resumeIndex = -1
    private var resumePosition = C.TIME_UNSET
    private var firstStart = true
    private var errorShown = false
    private var seenUsbEvent = -1L

    private val hideOsd = Runnable { b.osd.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        b = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(b.root)

        items = PlaylistHolder.items
        if (items.isEmpty()) {
            finish()
            return
        }
        resumeIndex = savedInstanceState?.getInt("index", -1) ?: -1
        resumePosition = savedInstanceState?.getLong("pos", C.TIME_UNSET) ?: C.TIME_UNSET

        b.playerView.apply {
            resizeMode = resizeModeFor(Prefs.resizeMode)
            controllerShowTimeoutMs = 4000
            setShowSubtitleButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (b.playerView.isControllerFullyVisible && !errorShown) b.playerView.hideController()
                else finish()
            }
        })

        seenUsbEvent = UsbMonitor.events.value.time
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UsbMonitor.events.collect { ev ->
                    if (ev.time == 0L || ev.time == seenUsbEvent) return@collect
                    seenUsbEvent = ev.time
                    if (!ev.mounted) checkFileStillThere()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let {
            outState.putInt("index", it.currentMediaItemIndex)
            outState.putLong("pos", it.currentPosition)
        } ?: run {
            outState.putInt("index", resumeIndex)
            outState.putLong("pos", resumePosition)
        }
    }

    // ------------------------------------------------------------------ player lifecycle

    private fun initPlayer() {
        if (player != null) return
        val p = PlayerFactory.create(this, forVideo = true)
        p.addListener(listener)

        val startIndex = if (resumeIndex >= 0) resumeIndex else PlaylistHolder.index.coerceIn(0, items.size - 1)
        var startPos = if (resumePosition != C.TIME_UNSET) resumePosition
        else PlaybackStore.position(items[startIndex].key)
        if (startPos < 0) startPos = 0

        p.setMediaItems(items.map { toMediaItem(it) }, startIndex, startPos)
        p.prepare()
        p.playWhenReady = true
        b.playerView.player = p
        b.playerView.requestFocus()
        player = p

        if (firstStart && startPos > 0) showOsd(getString(R.string.resumed_at, Format.duration(startPos)), 4000)
        firstStart = false
    }

    private fun releasePlayer() {
        val p = player ?: return
        saveCurrent()
        resumeIndex = p.currentMediaItemIndex
        resumePosition = p.currentPosition
        p.removeListener(listener)
        p.release()
        player = null
        b.playerView.player = null
    }

    private fun toMediaItem(e: MediaEntry): MediaItem =
        MediaItem.Builder()
            .setUri(e.uri)
            .setMediaId(e.key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).build())
            .build()

    private fun saveCurrent() {
        val p = player ?: return
        val e = items.getOrNull(p.currentMediaItemIndex) ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: e.durationMs
        PlaybackStore.savePosition(e, p.currentPosition, dur)
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            hideError()
            val p = player ?: return
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The previous file finished: clear its resume point.
                items.getOrNull(p.previousMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1)?.let {
                    PlaybackStore.savePosition(it, 0, it.durationMs)
                }
            }
            // Jump to where the user left this file before.
            val e = items.getOrNull(p.currentMediaItemIndex) ?: return
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                val saved = PlaybackStore.position(e.key)
                if (saved > 0) {
                    p.seekTo(saved)
                    showOsd(getString(R.string.resumed_at, Format.duration(saved)), 3000)
                }
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                val p = player ?: return
                items.getOrNull(p.currentMediaItemIndex)?.let { PlaybackStore.savePosition(it, 0, it.durationMs) }
                finish()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            when {
                video.isNotEmpty() && video.none { it.isSupported } ->
                    showOsd(getString(R.string.video_track_unsupported), 6000)
                audio.isNotEmpty() && audio.none { it.isSupported } ->
                    showOsd(getString(R.string.audio_track_unsupported), 6000)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val msg = when (error.errorCode) {
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> getString(R.string.err_codec)
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> getString(R.string.err_corrupt)
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> getString(R.string.err_format)
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> getString(R.string.err_file)
                else -> getString(R.string.err_generic, error.errorCodeName)
            }
            val p = player
            val hint = if (p != null && p.hasNextMediaItem()) "\n\n" + getString(R.string.err_next_hint) else ""
            showError(msg + hint)
        }
    }

    // ------------------------------------------------------------------ remote keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU -> {
                    showOptions(p)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    playNext(p)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    onBackPressedDispatcher.onBackPressed()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (errorShown) {
                    if (p.hasNextMediaItem()) playNext(p) else finish()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (!b.playerView.isControllerFullyVisible && !errorShown) {
                        val step = when {
                            event.repeatCount > 20 -> 60_000L
                            event.repeatCount > 6 -> 30_000L
                            else -> 10_000L
                        }
                        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
                        val target = (p.currentPosition + if (forward) step else -step).coerceIn(0, dur)
                        p.seekTo(target)
                        val total = if (dur == Long.MAX_VALUE) "" else " / " + Format.duration(dur)
                        showOsd((if (forward) "⏩ " else "⏪ ") + Format.duration(target.coerceAtLeast(1000)) + total, 1500)
                        return true
                    }
            }
        }
        // Volume keys fall through untouched, so the TV's own volume works normally.
        return super.dispatchKeyEvent(event)
    }

    private fun playNext(p: ExoPlayer) {
        if (!p.hasNextMediaItem()) return
        hideError()
        p.seekToNextMediaItem()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
    }

    private fun showOptions(p: ExoPlayer) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO && group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val kind = if (group.type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtitles"
                val lang = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
                val details = listOfNotNull(
                    f.label, lang,
                    f.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US),
                    if (f.channelCount > 0) "${f.channelCount}ch" else null
                ).joinToString(" · ").ifEmpty { "#${i + 1}" }
                labels += "$kind: $details" + if (group.isTrackSelected(i)) "  ✓" else ""
                val trackGroup = group.mediaTrackGroup
                val type = group.type
                actions += {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(trackGroup, i))
                        .build()
                }
            }
        }
        if (p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
            labels += getString(R.string.subtitles_off)
            actions += {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }
        labels += getString(R.string.video_size, sizeLabel(Prefs.resizeMode))
        actions += {
            Prefs.resizeMode = (Prefs.resizeMode + 1) % 3
            b.playerView.resizeMode = resizeModeFor(Prefs.resizeMode)
            showOsd(getString(R.string.video_size, sizeLabel(Prefs.resizeMode)), 2000)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.options)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun sizeLabel(mode: Int) = getString(
        when (mode) {
            1 -> R.string.size_fill
            2 -> R.string.size_zoom
            else -> R.string.size_fit
        }
    )

    private fun resizeModeFor(mode: Int) = when (mode) {
        1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    // ------------------------------------------------------------------ messages

    private fun showOsd(text: String, ms: Long) {
        b.osd.removeCallbacks(hideOsd)
        b.osd.text = text
        b.osd.visibility = View.VISIBLE
        b.osd.postDelayed(hideOsd, ms)
    }

    private fun showError(text: String) {
        errorShown = true
        b.error.text = text
        b.error.visibility = View.VISIBLE
        b.playerView.hideController()
    }

    private fun hideError() {
        errorShown = false
        b.error.visibility = View.GONE
    }

    /** USB pulled out mid-playback: stop cleanly instead of crashing or freezing. */
    private fun checkFileStillThere() {
        val p = player ?: return
        val e = items.getOrNull(p.currentMediaItemIndex) ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val path = e.path
                if (path != null) File(path).exists()
                else runCatching {
                    if (e.uri.scheme == ContentResolver.SCHEME_CONTENT || e.uri.scheme == ContentResolver.SCHEME_FILE) {
                        contentResolver.openFileDescriptor(e.uri, "r")?.close()
                    }
                    true
                }.getOrDefault(false)
            }
            if (!ok) {
                player?.stop()
                Toast.makeText(this@VideoPlayerActivity, R.string.drive_removed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}
