package com.tvhub.media.player

import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.data.MediaKind
import com.tvhub.media.data.PlaybackStore

/**
 * Background music. Only runs while music is playing; stops itself when nothing is queued.
 * Remote media keys (play/pause/next/previous) reach it through the media session.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = PlayerFactory.create(this, forVideo = false)
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val item = mediaItem ?: return
                val md = item.mediaMetadata
                PlaybackStore.addRecent(
                    MediaEntry(
                        uri = Uri.parse(item.mediaId),
                        title = md.title?.toString() ?: item.mediaId.substringAfterLast('/'),
                        displayName = md.title?.toString() ?: "",
                        kind = MediaKind.AUDIO,
                        artist = md.artist?.toString()
                    ),
                    0L, 0L
                )
            }
        })
        session = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0 || p.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
