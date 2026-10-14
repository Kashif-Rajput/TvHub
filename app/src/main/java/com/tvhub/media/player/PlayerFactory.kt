package com.tvhub.media.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvhub.media.core.Prefs

/**
 * One place that decides how playback is tuned for weak TVs:
 *  - hardware decoders first (best picture, lowest CPU), with decoder fallback if one fails
 *  - FFmpeg only as a fallback for audio formats the TV chip lacks (AC3/DTS...)
 *  - Media3's own buffer defaults, which are tuned for local files since 1.9
 *  - video is never re-scaled in software; the TV's video plane shows it at full quality
 */
@OptIn(UnstableApi::class)
object PlayerFactory {

    fun create(context: Context, forVideo: Boolean): ExoPlayer {
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                if (Prefs.ffmpegAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )

        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTunnelingEnabled(forVideo && Prefs.tunneling)
        )

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (forVideo) C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }
}
