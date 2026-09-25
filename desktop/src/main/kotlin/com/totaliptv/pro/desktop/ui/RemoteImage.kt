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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.TimeUnit

private val imageClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

/** Bounded thread-safe LRU cache preventing OutOfMemory on huge IPTV catalogs. */
private class LruBitmapCache(private val maxSize: Int = 300) {
    private val lock = Any()
    private val map = object : LinkedHashMap<String, ImageBitmap>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > maxSize
        }
    }

    operator fun get(key: String): ImageBitmap? = synchronized(lock) { map[key] }

    operator fun set(key: String, value: ImageBitmap) = synchronized(lock) {
        map[key] = value
    }
}

/** Negative cache preventing repeated network retries for 404 or broken image URLs. */
private class NegativeCache(private val maxEntries: Int = 500) {
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

private val bitmapCache = LruBitmapCache(maxSize = 350)
private val negativeCache = NegativeCache()

@Composable
fun RemoteArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    fallbackIcon: ImageVector,
    contentScale: ContentScale = ContentScale.Crop
) {
    var bitmap by remember(url) { mutableStateOf(url?.let { bitmapCache[it] }) }

    LaunchedEffect(url) {
        val u = url?.trim()?.takeIf { it.isNotBlank() } ?: run {
            bitmap = null
            return@LaunchedEffect
        }
        bitmapCache[u]?.let {
            bitmap = it
            return@LaunchedEffect
        }
        if (negativeCache.isFailed(u)) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
                    .get()
                    .build()
                imageClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        negativeCache.markFailed(u)
                        return@runCatching null
                    }
                    val bytes = resp.body?.bytes() ?: run {
                        negativeCache.markFailed(u)
                        return@runCatching null
                    }
                    if (bytes.isEmpty()) {
                        negativeCache.markFailed(u)
                        return@runCatching null
                    }
                    val rawSkia = SkiaImage.makeFromEncoded(bytes)
                    val maxDim = 600
                    val (w, h) = rawSkia.width to rawSkia.height
                    val sampledSkia = if (w > maxDim || h > maxDim) {
                        val scale = maxDim.toFloat() / maxOf(w, h)
                        val dstW = (w * scale).toInt().coerceAtLeast(1)
                        val dstH = (h * scale).toInt().coerceAtLeast(1)
                        val surface = Surface.makeRasterN32Premul(dstW, dstH)
                        val canvas = surface.canvas
                        val paint = Paint().apply { isAntiAlias = true }
                        val srcRect = Rect.makeWH(w.toFloat(), h.toFloat())
                        val dstRect = Rect.makeWH(dstW.toFloat(), dstH.toFloat())
                        canvas.drawImageRect(rawSkia, srcRect, dstRect, paint)
                        surface.makeImageSnapshot()
                    } else {
                        rawSkia
                    }
                    sampledSkia.toComposeImageBitmap().also {
                        bitmapCache[u] = it
                    }
                }
            }.getOrElse {
                negativeCache.markFailed(u)
                null
            }
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
