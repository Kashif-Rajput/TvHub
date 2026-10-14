package com.tvhub.media.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.text.HtmlCompat
import com.tvhub.media.BuildConfig
import com.tvhub.media.core.Prefs
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** One YouTube video in a result list. */
data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val thumb: String?,
    val live: Boolean,
    val duration: String = ""
)

data class YtPage(val items: List<YtVideo>, val nextToken: String?)

/** What to ask YouTube for. Either a text search, or a "most popular" chart (much cheaper). */
data class YtQuery(
    val q: String? = null,
    val chart: Boolean = false,
    val categoryId: String? = null,
    val live: Boolean = false,
    /** "short" (<4 min), "medium" (4–20 min), "long" (>20 min), or null for any. */
    val duration: String? = null,
    /** Only videos uploaded in the last N days ("latest"). Null = any time. */
    val recentDays: Int? = null
)

class YtException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NO_KEY, QUOTA, BAD_KEY, NETWORK, OTHER }
}

/**
 * Official YouTube Data API v3 (search + video details). Playback happens separately through the
 * official embedded player, so nothing here downloads or extracts video streams.
 *
 * Quota: a text search costs 100 units, a chart or details call costs 1. The free daily quota is
 * 10,000 units (about 100 searches), so results are cached on disk to save quota.
 */
object YouTubeApi {
    private const val BASE = "https://www.googleapis.com/youtube/v3/"
    private const val PAGE = 24

    private lateinit var cacheDir: File
    private lateinit var pkg: String
    private var certSha1: String = ""

    fun init(context: Context) {
        cacheDir = File(context.cacheDir, "yt").apply { mkdirs() }
        pkg = context.packageName
        certSha1 = runCatching { signingSha1(context) }.getOrDefault("")
    }

    fun key(): String = Prefs.ytApiKey?.trim()?.takeIf { it.isNotEmpty() } ?: BuildConfig.YT_API_KEY.trim()

    fun hasKey(): Boolean = key().isNotEmpty()

    /** Blocking. Call on Dispatchers.IO. */
    fun load(query: YtQuery, pageToken: String?): YtPage {
        if (!hasKey()) throw YtException(YtException.Kind.NO_KEY, "No YouTube API key")
        return if (query.chart) chart(query, pageToken) else search(query, pageToken)
    }

    // ------------------------------------------------------------------ search (100 units)

    private fun search(query: YtQuery, pageToken: String?): YtPage {
        val b = Uri.parse(BASE + "search").buildUpon()
            .appendQueryParameter("part", "snippet")
            .appendQueryParameter("type", "video")
            .appendQueryParameter("maxResults", PAGE.toString())
            .appendQueryParameter("q", query.q ?: "")
            .appendQueryParameter("regionCode", Prefs.ytRegion)
            .appendQueryParameter("safeSearch", "moderate")
            .appendQueryParameter("videoEmbeddable", "true")
            .appendQueryParameter("videoSyndicated", "true")
            .appendQueryParameter(
                "fields",
                "nextPageToken,items(id/videoId,snippet(title,channelTitle,liveBroadcastContent,thumbnails/medium/url))"
            )
        if (query.live) b.appendQueryParameter("eventType", "live")
        query.duration?.let { b.appendQueryParameter("videoDuration", it) }
        query.recentDays?.let { b.appendQueryParameter("publishedAfter", daysAgoUtc(it)) }
        if (pageToken != null) b.appendQueryParameter("pageToken", pageToken)

        val ttl = if (query.live) 15 * MIN else 6 * HOUR
        val json = get(b.build().toString(), ttl)
        val arr = json.optJSONArray("items")
        val list = ArrayList<YtVideo>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optJSONObject("id")?.optString("videoId").orEmpty()
                if (id.isEmpty()) continue
                val s = o.optJSONObject("snippet") ?: continue
                list += YtVideo(
                    id = id,
                    title = decode(s.optString("title")),
                    channel = decode(s.optString("channelTitle")),
                    thumb = s.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url"),
                    live = s.optString("liveBroadcastContent") == "live"
                )
            }
        }
        val withDurations = runCatching { addDurations(list) }.getOrDefault(list)
        return YtPage(withDurations, json.optString("nextPageToken").takeIf { it.isNotEmpty() })
    }

    /** Search results have no duration; one cheap call (1 unit) fills it in. */
    private fun addDurations(list: List<YtVideo>): List<YtVideo> {
        val ids = list.filter { !it.live }.joinToString(",") { it.id }
        if (ids.isEmpty()) return list
        val url = Uri.parse(BASE + "videos").buildUpon()
            .appendQueryParameter("part", "contentDetails")
            .appendQueryParameter("id", ids)
            .appendQueryParameter("fields", "items(id,contentDetails/duration)")
            .build().toString()
        val arr = get(url, 6 * HOUR).optJSONArray("items") ?: return list
        val map = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            map[o.optString("id")] = isoDuration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
        }
        return list.map { v -> map[v.id]?.let { v.copy(duration = it) } ?: v }
    }

    // ------------------------------------------------------------------ charts (1 unit)

    private fun chart(query: YtQuery, pageToken: String?): YtPage {
        val b = Uri.parse(BASE + "videos").buildUpon()
            .appendQueryParameter("part", "snippet,contentDetails,status")
            .appendQueryParameter("chart", "mostPopular")
            .appendQueryParameter("regionCode", Prefs.ytRegion)
            .appendQueryParameter("maxResults", PAGE.toString())
            .appendQueryParameter(
                "fields",
                "nextPageToken,items(id,snippet(title,channelTitle,liveBroadcastContent,thumbnails/medium/url)," +
                    "contentDetails/duration,status/embeddable)"
            )
        query.categoryId?.let { b.appendQueryParameter("videoCategoryId", it) }
        if (pageToken != null) b.appendQueryParameter("pageToken", pageToken)

        val json = get(b.build().toString(), HOUR)
        val arr = json.optJSONArray("items")
        val list = ArrayList<YtVideo>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optJSONObject("status")?.optBoolean("embeddable", true) == false) continue
                val s = o.optJSONObject("snippet") ?: continue
                list += YtVideo(
                    id = o.optString("id"),
                    title = decode(s.optString("title")),
                    channel = decode(s.optString("channelTitle")),
                    thumb = s.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url"),
                    live = s.optString("liveBroadcastContent") == "live",
                    duration = isoDuration(o.optJSONObject("contentDetails")?.optString("duration").orEmpty())
                )
            }
        }
        return YtPage(list, json.optString("nextPageToken").takeIf { it.isNotEmpty() })
    }

    // ------------------------------------------------------------------ HTTP + disk cache

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN

    private fun get(url: String, ttlMs: Long): JSONObject {
        val cache = File(cacheDir, md5(url + "|" + Prefs.ytRegion) + ".json")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < ttlMs) {
            runCatching { return JSONObject(cache.readText()) }
        }
        val full = Uri.parse(url).buildUpon().appendQueryParameter("key", key()).build().toString()
        val conn = try {
            (URL(full).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                // Lets you restrict the API key to this app in Google Cloud (optional).
                setRequestProperty("X-Android-Package", pkg)
                if (certSha1.isNotEmpty()) setRequestProperty("X-Android-Cert", certSha1)
            }
        } catch (e: IOException) {
            throw YtException(YtException.Kind.NETWORK, e.message ?: "network")
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw apiError(code, body)
            val json = JSONObject(body)
            runCatching { cache.writeText(body) }
            trimCache()
            return json
        } catch (e: YtException) {
            throw e
        } catch (e: IOException) {
            // Offline: fall back to an old cached copy if there is one.
            if (cache.exists()) runCatching { return JSONObject(cache.readText()) }
            throw YtException(YtException.Kind.NETWORK, e.message ?: "network")
        } catch (e: Exception) {
            throw YtException(YtException.Kind.OTHER, e.message ?: "error")
        } finally {
            conn.disconnect()
        }
    }

    private fun apiError(code: Int, body: String): YtException {
        val err = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val reason = err?.optJSONArray("errors")?.optJSONObject(0)?.optString("reason").orEmpty()
        val msg = err?.optString("message").orEmpty().ifEmpty { "HTTP $code" }
        val kind = when {
            reason.contains("quota", true) || reason.contains("dailyLimit", true) ||
                reason.contains("rateLimit", true) -> YtException.Kind.QUOTA
            reason == "keyInvalid" || reason == "accessNotConfigured" || reason == "forbidden" ||
                reason.contains("Blocked", true) || code == 400 && msg.contains("API key", true) ->
                YtException.Kind.BAD_KEY
            else -> YtException.Kind.OTHER
        }
        return YtException(kind, msg)
    }

    /** Keep the cache small on low-storage TVs. */
    private fun trimCache() {
        val files = cacheDir.listFiles() ?: return
        if (files.size <= 300) return
        files.sortedBy { it.lastModified() }.take(files.size - 200).forEach { it.delete() }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ helpers

    private fun decode(s: String): String =
        if (s.indexOf('&') < 0) s else HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

    /** "PT1H2M3S" → "1:02:03". "P0D" (live) → "". */
    fun isoDuration(iso: String): String {
        val m = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""").matchEntire(iso) ?: return ""
        val d = m.groupValues[1].toLongOrNull() ?: 0L
        val h = (m.groupValues[2].toLongOrNull() ?: 0L) + d * 24
        val min = m.groupValues[3].toLongOrNull() ?: 0L
        val s = m.groupValues[4].toLongOrNull() ?: 0L
        if (h == 0L && min == 0L && s == 0L) return ""
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, min, s)
        else String.format(Locale.US, "%d:%02d", min, s)
    }

    /** Midnight UTC, N days ago, as RFC 3339. Rounded to the day so the cache still works. */
    private fun daysAgoUtc(days: Int): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), Locale.US)
        cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
        return String.format(
            Locale.US, "%04d-%02d-%02dT00:00:00Z",
            cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { String.format(Locale.US, "%02x", it) }

    private fun signingSha1(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return ""
        return MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            .joinToString("") { String.format(Locale.US, "%02X", it) }
    }
}
