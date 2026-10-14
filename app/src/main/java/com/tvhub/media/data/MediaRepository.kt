package com.tvhub.media.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads the system media index (MediaStore). Android indexes USB drives itself, so this
 * is far faster than walking thousands of files, and it already knows durations and tags.
 * Everything here runs on Dispatchers.IO and is cancellable.
 */
object MediaRepository {
    private const val TAG = "MediaRepository"

    suspend fun load(context: Context, kind: MediaKind, volume: String?): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            val volumes = if (volume != null) listOf(volume)
            else MediaStore.getExternalVolumeNames(context).toList()
            val out = ArrayList<MediaEntry>(256)
            for (vol in volumes) {
                ensureActive()
                try {
                    queryVolume(context, kind, vol, out)
                } catch (e: Exception) {
                    // A drive pulled out mid-query, or a broken index: skip that volume, keep the rest.
                    Log.w(TAG, "Query failed for volume $vol", e)
                }
            }
            out
        }

    private fun collection(kind: MediaKind, vol: String): Uri = when (kind) {
        MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(vol)
        MediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(vol)
        MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(vol)
    }

    @Suppress("DEPRECATION") // DATA is still readable and gives a real path for thumbnails/removal checks
    private fun queryVolume(context: Context, kind: MediaKind, vol: String, out: MutableList<MediaEntry>) {
        val base = collection(kind, vol)
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATA
        )
        if (kind != MediaKind.IMAGE) proj += MediaStore.MediaColumns.DURATION
        if (kind == MediaKind.AUDIO) {
            proj += MediaStore.Audio.AudioColumns.ARTIST
            proj += MediaStore.Audio.AudioColumns.ALBUM
        }
        // Skip ringtones/notification sounds.
        val selection = if (kind == MediaKind.AUDIO) "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0" else null

        context.contentResolver.query(base, proj.toTypedArray(), selection, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTitle = c.getColumnIndex(MediaStore.MediaColumns.TITLE)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val iDur = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val iArtist = c.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST)
            val iAlbum = c.getColumnIndex(MediaStore.Audio.AudioColumns.ALBUM)

            while (c.moveToNext()) {
                val name = c.str(iName) ?: continue
                val id = c.getLong(iId)
                val tagTitle = c.str(iTitle)?.takeIf { it.isNotBlank() }
                out += MediaEntry(
                    uri = ContentUris.withAppendedId(base, id),
                    title = if (kind == MediaKind.AUDIO) tagTitle ?: name.substringBeforeLast('.') else name,
                    displayName = name,
                    kind = kind,
                    sizeBytes = c.long(iSize),
                    dateModified = c.long(iDate) * 1000L,
                    durationMs = c.long(iDur),
                    mime = c.str(iMime),
                    artist = c.str(iArtist)?.takeUnless { it == "<unknown>" || it.isBlank() },
                    album = c.str(iAlbum)?.takeUnless { it == "<unknown>" || it.isBlank() },
                    volume = vol,
                    folder = c.str(iRel)?.trimEnd('/'),
                    path = c.str(iData)
                )
            }
        }
    }

    private fun Cursor.str(i: Int): String? = if (i >= 0 && !isNull(i)) getString(i) else null
    private fun Cursor.long(i: Int): Long = if (i >= 0 && !isNull(i)) getLong(i) else 0L

    fun sort(list: List<MediaEntry>, mode: Int): List<MediaEntry> = when (mode) {
        1 -> list.sortedByDescending { it.dateModified }
        2 -> list.sortedByDescending { it.sizeBytes }
        else -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
