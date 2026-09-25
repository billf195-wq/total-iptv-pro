package com.totaliptv.pro.desktop.data

class CatalogRepository(
    private val xtreamApi: XtreamApi = XtreamApi()
) {
    private val xmltvByChannel = java.util.concurrent.ConcurrentHashMap<String, List<EpgProgram>>()
    @Volatile
    private var xmltvUrl: String? = null
    @Volatile
    private var xmltvLoaded: Boolean = false
    private val xmltvLock = Any()

    fun load(prefs: SavedPrefs): Catalog {
        return when (SourceType.valueOf(prefs.sourceType)) {
            SourceType.XTREAM -> {
                require(prefs.xtreamBaseUrl.isNotBlank()) { "Server URL required" }
                require(prefs.xtreamUsername.isNotBlank()) { "Username required" }
                require(prefs.xtreamPassword.isNotBlank()) { "Password required" }
                xmltvUrl = null
                xmltvLoaded = false
                xmltvByChannel.clear()
                xtreamApi.loadCatalog(prefs.xtreamBaseUrl, prefs.xtreamUsername, prefs.xtreamPassword)
            }
            SourceType.M3U -> {
                require(prefs.m3uUrl.isNotBlank()) { "M3U URL required" }
                val cat = M3uParser.loadFromUrl(prefs.m3uUrl)
                xmltvByChannel.clear()
                xmltvLoaded = false
                xmltvUrl = cat.xmltvUrl
                if (!cat.xmltvUrl.isNullOrBlank()) {
                    Thread({
                        runCatching { ensureXmltvLoaded() }
                    }, "m3u-xmltv").apply { isDaemon = true }.start()
                }
                cat
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
     * M3U listings come from the playlist's XMLTV url, keyed by tvg-id text.
     * Shared xmltv ids are corrected in [LiveEpgBinding].
     */
    fun loadChannelEpg(prefs: SavedPrefs, streamId: Int, limit: Int = 12): ChannelEpg =
        loadChannelEpg(prefs, streamId, epgChannelId = null, channelName = null, limit = limit)

    /**
     * Live EPG for one channel. Xtream stays keyed by stream_id.
     * M3U uses the XMLTV channel id string (tvg-id), not a hash of that id.
     */
    fun loadChannelEpg(
        prefs: SavedPrefs,
        streamId: Int,
        epgChannelId: String?,
        channelName: String?,
        limit: Int = 12
    ): ChannelEpg {
        val base = if (prefs.sourceType != SourceType.XTREAM.name) {
            ensureXmltvLoaded()
            val programs = xmltvPrograms(epgChannelId, channelName).take(limit)
            ChannelEpg(streamId = streamId, programs = programs)
        } else {
            try {
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
        return EpgUserOffset.apply(base, prefs.epgTimeOffsetHours)
    }

    fun loadChannelEpg(prefs: SavedPrefs, item: MediaItem, limit: Int = 12): ChannelEpg {
        val sid = item.xtreamStreamId ?: GuideKeys.of(item) ?: item.id.hashCode()
        return loadChannelEpg(prefs, sid, item.epgChannelId, item.name, limit)
    }

    private fun xmltvPrograms(epgChannelId: String?, channelName: String?): List<EpgProgram> {
        val id = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) xmltvByChannel[id]?.let { return it }
        val name = channelName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) xmltvByChannel[name]?.let { return it }
        return emptyList()
    }

    private fun ensureXmltvLoaded() {
        val url = xmltvUrl?.takeIf { it.isNotBlank() } ?: return
        if (xmltvLoaded) return
        synchronized(xmltvLock) {
            if (xmltvLoaded) return
            runCatching {
                xmltvByChannel.putAll(XmltvParser.loadFromUrl(url))
            }
            xmltvLoaded = true
        }
    }
}

/** UI map key for a channel. Xtream uses stream_id. M3U uses the stream URL, never the EPG id. */
object GuideKeys {
    fun of(item: MediaItem): Int? {
        item.xtreamStreamId?.takeIf { it > 0 }?.let { return it }
        val url = item.streamUrl.trim()
        if (url.isEmpty()) return null
        val mixed = url.hashCode() and 0x7fffffff
        return if (mixed == 0) 1 else mixed
    }
}
