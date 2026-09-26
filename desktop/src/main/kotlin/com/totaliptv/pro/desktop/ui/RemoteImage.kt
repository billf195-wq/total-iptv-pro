package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import com.totaliptv.pro.desktop.artwork.ArtworkDecode
import com.totaliptv.pro.desktop.artwork.ArtworkDiskCache
import com.totaliptv.pro.desktop.artwork.ArtworkRole
import com.totaliptv.pro.desktop.artwork.ArtworkSettings
import com.totaliptv.pro.desktop.artwork.PosterLoadLog
import com.totaliptv.pro.desktop.artwork.TmdbArtwork
import com.totaliptv.pro.desktop.data.ContentKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.TimeUnit

val LocalArtworkPage = compositionLocalOf { "posters" }

private val imageClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

/** Pixel-budget LRU so HD posters do not grow without a bound. */
private class LruBitmapCache(private val maxPixels: Long = 180L * 780 * 1170) {
    private val lock = Any()
    private var pixels = 0L
    private val map = object : LinkedHashMap<String, ImageBitmap>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            if (pixels <= maxPixels || size <= 1) return false
            val dropped = eldest?.value ?: return false
            pixels -= dropped.width.toLong() * dropped.height
            return true
        }
    }

    operator fun get(key: String): ImageBitmap? = synchronized(lock) { map[key] }

    operator fun set(key: String, value: ImageBitmap) = synchronized(lock) {
        val previous = map.put(key, value)
        if (previous != null) pixels -= previous.width.toLong() * previous.height
        pixels += value.width.toLong() * value.height
        map[key] = value
    }
}

/** Negative cache preventing repeated network retries for 404 or broken image URLs. */
private class NegativeCache(private val maxEntries: Int = 800) {
    private val lock = Any()
    private val set = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
            return size > maxEntries
        }
    }

    fun isFailed(url: String): Boolean = synchronized(lock) {
        val time = set[url] ?: return false
        if (System.currentTimeMillis() - time > 120_000L) {
            set.remove(url)
            false
        } else {
            true
        }
    }

    fun markFailed(url: String) = synchronized(lock) {
        set[url] = System.currentTimeMillis()
    }
}

private val bitmapCache = LruBitmapCache()
private val negativeCache = NegativeCache()
private val diskCache by lazy { ArtworkDiskCache.shared() }

private fun cacheKey(url: String, role: ArtworkRole, sharp: Boolean): String =
    "$url|${role.name}|sharp=$sharp"

@Composable
fun RemoteArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector,
    contentScale: ContentScale = ContentScale.Crop,
    role: ArtworkRole = ArtworkRole.POSTER,
    tmdbId: String? = null,
    title: String? = null,
    year: Int? = null,
    contentKind: ContentKind? = null
) {
    val page = LocalArtworkPage.current
    val sharp = ArtworkSettings.sharpPosters && role != ArtworkRole.LOGO
    val prepared = if (sharp) TmdbArtwork.rewrite(url, role) else url?.trim()?.takeIf { it.isNotBlank() }
    val memoryKey = prepared?.let { cacheKey(it, role, sharp) }
    var bitmap by remember(memoryKey) { mutableStateOf(memoryKey?.let { bitmapCache[it] }) }

    LaunchedEffect(memoryKey, tmdbId, title, year, sharp) {
        val u = prepared
        if (u == null && !(sharp && (tmdbId != null || !title.isNullOrBlank()))) {
            bitmap = null
            return@LaunchedEffect
        }
        if (u != null) {
            val key = cacheKey(u, role, sharp)
            bitmapCache[key]?.let {
                bitmap = it
                if (role != ArtworkRole.LOGO) {
                    PosterLoadLog.record(page, "memory", 0, it.width, it.height)
                }
                return@LaunchedEffect
            }
        }
        bitmap = withContext(Dispatchers.IO) {
            loadArtwork(
                url = u,
                role = role,
                sharp = sharp,
                tmdbId = tmdbId,
                title = title,
                year = year,
                contentKind = contentKind,
                page = page
            )
        }
    }

    Box(modifier.background(TipSurfaceAlt), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else {
            Icon(fallbackIcon, contentDescription = contentDescription, tint = TipBlue)
        }
    }
}

private fun loadArtwork(
    url: String?,
    role: ArtworkRole,
    sharp: Boolean,
    tmdbId: String?,
    title: String?,
    year: Int?,
    contentKind: ContentKind?,
    page: String
): ImageBitmap? {
    val started = System.nanoTime()
    val target = TmdbArtwork.targetLongEdge(role)
    var source = "network"
    var current = url
    var bytes = current?.let { readBytes(it) }?.also { (data, from) ->
        source = from
        return@also
    }?.first
    var decoded = bytes?.let { decodeScaled(it, target, sharp) }
    val tooSmall = decoded == null || TmdbArtwork.isTooSmall(decoded.width, decoded.height, target)
    if (sharp && tooSmall) {
        val better = TmdbArtwork.lookup(
            apiKey = ArtworkSettings.tmdbApiKey,
            tmdbId = tmdbId,
            title = title,
            year = year,
            kind = contentKind,
            role = role,
            fetch = ::httpText
        )
        if (!better.isNullOrBlank() && better != current) {
            val fetched = readBytes(better)
            if (fetched != null) {
                current = better
                bytes = fetched.first
                source = fetched.second
                decoded = decodeScaled(bytes, target, highQuality = true)
            }
        }
    }
    val image = decoded ?: return null
    val keyUrl = current ?: return null
    bitmapCache[cacheKey(keyUrl, role, sharp)] = image
    if (role != ArtworkRole.LOGO) {
        val ms = (System.nanoTime() - started) / 1_000_000
        PosterLoadLog.record(page, source, ms, image.width, image.height)
    }
    return image
}

private fun readBytes(url: String): Pair<ByteArray, String>? {
    if (negativeCache.isFailed(url)) return null
    diskCache.read(url)?.let { return it to "disk" }
    val downloaded = httpBytes(url) ?: return null
    diskCache.write(url, downloaded)
    return downloaded to "network"
}

private fun httpBytes(url: String): ByteArray? {
    return runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
            .get()
            .build()
        imageClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                negativeCache.markFailed(url)
                return null
            }
            val bytes = resp.body?.bytes()
            if (bytes == null || bytes.isEmpty()) {
                negativeCache.markFailed(url)
                return null
            }
            bytes
        }
    }.getOrElse {
        negativeCache.markFailed(url)
        null
    }
}

private fun httpText(url: String): String? {
    return runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
            .get()
            .build()
        imageClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

private fun decodeScaled(bytes: ByteArray, targetLongEdge: Int, highQuality: Boolean): ImageBitmap? {
    return runCatching {
        val raw = SkiaImage.makeFromEncoded(bytes)
        val edge = if (highQuality) targetLongEdge else 600
        ArtworkDecode.scale(raw, edge, highQuality = highQuality).toComposeImageBitmap()
    }.getOrNull()
}
