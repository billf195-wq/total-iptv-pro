package com.totaliptv.pro2.data

class CatalogRepository(
    private val xtreamApi: XtreamApi = XtreamApi()
) {
    fun load(prefs: SavedPrefs): Catalog {
        require(prefs.xtreamBaseUrl.isNotBlank()) { "Server URL required" }
        require(prefs.xtreamUsername.isNotBlank()) { "Username required" }
        require(prefs.xtreamPassword.isNotBlank()) { "Password required" }
        return xtreamApi.loadCatalog(prefs.xtreamBaseUrl, prefs.xtreamUsername, prefs.xtreamPassword)
    }

    fun loadSeriesDetail(prefs: SavedPrefs, series: MediaItem): SeriesDetail {
        val sid = series.xtreamStreamId ?: error("Missing series id")
        return xtreamApi.loadSeriesDetail(
            baseUrl = prefs.xtreamBaseUrl,
            username = prefs.xtreamUsername,
            password = prefs.xtreamPassword,
            seriesId = sid,
            seriesName = series.name,
            fallbackPoster = series.posterUrl,
            fallbackBackdrop = series.backdropUrl
        )
    }

    fun loadChannelEpg(prefs: SavedPrefs, streamId: Int, limit: Int = 12): ChannelEpg {
        return try {
            xtreamApi.loadShortEpg(
                prefs.xtreamBaseUrl, prefs.xtreamUsername, prefs.xtreamPassword, streamId, limit
            )
        } catch (_: Throwable) {
            try {
                xtreamApi.loadSimpleEpgTable(
                    prefs.xtreamBaseUrl, prefs.xtreamUsername, prefs.xtreamPassword, streamId
                )
            } catch (_: Throwable) {
                ChannelEpg(streamId = streamId, programs = emptyList())
            }
        }
    }
}
