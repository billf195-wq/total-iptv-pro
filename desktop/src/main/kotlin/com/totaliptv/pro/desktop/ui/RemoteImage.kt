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
import org.jetbrains.skia.Image as SkiaImage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val imageClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

private val bitmapCache = ConcurrentHashMap<String, ImageBitmap>()

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
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url(u)
                    .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
                    .get()
                    .build()
                imageClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val bytes = resp.body?.bytes() ?: return@runCatching null
                    if (bytes.isEmpty()) return@runCatching null
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap().also {
                        bitmapCache[u] = it
                    }
                }
            }.getOrNull()
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
