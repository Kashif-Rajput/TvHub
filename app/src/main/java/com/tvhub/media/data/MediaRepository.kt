package com.tvhub.media.data

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
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

    /**
     * [volumeName] = MediaStore volume (Android 10+), [pathPrefix] = drive folder used as a filter
     * on Android 9, where every drive shares one "external" index.
     */
    suspend fun load(context: Context, kind: MediaKind, volumeName: String?, pathPrefix: String?): List<MediaEntry> =
        withContext(Dispatchers.IO) {
            val modern = Build.VERSION.SDK_INT >= 29
            val volumes = when {
                modern && volumeName != null -> listOf(volumeName)
                modern -> MediaStore.getExternalVolumeNames(context).toList()
                else -> listOf("external")
            }
            val prefix = if (modern && volumeName != null) null else pathPrefix
            val out = ArrayList<MediaEntry>(256)
            for (vol in volumes) {
                ensureActive()
                try {
                    queryVolume(context, kind, vol, prefix, out)
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
    private fun queryVolume(context: Context, kind: MediaKind, vol: String, prefix: String?, out: MutableList<MediaEntry>) {
        val base = collection(kind, vol)
        val proj = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA
        )
        if (Build.VERSION.SDK_INT >= 29) proj += MediaStore.MediaColumns.RELATIVE_PATH
        // "duration" exists in the video and audio tables on every Android version.
        if (kind != MediaKind.IMAGE) proj += COL_DURATION
        if (kind == MediaKind.AUDIO) {
            proj += MediaStore.Audio.AudioColumns.ARTIST
            proj += MediaStore.Audio.AudioColumns.ALBUM
        }
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        // Skip ringtones/notification sounds.
        if (kind == MediaKind.AUDIO) where += "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0"
        if (prefix != null) {
            where += "${MediaStore.MediaColumns.DATA} LIKE ?"
            args += prefix.trimEnd('/') + "/%"
        }
        val selection = if (where.isEmpty()) null else where.joinToString(" AND ")
        val selArgs = if (args.isEmpty()) null else args.toTypedArray()

        context.contentResolver.query(base, proj.toTypedArray(), selection, selArgs, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iTitle = c.getColumnIndex(MediaStore.MediaColumns.TITLE)
            val iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val iDate = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val iData = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            val iDur = c.getColumnIndex(COL_DURATION)
            val iArtist = c.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST)
            val iAlbum = c.getColumnIndex(MediaStore.Audio.AudioColumns.ALBUM)

            while (c.moveToNext()) {
                val name = c.str(iName) ?: continue
                val id = c.getLong(iId)
                val tagTitle = c.str(iTitle)?.takeIf { it.isNotBlank() }
                val data = c.str(iData)
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
                    folder = c.str(iRel)?.trimEnd('/') ?: data?.substringBeforeLast('/')?.substringAfterLast('/'),
                    path = data
                )
            }
        }
    }

    private const val COL_DURATION = "duration"

    private fun Cursor.str(i: Int): String? = if (i >= 0 && !isNull(i)) getString(i) else null
    private fun Cursor.long(i: Int): Long = if (i >= 0 && !isNull(i)) getLong(i) else 0L

    fun sort(list: List<MediaEntry>, mode: Int): List<MediaEntry> = when (mode) {
        1 -> list.sortedByDescending { it.dateModified }
        2 -> list.sortedByDescending { it.sizeBytes }
        else -> list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
