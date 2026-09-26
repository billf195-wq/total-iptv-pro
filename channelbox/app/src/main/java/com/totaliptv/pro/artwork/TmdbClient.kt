package com.totaliptv.pro.artwork

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Shared TMDB HTTP client. A blank or unusable key never opens a connection. */
object TmdbClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun get(request: TmdbRequest): String? {
        val key = TmdbAuth.normalize(request.key)
        if (!TmdbAuth.isUsable(key)) return null
        return runCatching {
            val builder = Request.Builder()
                .url(TmdbAuth.url(TmdbRequest(request.pathAndQuery, key)))
                .header("User-Agent", "TotalIPTVPro-Android/1.0")
                .header("Accept", "application/json")
            TmdbAuth.authorization(TmdbRequest(request.pathAndQuery, key))?.let {
                builder.header("Authorization", it)
            }
            http.newCall(builder.build()).execute().use { response ->
                response.body?.string()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }
}
