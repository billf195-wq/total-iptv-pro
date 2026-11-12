package com.totaliptv.pro.data.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Last screen / group / scroll / playback identity, written often so a crash or ANR
 * can reopen where the user was. Never stores a stream URL (those can contain
 * playlist credentials).
 */
@Serializable
data class LastPlace(
    /** home, live, movies, series, settings, guide, search, recordings */
    val screen: String = "home",
    val categoryId: String? = null,
    val firstVisibleIndex: Int = 0,
    val focusedItemId: String? = null,
    val playing: Boolean = false,
    val playItemId: String? = null,
    val playKind: String? = null,
    val playTitle: String? = null,
    val playCatalogId: String? = null,
    val playPositionMs: Long = 0L,
    val savedAtMs: Long = 0L
) {
    companion object {
        const val SCREEN_HOME = "home"
        const val SCREEN_LIVE = "live"
        const val SCREEN_MOVIES = "movies"
        const val SCREEN_SERIES = "series"
        const val SCREEN_SETTINGS = "settings"
        const val SCREEN_GUIDE = "guide"
        const val SCREEN_SEARCH = "search"
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

    fun peek(): LastPlace? = memory ?: load()

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

    fun saveBrowse(
        screen: String,
        categoryId: String? = null,
        firstVisibleIndex: Int = 0,
        focusedItemId: String? = null
    ) {
        update { cur ->
            cur.copy(
                screen = screen,
                categoryId = categoryId,
                firstVisibleIndex = firstVisibleIndex.coerceAtLeast(0),
                focusedItemId = focusedItemId,
                savedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun savePlaying(
        itemId: String,
        kind: String?,
        title: String?,
        catalogId: String?,
        positionMs: Long
    ) {
        update { cur ->
            cur.copy(
                playing = true,
                playItemId = itemId,
                playKind = kind,
                playTitle = title,
                playCatalogId = catalogId,
                playPositionMs = positionMs.coerceAtLeast(0L),
                savedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun clearPlaying() {
        update { cur ->
            cur.copy(playing = false, savedAtMs = System.currentTimeMillis())
        }
    }

    /** Wait for queued writes (unit tests). */
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
}
