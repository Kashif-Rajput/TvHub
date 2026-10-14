package com.tvhub.media.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
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
        if (uri != null && uri.authority == MediaStore.AUTHORITY) {
            return context.contentResolver.loadThumbnail(uri, size, null)
        }
        val path = data.path
        if (path != null) {
            val f = File(path)
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

    class Factory(private val context: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, options, context.applicationContext)
    }
}

/** Lets Coil keep thumbnails in its memory cache. */
class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String = "thumb:" + data.key
}
