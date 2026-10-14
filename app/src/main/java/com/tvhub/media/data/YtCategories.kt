package com.tvhub.media.data

/**
 * Ready-made sections. Each one is just a YouTube search (or a free "most popular" chart).
 * To change a section, edit its search words here.
 */
data class YtCategory(val id: String, val label: String, val query: YtQuery)

object YtCategories {

    /** Shown under the search box on the YouTube screen. */
    val youtube = listOf(
        YtCategory("trending", "Trending", YtQuery(chart = true)),
        YtCategory("music_top", "Top Music", YtQuery(chart = true, categoryId = "10")),
        YtCategory("songs_new", "New Songs", YtQuery(q = "new songs 2026 official video")),
        YtCategory("news_live", "Live News", YtQuery(q = "news live", live = true)),
        YtCategory("cricket", "Cricket", YtQuery(q = "cricket highlights")),
        YtCategory("cooking", "Cooking", YtQuery(q = "pakistani recipes urdu")),
        YtCategory("kids", "Kids", YtQuery(q = "cartoons for kids urdu hindi")),
        YtCategory("naat", "Naat / Islamic", YtQuery(q = "naat sharif"))
    )

    /** The "Movies & TV" screen: Hollywood, Bollywood, dramas, live TV channels and more. */
    val moviesTv = listOf(
        YtCategory("hollywood", "Hollywood", YtQuery(q = "hollywood full movie", duration = "long")),
        YtCategory("hollywood_hindi", "Hollywood (Hindi)", YtQuery(q = "hollywood movie hindi dubbed full", duration = "long")),
        YtCategory("bollywood", "Bollywood", YtQuery(q = "bollywood full movie", duration = "long")),
        YtCategory("south", "South (Hindi Dubbed)", YtQuery(q = "south indian movie hindi dubbed full movie", duration = "long")),
        YtCategory("pak_movies", "Pakistani Movies", YtQuery(q = "pakistani full movie", duration = "long")),
        YtCategory("punjabi", "Punjabi Movies", YtQuery(q = "punjabi full movie", duration = "long")),
        YtCategory("pak_dramas", "Pakistani Dramas", YtQuery(q = "pakistani drama full episode", duration = "long")),
        YtCategory("ind_serials", "Indian Serials", YtQuery(q = "hindi serial full episode", duration = "medium")),
        YtCategory("live_pk", "Live TV: Pakistan", YtQuery(q = "pakistan news live", live = true)),
        YtCategory("live_in", "Live TV: India", YtQuery(q = "hindi news live", live = true)),
        YtCategory("live_all", "Live TV: All", YtQuery(q = "live tv", live = true)),
        YtCategory("live_sports", "Live Sports", YtQuery(q = "sports live", live = true)),
        YtCategory("comedy", "Comedy", YtQuery(q = "comedy show full episode urdu hindi", duration = "long")),
        YtCategory("cartoon_movies", "Cartoon Movies", YtQuery(q = "animated movie full hindi urdu", duration = "long")),
        YtCategory("documentary", "Documentaries", YtQuery(q = "documentary urdu hindi", duration = "long")),
        YtCategory("songs", "Songs", YtQuery(q = "hindi urdu songs jukebox"))
    )
}
