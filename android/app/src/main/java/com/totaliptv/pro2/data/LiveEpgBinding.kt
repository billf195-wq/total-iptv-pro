package com.totaliptv.pro2.data

/**
 * Bind a live channel to the program list that belongs to **that** stream.
 *
 * Xtream `get_short_epg` / `get_simple_data_table` are requested with
 * [MediaItem.xtreamStreamId]. The panel then resolves XMLTV via
 * `epg_channel_id`. 24/7 movie bouquets (USA Movies) often give every
 * stream the same xmltv id, so Masters of the Universe and The Godfather
 * share one lineup. Playback is still the unique `stream_id`; the guide
 * must not show the shared "now" title when it is a different movie.
 */
object LiveEpgBinding {
    private val GENERIC_WORDS = setOf(
        "movies", "movie", "news", "sports", "sport", "network", "live",
        "channel", "tv", "usa", "us", "uk", "hd", "sd", "fhd", "uhd"
    )

    fun normalizeTitle(raw: String): String =
        raw.lowercase()
            .replace(Regex("\\(\\d{4}\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .replace(Regex("\\b(the|a|an|and|of|hd|sd|fhd|uhd|4k|us|usa)\\b"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    fun titlesMatch(channelName: String, programTitle: String): Boolean {
        val a = normalizeTitle(channelName)
        val b = normalizeTitle(programTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length >= 4 && b.length >= 4 && (a.contains(b) || b.contains(a))) return true
        val ta = a.split(' ').filter { it.length > 2 }.toSet()
        val tb = b.split(' ').filter { it.length > 2 }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val need = minOf(2, minOf(ta.size, tb.size))
        return ta.intersect(tb).size >= need
    }

    fun looksLikeSpecificTitle(name: String): Boolean {
        val words = normalizeTitle(name).split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return false
        if (words.all { it in GENERIC_WORDS }) return false
        return words.any { it !in GENERIC_WORDS }
    }

    fun sharesEpgChannelId(channel: MediaItem, siblings: List<MediaItem>): Boolean {
        val id = channel.epgChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return siblings.any { other ->
            other.xtreamStreamId != channel.xtreamStreamId &&
                other.epgChannelId?.equals(id, ignoreCase = true) == true
        }
    }

    /**
     * Programs to draw for [channel]. Fetch stays keyed by stream_id; this
     * replaces a shared-xmltv "now" (Godfather) with the tuned stream title
     * (Masters of the Universe) when those names do not match.
     */
    fun bindForDisplay(
        channel: MediaItem,
        programs: List<EpgProgram>,
        siblings: List<MediaItem> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): List<EpgProgram> {
        val sid = channel.xtreamStreamId
        if (programs.any { it.channelStreamId > 0 && sid != null && it.channelStreamId != sid }) {
            return overlayNow(channel, programs.filter { it.channelStreamId == sid || it.channelStreamId <= 0 }, nowMs)
                .ifEmpty { overlayNow(channel, emptyList(), nowMs) }
        }
        val nowProg = programs.find { it.contains(nowMs) }
        if (nowProg != null && titlesMatch(channel.name, nowProg.title)) return programs
        if (programs.any { titlesMatch(channel.name, it.title) }) return programs
        val shouldOverlay = looksLikeSpecificTitle(channel.name) &&
            (programs.isNotEmpty() || sharesEpgChannelId(channel, siblings))
        if (!shouldOverlay && !sharesEpgChannelId(channel, siblings)) return programs
        if (!looksLikeSpecificTitle(channel.name)) return programs
        return overlayNow(channel, programs, nowMs)
    }

    private fun overlayNow(
        channel: MediaItem,
        programs: List<EpgProgram>,
        nowMs: Long
    ): List<EpgProgram> {
        val nowProg = programs.find { it.contains(nowMs) }
        val endMs = (nowProg?.endMs ?: (nowMs + 2 * 60 * 60_000L)).coerceAtLeast(nowMs + 30 * 60_000L)
        val startMs = nowProg?.startMs?.takeIf { it <= nowMs } ?: (nowMs - (nowMs % 60_000L))
        val overlay = EpgProgram(
            id = "bound-${channel.id}-$startMs",
            title = channel.name,
            description = nowProg?.title?.takeIf { it.isNotBlank() && !titlesMatch(channel.name, it) }
                ?.let { "Shared guide listing: $it" },
            startMs = startMs,
            endMs = endMs,
            channelStreamId = channel.xtreamStreamId ?: 0
        )
        val rest = programs.filter { !it.contains(nowMs) }
        return listOf(overlay) + rest
    }
}
