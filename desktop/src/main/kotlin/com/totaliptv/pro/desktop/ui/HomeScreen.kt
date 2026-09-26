package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.artwork.ArtworkSettings
import com.totaliptv.pro.desktop.artwork.TmdbRatingStore
import com.totaliptv.pro.desktop.artwork.TmdbRatings
import com.totaliptv.pro.desktop.data.Catalog
import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.ResumeStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TOP_N = 11
/** Enough titles to honestly claim a filtered row label. */
private const val MIN_FILTERED = 6
/** Prefer titles from roughly the last 3–5 calendar years when a year is known. */
private const val NEW_YEAR_WINDOW = 5
/** When year is unknown, treat catalog add/last_modified within this many years as "new". */
private const val NEW_ADDED_WINDOW_YEARS = 5L

data class TopRatedRow(
    val title: String,
    val items: List<MediaItem>
)

fun isLikelyAmerican(item: MediaItem): Boolean {
    val country = item.country?.lowercase()?.trim().orEmpty()
    if (country.isNotEmpty()) {
        if (country.contains("united states") || country.contains("usa")) return true
        if ((country.contains("united states of america") || country == "america" || country.contains("north america")) &&
            !country.contains("south america") && !country.contains("latin america")
        ) return true
        // Word-boundary style matches for US / U.S. / U.S.A.
        val tokens = country.split(Regex("""[,;/|]+|\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.any {
                it == "us" || it == "u.s" || it == "u.s." || it == "u.s.a" || it == "u.s.a." ||
                    it == "usa" || it == "united states" || it == "united states of america"
            }) {
            return true
        }
        if (Regex("""\busa\b|\bu\.s\.a\.?\b|\bu\.s\.?\b|\bus\b""").containsMatchIn(country)) return true
    }
    val genre = item.genre?.lowercase().orEmpty()
    val plot = item.plot?.lowercase().orEmpty()
    val group = item.groupTitle?.lowercase().orEmpty()
    val blob = "$genre $plot $group"
    if (blob.contains("united states") || Regex("""\busa\b|\bu\.s\.?\b""").containsMatchIn(blob)) return true
    if (blob.contains("american") && (
            blob.contains("united states") ||
                Regex("""\busa\b|\bu\.s\.?\b|\bus\b""").containsMatchIn(blob) ||
                group.contains("us ") || group.startsWith("us") || group.contains(" america")
            )
    ) {
        return true
    }
    // Category / group heuristics: "US Movies", "American Cinema", etc.
    if (Regex("""\b(us|u\.s\.?|usa|american)\b""").containsMatchIn(group) &&
        !Regex("""\b(ussr|ukraine|australia|austria)\b""").containsMatchIn(group)
    ) {
        return true
    }
    return false
}

/** Best-effort release year: MediaItem.year if set, else (YYYY) / trailing year in the title. */
fun guessReleaseYear(item: MediaItem): Int? {
    item.year?.takeIf { it in 1888..2100 }?.let { return it }
    val name = item.name
    Regex("""\((19|20)\d{2}\)""").find(name)?.groupValues?.get(0)
        ?.trim('(', ')')?.toIntOrNull()?.let { return it }
    Regex("""(?:^|[\s\-–—])((?:19|20)\d{2})\s*$""").find(name)?.groupValues?.get(1)
        ?.toIntOrNull()?.let { return it }
    return null
}

/**
 * "New" = recent release year when known (last ~NEW_YEAR_WINDOW years),
 * otherwise recent catalog added/last_modified (addedEpoch).
 */
fun isLikelyNew(
    item: MediaItem,
    nowEpochSec: Long = System.currentTimeMillis() / 1000L,
    currentYear: Int = java.time.Year.now().value
): Boolean {
    val year = guessReleaseYear(item)
    if (year != null) {
        return year >= (currentYear - NEW_YEAR_WINDOW)
    }
    val added = item.addedEpoch
    if (added > 0L) {
        val cutoff = nowEpochSec - (NEW_ADDED_WINDOW_YEARS * 365L * 24L * 3600L)
        return added >= cutoff
    }
    return false
}

/**
 * Home movies row: pool = new American when thick enough; rank by rating desc.
 * Labels stay honest — never say "American" / "new" unless that filter was applied.
 * [rank] defaults to the provider score with bogus 10.0s removed. Home passes TMDB rank when a key is on.
 */
fun pickTopRatedMovies(
    catalog: Catalog,
    rank: (MediaItem) -> Double = { TmdbRatings.providerScore(it) }
): TopRatedRow {
    val rated = catalog.vodItems.filter { rank(it) > 0.0 }
    val pool = rated.ifEmpty { catalog.vodItems }

    fun byRating(list: List<MediaItem>) =
        list.sortedByDescending(rank).take(TOP_N)

    val americanNew = byRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American movies", americanNew)
    }

    val newOnly = byRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new movies", newOnly)
    }

    val overall = byRating(pool)
    return TopRatedRow(
        "Top rated movies",
        overall.ifEmpty { catalog.vodItems.sortedByDescending { it.addedEpoch }.take(TOP_N) }
    )
}

/**
 * Home series row: same rules as movies — prefer new American, then new, then overall by rating.
 * Labels stay honest — never say "American" / "new" unless that filter was applied.
 */
fun pickTopRatedSeries(
    catalog: Catalog,
    rank: (MediaItem) -> Double = { TmdbRatings.providerScore(it) }
): TopRatedRow {
    val rated = catalog.seriesItems.filter { rank(it) > 0.0 }
    val pool = rated.ifEmpty { catalog.seriesItems }

    fun byRating(list: List<MediaItem>) =
        list.sortedByDescending(rank).take(TOP_N)

    val americanNew = byRating(pool.filter { isLikelyAmerican(it) && isLikelyNew(it) })
    if (americanNew.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new American series", americanNew)
    }

    val newOnly = byRating(pool.filter { isLikelyNew(it) })
    if (newOnly.size >= MIN_FILTERED) {
        return TopRatedRow("Top rated new series", newOnly)
    }

    val overall = byRating(pool)
    return TopRatedRow(
        "Top rated series",
        overall.ifEmpty { catalog.seriesItems.sortedByDescending { it.addedEpoch }.take(TOP_N) }
    )
}

fun resolveContinueItems(
    entries: List<ResumeStore.ResumeEntry>,
    catalog: Catalog
): List<Pair<ResumeStore.ResumeEntry, MediaItem?>> {
    return entries.map { entry ->
        val media = when (entry.kind) {
            ContentKind.VOD.name -> catalog.vodItems.find { it.id == entry.catalogId }
                ?: catalog.vodItems.find { it.xtreamStreamId == entry.xtreamStreamId }
            ContentKind.SERIES.name -> {
                val sid = entry.seriesId
                catalog.seriesItems.find { it.id == entry.catalogId }
                    ?: sid?.let { id -> catalog.seriesItems.find { it.xtreamStreamId == id } }
            }
            else -> null
        }
        entry to media
    }
}

@Composable
fun HomeScreen(
    catalog: Catalog,
    resumeEntries: List<ResumeStore.ResumeEntry>,
    onOpenVod: (MediaItem) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onResumeEntry: (ResumeStore.ResumeEntry, MediaItem?) -> Unit,
    posterColumns: Int = 11,
    modifier: Modifier = Modifier
) {
    val columns = posterColumns.let { if (it in setOf(5, 6, 8, 11)) it else 11 }
    val continuePairs = remember(resumeEntries, catalog, columns) {
        resolveContinueItems(resumeEntries, catalog).take(columns)
    }
    val ratingRevision by TmdbRatingStore.revision.collectAsState()
    val ratingsOn = ArtworkSettings.ratingsActive()
    val movieRow = remember(catalog, ratingRevision, ratingsOn) {
        pickTopRatedMovies(catalog) { TmdbRatingStore.rankScore(it) }
    }
    val seriesRow = remember(catalog, ratingRevision, ratingsOn) {
        pickTopRatedSeries(catalog) { TmdbRatingStore.rankScore(it) }
    }
    LaunchedEffect(catalog, ratingsOn, movieRow.items, seriesRow.items, continuePairs) {
        if (!ratingsOn) return@LaunchedEffect
        val visible = movieRow.items + seriesRow.items + continuePairs.mapNotNull { it.second }
        TmdbRatingStore.enqueue(visible, front = true)
        val rest = catalog.vodItems + catalog.seriesItems
        withContext(Dispatchers.Default) {
            TmdbRatingStore.enqueue(rest, front = false)
        }
    }

    CompositionLocalProvider(LocalArtworkPage provides "Home") {
    LazyColumn(
        modifier = modifier.fillMaxSize().tvContentBackground().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                "Home",
                style = MaterialTheme.typography.headlineMedium,
                color = TipOnBg
            )
            Text(
                "Continue watching and top picks from your catalog",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
        }

        item {
            SectionTitle("Continue watching")
            if (continuePairs.isEmpty()) {
                EmptyHint("Play a movie or series episode — it will show up here.")
            } else {
                HomePosterRow(columns = columns) { cardWidth ->
                    items(continuePairs, key = { it.first.key }) { (entry, media) ->
                        ContinueCard(
                            entry = entry,
                            media = media,
                            cardWidth = cardWidth,
                            onClick = { onResumeEntry(entry, media) }
                        )
                    }
                }
            }
        }

        item {
            SectionTitle(movieRow.title)
            if (movieRow.items.isEmpty()) {
                EmptyHint("No movies in catalog yet.")
            } else {
                HomePosterRow(columns = columns) { cardWidth ->
                    items(movieRow.items.take(columns), key = { it.id }) { item ->
                        HomePosterCard(
                            title = item.name,
                            posterUrl = item.artworkUrl(),
                            subtitle = item.year?.toString(),
                            kind = ContentKind.VOD,
                            tmdbId = item.tmdbId,
                            year = item.year,
                            cardWidth = cardWidth,
                            onClick = { onOpenVod(item) },
                            ratingScore = rememberRatingScore(item)
                        )
                    }
                }
            }
        }

        item {
            SectionTitle(seriesRow.title)
            if (seriesRow.items.isEmpty()) {
                EmptyHint("No series in catalog yet.")
            } else {
                HomePosterRow(columns = columns) { cardWidth ->
                    items(seriesRow.items.take(columns), key = { it.id }) { item ->
                        HomePosterCard(
                            title = item.name,
                            posterUrl = item.artworkUrl(),
                            subtitle = item.year?.toString(),
                            kind = ContentKind.SERIES,
                            tmdbId = item.tmdbId,
                            year = item.year,
                            cardWidth = cardWidth,
                            onClick = { onOpenSeries(item) },
                            ratingScore = rememberRatingScore(item)
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = TipOnBg,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(TipSurface)
            .padding(16.dp)
    ) {
        Text(text, color = TipMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ContinueCard(
    entry: ResumeStore.ResumeEntry,
    media: MediaItem?,
    cardWidth: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val poster = media?.artworkUrl() ?: entry.posterUrl
    val subtitle = entry.episodeLabel?.takeIf { it.isNotBlank() }
        ?: if (entry.kind == ContentKind.SERIES.name) "Series" else "Movie"
    HomePosterCard(
        title = entry.name,
        posterUrl = poster,
        subtitle = subtitle,
        kind = if (entry.kind == ContentKind.SERIES.name) ContentKind.SERIES else ContentKind.VOD,
        tmdbId = media?.tmdbId,
        year = media?.year,
        cardWidth = cardWidth,
        onClick = onClick,
        showPlayBadge = true,
        ratingScore = if (media != null) rememberRatingScore(media) else 0.0,
        progressPercent = entry.progressPercent.takeIf { entry.hasProgress }
    )
}

@Composable
private fun HomePosterRow(
    columns: Int,
    spacing: androidx.compose.ui.unit.Dp = 12.dp,
    content: androidx.compose.foundation.lazy.LazyListScope.(cardWidth: androidx.compose.ui.unit.Dp) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gapTotal = spacing * (columns - 1).coerceAtLeast(0)
        val cardWidth = ((maxWidth - gapTotal) / columns).coerceAtLeast(72.dp)
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            userScrollEnabled = false
        ) {
            content(cardWidth)
        }
    }
}

@Composable
private fun HomePosterCard(
    title: String,
    posterUrl: String?,
    subtitle: String?,
    kind: ContentKind,
    tmdbId: String? = null,
    year: Int? = null,
    cardWidth: androidx.compose.ui.unit.Dp = 140.dp,
    onClick: () -> Unit,
    showPlayBadge: Boolean = false,
    ratingScore: Double = 0.0,
    progressPercent: Int? = null
) {
    Column(
        Modifier
            .width(cardWidth)
            .clip(RoundedCornerShape(10.dp))
            .background(TipSurface)
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Box {
            RemoteArtwork(
                url = posterUrl,
                contentDescription = title,
                tmdbId = tmdbId,
                title = title,
                year = year,
                contentKind = kind,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .clip(RoundedCornerShape(8.dp)),
                fallbackIcon = if (kind == ContentKind.SERIES) Icons.Default.Tv else Icons.Default.Movie,
                contentScale = ContentScale.Crop
            )
            RatingBadge(
                score = ratingScore,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            val watched = progressPercent?.takeIf { it in 1..94 }
            if (watched != null) {
                LinearProgressIndicator(
                    progress = { watched / 100f },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp),
                    color = TipAccent,
                    trackColor = TipSurfaceAlt
                )
            }
            if (showPlayBadge) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(TipBlue.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TipOnAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = TipOnBg
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
        }
    }
}
