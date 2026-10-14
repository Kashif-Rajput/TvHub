package com.tvhub.media.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** Resume positions + recently played. Tiny, so SharedPreferences is enough (no database needed). */
object PlaybackStore {
    private const val MAX_RECENTS = 30
    private lateinit var sp: SharedPreferences
    @Volatile private var cache: List<Recent>? = null

    data class Recent(val entry: MediaEntry, val position: Long, val duration: Long, val time: Long)

    fun init(context: Context) {
        sp = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    }

    fun position(key: String): Long = sp.getLong("pos:$key", 0L)

    /** Saves where the user stopped. Near the start or end counts as "not started" / "finished". */
    fun savePosition(entry: MediaEntry, position: Long, duration: Long) {
        val finished = duration > 0 && position > duration - 15_000
        sp.edit {
            if (position < 5_000 || finished) remove("pos:${entry.key}")
            else putLong("pos:${entry.key}", position)
        }
        addRecent(entry, if (finished) 0L else position, duration)
    }

    @Synchronized
    fun addRecent(entry: MediaEntry, position: Long, duration: Long) {
        val list = recents().filterNot { it.entry.key == entry.key }.toMutableList()
        list.add(0, Recent(entry, position, duration, System.currentTimeMillis()))
        while (list.size > MAX_RECENTS) list.removeAt(list.size - 1)
        cache = list
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        sp.edit { putString("recents", arr.toString()) }
    }

    @Synchronized
    fun recents(): List<Recent> {
        cache?.let { return it }
        val raw = sp.getString("recents", null) ?: return emptyList<Recent>().also { cache = it }
        val out = ArrayList<Recent>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) fromJson(arr.getJSONObject(i))?.let { out += it }
        }
        cache = out
        return out
    }

    fun continueWatching(): List<Recent> =
        recents().filter { it.entry.kind == MediaKind.VIDEO && position(it.entry.key) > 0 }

    @Synchronized
    fun clear() {
        cache = null
        sp.edit { clear() }
    }

    private fun toJson(r: Recent) = JSONObject().apply {
        val e = r.entry
        put("uri", e.uri.toString())
        put("title", e.title)
        put("name", e.displayName)
        put("kind", e.kind.name)
        put("mime", e.mime ?: "")
        put("path", e.path ?: "")
        put("artist", e.artist ?: "")
        put("dur", r.duration)
        put("pos", r.position)
        put("time", r.time)
    }

    private fun fromJson(o: JSONObject): Recent? {
        val kind = runCatching { MediaKind.valueOf(o.getString("kind")) }.getOrNull() ?: return null
        val entry = MediaEntry(
            uri = Uri.parse(o.getString("uri")),
            title = o.optString("title"),
            displayName = o.optString("name"),
            kind = kind,
            durationMs = o.optLong("dur"),
            mime = o.optString("mime").ifEmpty { null },
            path = o.optString("path").ifEmpty { null },
            artist = o.optString("artist").ifEmpty { null }
        )
        return Recent(entry, o.optLong("pos"), o.optLong("dur"), o.optLong("time"))
    }
}
