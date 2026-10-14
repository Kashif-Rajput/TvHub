package com.tvhub.media.ui.common

import android.app.Activity
import android.content.Intent
import com.tvhub.media.core.PlaylistHolder
import com.tvhub.media.data.MediaEntry
import com.tvhub.media.data.MediaKind
import com.tvhub.media.player.AudioPlayerActivity
import com.tvhub.media.player.VideoPlayerActivity
import com.tvhub.media.viewer.ImageViewerActivity

/** Fragments that want the remote's Menu/Options key. */
interface MenuKeyHandler {
    fun onMenuKey(): Boolean
}

object Open {
    /** Opens the right player for [list][index]; the rest of the list becomes previous/next. */
    fun media(activity: Activity, list: List<MediaEntry>, index: Int) {
        if (list.isEmpty()) return
        val i = index.coerceIn(0, list.size - 1)
        val kind = list[i].kind
        // Only same-kind items make sense as previous/next.
        val same = list.filter { it.kind == kind }
        PlaylistHolder.set(same, same.indexOf(list[i]).coerceAtLeast(0))
        val cls = when (kind) {
            MediaKind.VIDEO -> VideoPlayerActivity::class.java
            MediaKind.AUDIO -> AudioPlayerActivity::class.java
            MediaKind.IMAGE -> ImageViewerActivity::class.java
        }
        activity.startActivity(Intent(activity, cls))
    }
}
