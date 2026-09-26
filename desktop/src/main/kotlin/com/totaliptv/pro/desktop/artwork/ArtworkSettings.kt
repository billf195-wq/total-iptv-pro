package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.util.AppPaths
import java.nio.file.Files

/** Runtime switch for HD artwork. Default on. TMDB HTTP runs only when a key is configured. */
object ArtworkSettings {
    @Volatile
    var sharpPosters: Boolean = true

    @Volatile
    var tmdbApiKey: String = ""

    /** Honored only when a key is actually available. Defaults on. */
    @Volatile
    var tmdbRatings: Boolean = true

    fun apply(sharp: Boolean, prefKey: String? = null, ratings: Boolean = true) {
        sharpPosters = sharp
        tmdbRatings = ratings
        tmdbApiKey = TmdbArtwork.resolveApiKey(
            pref = prefKey,
            env = System.getenv("TMDB_API_KEY"),
            fileText = readKeyFile()
        )
    }

    fun hasTmdbKey(): Boolean = tmdbApiKey.isNotBlank()

    fun ratingsActive(): Boolean = tmdbRatings && hasTmdbKey()

    private fun readKeyFile(): String? {
        val file = AppPaths.configDir.resolve("tmdb-api-key.txt")
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readString(file) }.getOrNull()
    }
}
