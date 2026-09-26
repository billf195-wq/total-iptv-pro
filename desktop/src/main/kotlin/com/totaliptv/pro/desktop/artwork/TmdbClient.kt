package com.totaliptv.pro.desktop.artwork

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Shared TMDB HTTP client for poster lookups and ratings. */
object TmdbClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun get(request: TmdbRequest): String? {
        if (TmdbAuth.normalize(request.key).isEmpty()) return null
        return runCatching {
            val builder = Request.Builder()
                .url(TmdbAuth.url(request))
                .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
                .header("accept", "application/json")
            TmdbAuth.authorization(request)?.let { builder.header("Authorization", it) }
            http.newCall(builder.build()).execute().use { resp ->
                resp.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
