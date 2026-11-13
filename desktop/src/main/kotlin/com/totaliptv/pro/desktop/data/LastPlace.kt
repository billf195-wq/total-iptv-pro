package com.totaliptv.pro.desktop.data

import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Last screen and group, written often so a crash or restart can reopen where
 * the user was. Never stores a stream URL (those can contain credentials).
 */
@Serializable
data class LastPlace(
    /** home, live, movies, series, settings, guide, favorites, recordings */
    val screen: String = SCREEN_HOME,
    val categoryId: String? = null,
    val savedAtMs: Long = 0L
) {
    companion object {
        const val SCREEN_HOME = "home"
        const val SCREEN_LIVE = "live"
        const val SCREEN_MOVIES = "movies"
        const val SCREEN_SERIES = "series"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_GUIDE = "guide"
        const val SCREEN_FAVORITES = "favorites"
        const val SCREEN_RECORDINGS = "recordings"
    }
}

class LastPlaceStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    @Volatile
    private var memory: LastPlace? = null
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "last-place-writer").apply { isDaemon = true }
    }

    fun peek(): LastPlace? = memory

    fun load(): LastPlace? = synchronized(lock) {
        memory?.let { return it }
        val loaded = runCatching {
            if (!file.isFile) return@runCatching null
            json.decodeFromString(LastPlace.serializer(), file.readText())
        }.getOrNull()
        memory = loaded
        loaded
    }

    fun update(transform: (LastPlace) -> LastPlace) {
        val next: LastPlace
        synchronized(lock) {
            val cur = memory ?: load() ?: LastPlace()
            next = transform(cur)
            memory = next
        }
        writer.execute { write(next) }
    }

    fun saveBrowse(screen: String, categoryId: String? = null) {
        update { cur ->
            cur.copy(
                screen = screen,
                categoryId = categoryId,
                savedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun flush(timeoutMs: Long = 2_000L): Boolean {
        val done = CountDownLatch(1)
        writer.execute { done.countDown() }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun write(place: LastPlace) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(LastPlace.serializer(), place))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        val shared: LastPlaceStore by lazy {
            LastPlaceStore(AppPaths.configDir.resolve("last-place.json").toFile())
        }
    }
}
