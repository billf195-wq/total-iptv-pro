package com.totaliptv.pro.desktop.data

class CatalogRepository(
    private val xtreamApi: XtreamApi = XtreamApi()
) {
    fun load(prefs: SavedPrefs): Catalog {
        return when (SourceType.valueOf(prefs.sourceType)) {
            SourceType.XTREAM -> {
                require(prefs.xtreamBaseUrl.isNotBlank()) { "Server URL required" }
                require(prefs.xtreamUsername.isNotBlank()) { "Username required" }
                require(prefs.xtreamPassword.isNotBlank()) { "Password required" }
                xtreamApi.loadCatalog(prefs.xtreamBaseUrl, prefs.xtreamUsername, prefs.xtreamPassword)
            }
            SourceType.M3U -> {
                require(prefs.m3uUrl.isNotBlank()) { "M3U URL required" }
                M3uParser.loadFromUrl(prefs.m3uUrl)
            }
        }
    }

    fun loadSeriesDetail(prefs: SavedPrefs, series: MediaItem): SeriesDetail {
        require(prefs.sourceType == SourceType.XTREAM.name) {
            "Series episodes require an Xtream source"
        }
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

    /** VOD movie detail (plot + cast) via get_vod_info. Falls back to catalog fields on M3U / errors. */
    fun loadVodDetail(prefs: SavedPrefs, movie: MediaItem): VodDetail {
        val sid = movie.xtreamStreamId
        if (prefs.sourceType != SourceType.XTREAM.name || sid == null) {
            return VodDetail(
                streamId = sid ?: 0,
                name = movie.name,
                plot = movie.plot,
                cast = movie.cast,
                rating = movie.rating,
                year = movie.year,
                genre = movie.genre,
                posterUrl = movie.posterUrl ?: movie.logoUrl,
                backdropUrl = movie.backdropUrl,
                streamUrl = movie.streamUrl,
                catalogId = movie.id,
                categoryId = movie.categoryId
            )
        }
        return try {
            xtreamApi.loadVodDetail(
                baseUrl = prefs.xtreamBaseUrl,
                username = prefs.xtreamUsername,
                password = prefs.xtreamPassword,
                streamId = sid,
                fallbackName = movie.name,
                fallbackPoster = movie.posterUrl ?: movie.logoUrl,
                fallbackBackdrop = movie.backdropUrl,
                fallbackStreamUrl = movie.streamUrl,
                fallbackPlot = movie.plot,
                fallbackRating = movie.rating,
                fallbackYear = movie.year,
                fallbackGenre = movie.genre,
                catalogId = movie.id,
                categoryId = movie.categoryId
            )
        } catch (_: Throwable) {
            VodDetail(
                streamId = sid,
                name = movie.name,
                plot = movie.plot,
                cast = movie.cast,
                rating = movie.rating,
                year = movie.year,
                genre = movie.genre,
                posterUrl = movie.posterUrl ?: movie.logoUrl,
                backdropUrl = movie.backdropUrl,
                streamUrl = movie.streamUrl,
                catalogId = movie.id,
                categoryId = movie.categoryId
            )
        }
    }

    /**
     * Live EPG for one channel (short listing). Always keyed by Xtream
     * `stream_id` — never `num`, list index, or `epg_channel_id`.
     * M3U returns empty. Shared xmltv ids are corrected in [LiveEpgBinding].
     */
    fun loadChannelEpg(prefs: SavedPrefs, streamId: Int, limit: Int = 12): ChannelEpg {
        if (prefs.sourceType != SourceType.XTREAM.name) {
            return ChannelEpg(streamId = streamId, programs = emptyList())
        }
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
