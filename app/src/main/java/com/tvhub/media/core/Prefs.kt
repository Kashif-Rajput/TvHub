package com.tvhub.media.core

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Small user settings. Read on the main thread is fine: SharedPreferences is cached in memory. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    var animations: Boolean
        get() = sp.getBoolean("animations", true)
        set(v) = sp.edit { putBoolean("animations", v) }

    /** 0 = fit, 1 = fill, 2 = zoom */
    var resizeMode: Int
        get() = sp.getInt("resize", 0)
        set(v) = sp.edit { putInt("resize", v) }

    var tunneling: Boolean
        get() = sp.getBoolean("tunneling", false)
        set(v) = sp.edit { putBoolean("tunneling", v) }

    var ffmpegAudio: Boolean
        get() = sp.getBoolean("ffmpeg_audio", true)
        set(v) = sp.edit { putBoolean("ffmpeg_audio", v) }

    /** 0 = name, 1 = newest, 2 = size */
    var sortMode: Int
        get() = sp.getInt("sort", 0)
        set(v) = sp.edit { putInt("sort", v) }

    var slideshowSeconds: Int
        get() = sp.getInt("slideshow_s", 5)
        set(v) = sp.edit { putInt("slideshow_s", v) }

    fun appPackage(id: String): String? = sp.getString("pkg_$id", null)

    fun setAppPackage(id: String, pkg: String?) = sp.edit {
        if (pkg == null) remove("pkg_$id") else putString("pkg_$id", pkg)
    }

    fun treeUri(volumeId: String): String? = sp.getString("tree_$volumeId", null)

    fun setTreeUri(volumeId: String, uri: String?) = sp.edit {
        if (uri == null) remove("tree_$volumeId") else putString("tree_$volumeId", uri)
    }

    /** Optional: a YouTube API key typed in Settings (overrides the one built into the APK). */
    var ytApiKey: String?
        get() = sp.getString("yt_key", null)
        set(v) = sp.edit { if (v.isNullOrBlank()) remove("yt_key") else putString("yt_key", v.trim()) }

    /** Country used for YouTube results and Trending. */
    var ytRegion: String
        get() = sp.getString("yt_region", "PK") ?: "PK"
        set(v) = sp.edit { putString("yt_region", v) }

    /** Start page saved for a Watcho website tile ("movies" / "live"). Null = Watcho home page. */
    fun webStart(key: String): String? = sp.getString("web_start_$key", null)

    fun setWebStart(key: String, url: String?) = sp.edit {
        if (url == null) remove("web_start_$key") else putString("web_start_$key", url)
    }

    /** Show websites in desktop layout (better on a big TV screen). */
    var webDesktop: Boolean
        get() = sp.getBoolean("web_desktop", true)
        set(v) = sp.edit { putBoolean("web_desktop", v) }

    var ytLastSearch: String
        get() = sp.getString("yt_last", "") ?: ""
        set(v) = sp.edit { putString("yt_last", v) }
}
