package com.tvhub.media.data

import android.net.Uri

enum class MediaKind {
    VIDEO, AUDIO, IMAGE;

    companion object {
        private val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "avi", "mov", "webm", "3gp", "ts", "m2ts", "mts",
            "mpg", "mpeg", "flv", "wmv", "vob"
        )
        private val AUDIO_EXT = setOf(
            "mp3", "aac", "m4a", "wav", "flac", "ogg", "oga", "opus", "wma", "amr",
            "mka", "ac3", "eac3", "dts"
        )
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

        fun fromName(name: String, mime: String? = null): MediaKind? {
            if (mime != null) {
                when {
                    mime.startsWith("video/") -> return VIDEO
                    mime.startsWith("audio/") -> return AUDIO
                    mime.startsWith("image/") -> return IMAGE
                }
            }
            return when (name.substringAfterLast('.', "").lowercase()) {
                in VIDEO_EXT -> VIDEO
                in AUDIO_EXT -> AUDIO
                in IMAGE_EXT -> IMAGE
                else -> null
            }
        }
    }
}

/** One playable/viewable file, from MediaStore or from the folder browser. */
data class MediaEntry(
    val uri: Uri,
    val title: String,
    val displayName: String,
    val kind: MediaKind,
    val sizeBytes: Long = 0,
    val dateModified: Long = 0,
    val durationMs: Long = 0,
    val mime: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val volume: String? = null,
    val folder: String? = null,
    val path: String? = null
) {
    val key: String get() = uri.toString()
    val stableId: Long get() = key.hashCode().toLong()
    val thumb: Thumb get() = Thumb(uri, path, kind, key)
}
