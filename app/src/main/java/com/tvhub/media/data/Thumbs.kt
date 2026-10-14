package com.tvhub.media.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** What to make a thumbnail for. */
data class Thumb(val uri: Uri?, val path: String?, val kind: MediaKind, val key: String)

/**
 * Uses the system's own thumbnail generator (fast, sized, cached by MediaProvider)
 * instead of decoding whole videos or full-size photos in our process.
 */
class ThumbFetcher(
    private val data: Thumb,
    private val options: Options,
    private val context: Context
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: 320
        val h = (options.size.height as? Dimension.Pixels)?.px ?: 180
        val size = Size(w.coerceIn(64, 640), h.coerceIn(64, 640))
        val bitmap: Bitmap? = try {
            load(size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null // missing, corrupt, removed USB, no thumbnail: show the placeholder icon
        }
        bitmap?.let {
            DrawableResult(
                drawable = BitmapDrawable(context.resources, it),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        }
    }

    private fun load(size: Size): Bitmap? {
        val uri = data.uri
        val path = data.path
        val modern = Build.VERSION.SDK_INT >= 29
        if (uri != null && uri.authority == MediaStore.AUTHORITY) {
            if (modern) return context.contentResolver.loadThumbnail(uri, size, null)
            legacyMediaStoreThumb(uri, size)?.let { return it }
        }
        if (path != null) {
            val f = File(path)
            if (!modern) return legacyFileThumb(f, size)
            return when (data.kind) {
                MediaKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(f, size, null)
                MediaKind.AUDIO -> ThumbnailUtils.createAudioThumbnail(f, size, null)
                MediaKind.IMAGE -> ThumbnailUtils.createImageThumbnail(f, size, null)
            }
        }
        if (uri != null && uri.scheme == "content") {
            return DocumentsContract.getDocumentThumbnail(
                context.contentResolver, uri, Point(size.width, size.height), null
            )
        }
        return null
    }

    // ---------------------------------------------------------------- Android 9 fallbacks

    @Suppress("DEPRECATION")
    private fun legacyMediaStoreThumb(uri: Uri, size: Size): Bitmap? {
        val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
        val cr = context.contentResolver
        val bmp = when (data.kind) {
            MediaKind.VIDEO -> MediaStore.Video.Thumbnails.getThumbnail(cr, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            MediaKind.IMAGE -> MediaStore.Images.Thumbnails.getThumbnail(cr, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            MediaKind.AUDIO -> null // handled from the file path below
        } ?: return null
        return fit(bmp, size)
    }

    @Suppress("DEPRECATION")
    private fun legacyFileThumb(f: File, size: Size): Bitmap? = when (data.kind) {
        MediaKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Video.Thumbnails.MINI_KIND)?.let { fit(it, size) }
        MediaKind.IMAGE -> decodeSampled(size) { opts -> BitmapFactory.decodeFile(f.path, opts) }
        MediaKind.AUDIO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.path)
                r.embeddedPicture?.let { bytes ->
                    decodeSampled(size) { opts -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }
                }
            } finally {
                runCatching { r.release() }
            }
        }
    }

    /** Decode only as many pixels as the tile needs (never a full 12-megapixel photo). */
    private inline fun decodeSampled(size: Size, decode: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size.width && bounds.outHeight / (sample * 2) >= size.height) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return decode(opts)
    }

    private fun fit(bmp: Bitmap, size: Size): Bitmap =
        if (bmp.width <= size.width * 2 && bmp.height <= size.height * 2) bmp
        else ThumbnailUtils.extractThumbnail(bmp, size.width, size.height, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, options, context.applicationContext)
    }
}

/** Lets Coil keep thumbnails in its memory cache. */
class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "thumb:" + data.key
}
