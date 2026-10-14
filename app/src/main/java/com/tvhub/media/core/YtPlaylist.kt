package com.tvhub.media.core

import com.tvhub.media.data.YtVideo

/** Hands the current YouTube result list to the player (same idea as PlaylistHolder). */
object YtPlaylist {
    var items: List<YtVideo> = emptyList()
        private set
    var index: Int = 0

    fun set(list: List<YtVideo>, start: Int) {
        items = list.toList()
        index = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }
}
