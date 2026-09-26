package com.totaliptv.pro.artwork

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.size.Precision
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.diagnostics.DebugLog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** TV-only Coil cache, rating engine, and the badge composable. Phone uses [ArtworkFlavor] as a no-op. */
object ArtworkFlavor {
    fun install(app: Application) {
        installImageLoader(app)
        TvRatings.install(app)
        val prefs = (app as TotalIptvProApp).preferences
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(prefs.sharpPosters, prefs.tmdbRatings, prefs.tmdbApiKey) { sharp, ratings, key ->
                Triple(sharp, ratings, key)
            }.collect { (sharp, ratings, key) ->
                apply(app, sharp, ratings, key)
            }
        }
    }

    private fun installImageLoader(context: Context) {
        val loader = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.30)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("artwork-cache"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
        Coil.setImageLoader(loader)
    }

    fun apply(context: Context, sharp: Boolean, ratings: Boolean, prefKey: String) {
        val resolved = TmdbAuth.normalize(
            TmdbArtwork.resolveApiKey(prefKey, TmdbKeyFile.read(context))
        )
        ArtworkRuntime.sharpPosters = sharp
        ArtworkRuntime.tmdbRatings = ratings
        ArtworkRuntime.apiKey = resolved
        TvRatings.noteSettingsChanged()
    }
}

object TvRatings {
    private val settingsState = MutableStateFlow(0)
    val settingsEpoch: StateFlow<Int> = settingsState

    @Volatile
    private var engine: TmdbRatingEngine? = null

    private val idleRevision = MutableStateFlow(0)
    val revision: StateFlow<Int>
        get() = engine?.revision ?: idleRevision

    fun install(context: Context) {
        if (engine != null) return
        engine = TmdbRatingEngine(
            storeFile = File(context.filesDir, "tmdb-ratings.json"),
            log = { line -> DebugLog.append(context, "tmdb", line) },
            ratingsEnabled = { ArtworkRuntime.tmdbRatings },
            apiKey = { ArtworkRuntime.apiKey }
        )
    }

    fun noteSettingsChanged() {
        settingsState.value = settingsState.value + 1
    }

    fun label(item: MediaItem): String? {
        val active = engine
        val provider = TmdbRatings.providerScore(item.rating)
        val tmdb = active?.peek(item)
        val enabled = active?.ratingsActive() == true
        return TmdbRatings.formatScore(TmdbRatings.displayScore(provider, tmdb, enabled))
    }

    fun rankScore(item: MediaItem): Double {
        val active = engine
        val provider = TmdbRatings.providerScore(item.rating)
        return TmdbRatings.rankScore(provider, active?.peek(item), active?.ratingsActive() == true)
    }

    /** Scores copied once from the published rating table. Sorting must use this map, not [rankScore]. */
    fun rankScores(items: List<MediaItem>): Map<String, Double> {
        val active = engine
        if (active == null) {
            return items.associate { item ->
                item.id to TmdbRatings.rankScore(TmdbRatings.providerScore(item.rating), null, false)
            }
        }
        return active.rankScores(items)
    }

    fun isIdle(): Boolean = engine?.isIdle() != false

    fun enqueueVisible(item: MediaItem) {
        if (item.kind != ContentKind.VOD && item.kind != ContentKind.SERIES) return
        engine?.enqueue(listOf(item), front = true)
    }

    fun enqueueBackfill(movies: List<MediaItem>, series: List<MediaItem>) {
        engine?.enqueue(movies.take(BACKFILL_CAP) + series.take(BACKFILL_CAP), front = false)
    }

    private const val BACKFILL_CAP = 2500
}

fun tvImageRequest(context: Context, url: String?, role: ArtworkRole, sharp: Boolean): ImageRequest {
    val source = if (sharp && role != ArtworkRole.LOGO) TmdbArtwork.rewrite(url, role) else url
    val builder = ImageRequest.Builder(context)
        .data(source)
        .crossfade(false)
    when (role) {
        ArtworkRole.LOGO -> builder.size(128, 128)
        ArtworkRole.POSTER -> if (sharp) {
            builder.size(500, 750)
                .precision(Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
        } else {
            builder.size(220, 330)
        }
        ArtworkRole.BACKDROP -> if (sharp) {
            builder.size(780, 439)
                .precision(Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
        } else {
            builder.size(220, 330)
        }
        ArtworkRole.DETAIL -> if (sharp) {
            builder.size(780, 1170)
                .precision(Precision.EXACT)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
        } else {
            builder.size(220, 330)
        }
    }
    return builder.build()
}

/** Badge text for a visible tile. Enqueues a TMDB lookup without blocking the caller. */
@Composable
fun MediaItem.tvRatingLabel(): String? {
    val engineRevision by TvRatings.revision.collectAsState()
    val settings by TvRatings.settingsEpoch.collectAsState()
    LaunchedEffect(id, settings) {
        TvRatings.enqueueVisible(this@tvRatingLabel)
    }
    return remember(engineRevision, settings, id, rating, tmdbId) {
        TvRatings.label(this@tvRatingLabel)
    }
}
