package com.totaliptv.pro.artwork

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.size.Precision
import coil.size.Scale
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
            // 1.4.68 wrote a second Coil directory. Keep only image_cache.
            runCatching { app.cacheDir.resolve("artwork-cache").deleteRecursively() }
            combine(prefs.sharpPosters, prefs.tmdbRatings, prefs.tmdbApiKey) { sharp, ratings, key ->
                Triple(sharp, ratings, key)
            }.collect { (sharp, ratings, key) ->
                apply(app, sharp, ratings, key)
            }
        }
    }

    private fun installImageLoader(context: Context) {
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val loader = ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(ArtworkDecode.memoryCacheBytes(memoryClass))
                    .weakReferencesEnabled(false)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(ArtworkDecode.DISK_CACHE_DIR))
                    .maxSizeBytes(ArtworkDecode.DISK_CACHE_CAP_BYTES)
                    .build()
            }
            .allowRgb565(true)
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

fun tvImageRequest(
    context: Context,
    url: String?,
    role: ArtworkRole,
    sharp: Boolean,
    widthDp: Float? = null,
    heightDp: Float? = null
): ImageRequest {
    val source = if (sharp && role != ArtworkRole.LOGO) TmdbArtwork.rewrite(url, role) else url
    val density = context.resources.displayMetrics.density
    val builder = ImageRequest.Builder(context)
        .data(source)
        .crossfade(false)
    when (role) {
        ArtworkRole.LOGO -> builder.size(128, 128)
        ArtworkRole.POSTER -> {
            val (w, h) = ArtworkDecode.pixels(
                widthDp ?: CLASSIC_POSTER_W_DP,
                heightDp ?: CLASSIC_POSTER_H_DP,
                density,
                ArtworkDecode.POSTER_MAX_W,
                ArtworkDecode.POSTER_MAX_H
            )
            // RGB_565 is half of ARGB and posters have no alpha. Hardware would keep a second copy.
            builder.size(w, h)
                .precision(Precision.EXACT)
                .scale(Scale.FIT)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(false)
        }
        ArtworkRole.BACKDROP -> {
            val (w, h) = ArtworkDecode.clamp(
                widthDp ?: 480f,
                heightDp ?: CLASSIC_HERO_H_DP,
                density,
                ArtworkDecode.BACKDROP_MAX_W,
                ArtworkDecode.BACKDROP_MAX_H
            )
            builder.size(w, h)
                .precision(Precision.INEXACT)
                .scale(Scale.FILL)
                .allowHardware(true)
        }
        ArtworkRole.DETAIL -> {
            val (w, h) = ArtworkDecode.pixels(
                widthDp ?: DETAIL_W_DP,
                heightDp ?: DETAIL_H_DP,
                density,
                ArtworkDecode.DETAIL_MAX_W,
                ArtworkDecode.DETAIL_MAX_H
            )
            builder.size(w, h)
                .precision(Precision.INEXACT)
                .scale(Scale.FIT)
                .allowHardware(true)
        }
    }
    return builder.build()
}

/** Classic poster is 103dp × 0.88, at a 2:3 frame. */
private const val CLASSIC_POSTER_W_DP = 103f * 0.88f
private const val CLASSIC_POSTER_H_DP = CLASSIC_POSTER_W_DP * 1.5f
private const val CLASSIC_HERO_H_DP = 175f * 0.88f
private const val DETAIL_W_DP = 168f
private const val DETAIL_H_DP = 252f

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
