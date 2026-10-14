package com.tvhub.media.core

import com.tvhub.media.data.MediaEntry

/**
 * Hands a playlist to a player screen without stuffing thousands of items into an Intent
 * (which would crash with TransactionTooLargeException).
 */
object PlaylistHolder {
    @Volatile
    var items: List<MediaEntry> = emptyList()
        private set
    @Volatile
    var index: Int = 0
        private set

    fun set(list: List<MediaEntry>, startIndex: Int) {
        items = list
        index = startIndex.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }
}
