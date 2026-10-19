package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.nio.file.Files

class VodDiskCacheTest {
    private fun item(id: String, kind: ContentKind) =
        MediaItem(id = id, name = id, streamUrl = "http://x/$id", categoryId = null, kind = kind)

    private val cats = listOf(
        Category("live-1", "Live", ContentKind.LIVE),
        Category("vod-1", "Action", ContentKind.VOD),
        Category("series-1", "Drama", ContentKind.SERIES)
    )
    private val items = listOf(
        item("live-1", ContentKind.LIVE),
        item("vod-1", ContentKind.VOD),
        item("vod-2", ContentKind.VOD),
        item("series-1", ContentKind.SERIES)
    )

    @Test
    fun roundTripKeepsMoviesAndSeriesButNotLive() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items, nowMs = 42L)
        val snap = VodDiskCache.read(dir, "src-a")
        assertNotNull(snap)
        assertEquals(42L, snap!!.savedAtMs)
        assertEquals(3, snap.items.size)
        assertTrue(snap.items.none { it.kind == ContentKind.LIVE })
        assertTrue(snap.categories.none { it.kind == ContentKind.LIVE })
    }

    @Test
    fun otherSourceIsIgnored() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items)
        assertNull(VodDiskCache.read(dir, "src-b"))
    }

    @Test
    fun emptyMovieListNeverOverwritesGoodCache() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items)
        VodDiskCache.write(dir, "src-a", cats, items.filter { it.kind != ContentKind.VOD })
        assertEquals(2, VodDiskCache.read(dir, "src-a")!!.items.count { it.kind == ContentKind.VOD })
    }

    @Test
    fun corruptFileReadsAsNull() {
        val dir = Files.createTempDirectory("vod").toFile()
        java.io.File(dir, VodDiskCache.FILE_NAME).writeText("{not json")
        assertNull(VodDiskCache.read(dir, "src-a"))
    }

    @Test
    fun onlyNonEmptyResultsReplace() {
        assertFalse(VodDiskCache.shouldReplace(emptyList()))
        assertTrue(VodDiskCache.shouldReplace(listOf(item("vod-1", ContentKind.VOD))))
    }
}
