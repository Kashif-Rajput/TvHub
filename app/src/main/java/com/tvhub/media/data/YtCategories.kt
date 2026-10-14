package com.tvhub.media.data

import java.util.Calendar

/**
 * Ready-made sections. Each one is just a YouTube search (or a free "most popular" chart).
 * To change a section, edit its search words here.
 */
data class YtCategory(val id: String, val label: String, val query: YtQuery)

/**
 * One tab on the "Movies & TV" screen (like Watcho's Movies / Videos / TV).
 * [searchSuffix] is added to whatever the user types, e.g. "full movie" on the Movies tab.
 */
data class YtTab(
    val id: String,
    val label: String,
    val categories: List<YtCategory>,
    val hasYears: Boolean,
    val searchSuffix: String,
    val searchLive: Boolean = false,
    val searchDuration: String? = null
)

object YtCategories {

    /** Shown under the search box on the YouTube screen. */
    val youtube = listOf(
        YtCategory("trending", "Trending", YtQuery(chart = true)),
        YtCategory("music_top", "Top Music", YtQuery(chart = true, categoryId = "10")),
        YtCategory("songs_new", "New Songs", YtQuery(q = "new song official video", recentDays = 30)),
        YtCategory("news_live", "Live News", YtQuery(q = "news live", live = true)),
        YtCategory("cricket", "Cricket", YtQuery(q = "cricket highlights", recentDays = 14)),
        YtCategory("cooking", "Cooking", YtQuery(q = "pakistani recipes urdu")),
        YtCategory("kids", "Kids", YtQuery(q = "cartoons for kids urdu hindi")),
        YtCategory("naat", "Naat / Islamic", YtQuery(q = "naat sharif"))
    )

    // ---------------------------------------------------------------- Movies & TV

    /** "Latest" on the Movies tab = uploaded in the last 6 months. */
    const val LATEST_MOVIE_DAYS = 180
    /** "Latest" on the Videos tab = uploaded in the last 30 days. */
    private const val LATEST_VIDEO_DAYS = 30

    private fun movie(id: String, label: String, q: String) =
        YtCategory(id, label, YtQuery(q = q, duration = "long", recentDays = LATEST_MOVIE_DAYS))

    private fun video(id: String, label: String, q: String, duration: String? = null) =
        YtCategory(id, label, YtQuery(q = q, duration = duration, recentDays = LATEST_VIDEO_DAYS))

    private fun live(id: String, label: String, q: String) =
        YtCategory(id, label, YtQuery(q = q, live = true))

    private val movies = YtTab(
        id = "movies", label = "Movies", hasYears = true,
        searchSuffix = " full movie", searchDuration = "long",
        categories = listOf(
            movie("hollywood", "Hollywood", "hollywood full movie"),
            movie("hollywood_hindi", "Hollywood (Hindi)", "hollywood movie hindi dubbed full"),
            movie("bollywood", "Bollywood", "bollywood full movie"),
            movie("south", "South (Hindi Dubbed)", "south indian movie hindi dubbed full movie"),
            movie("pakistani", "Pakistani", "pakistani full movie"),
            movie("punjabi", "Punjabi", "punjabi full movie"),
            movie("action", "Action", "action full movie"),
            movie("comedy_m", "Comedy", "comedy full movie hindi"),
            movie("horror", "Horror", "horror full movie"),
            movie("romantic", "Romantic", "romantic full movie hindi"),
            movie("animated", "Cartoon Movies", "animated full movie hindi urdu"),
            movie("islamic_m", "Islamic History", "islamic history full movie urdu")
        )
    )

    private val videos = YtTab(
        id = "videos", label = "Videos", hasYears = false,
        searchSuffix = "",
        categories = listOf(
            video("reality", "Reality Shows", "reality show full episode"),
            video("songs", "Songs", "new song official video"),
            video("pak_dramas", "Pakistani Dramas", "pakistani drama episode", "long"),
            video("ind_serials", "Indian Serials", "hindi serial full episode", "medium"),
            video("web_series", "Web Series", "web series episode hindi urdu", "long"),
            video("comedy", "Comedy Shows", "comedy show full episode urdu hindi"),
            video("talk", "Talk Shows", "talk show urdu hindi full"),
            video("kids", "Kids", "cartoon for kids urdu hindi"),
            video("cooking", "Cooking", "recipe urdu hindi"),
            video("islamic", "Naat / Bayan", "naat sharif bayan"),
            video("cricket", "Cricket", "cricket highlights"),
            video("documentary", "Documentaries", "documentary urdu hindi", "long")
        )
    )

    private val tv = YtTab(
        id = "tv", label = "TV Channels", hasYears = false,
        searchSuffix = " live", searchLive = true,
        categories = listOf(
            live("live_all", "All Live", "live tv"),
            live("news_pk", "Pakistan News", "pakistan news live"),
            live("news_in", "India News", "hindi news live"),
            live("news_world", "World News", "news live english"),
            live("sports", "Sports", "sports live"),
            live("religious", "Religious", "makkah madinah live"),
            live("entertainment", "Entertainment", "entertainment channel live"),
            live("music", "Music", "music live 24/7"),
            live("kids_live", "Kids", "kids cartoon live")
        )
    )

    val moviesTabs = listOf(movies, videos, tv)

    /** Year buttons on the Movies tab: Latest, this year, last year ... 2000. */
    const val YEAR_LATEST = "latest"

    fun years(latestLabel: String): List<YtCategory> {
        val now = Calendar.getInstance().get(Calendar.YEAR)
        val list = ArrayList<YtCategory>()
        list += YtCategory(YEAR_LATEST, latestLabel, YtQuery())
        for (y in now downTo 2000) list += YtCategory(y.toString(), y.toString(), YtQuery())
        return list
    }
}
