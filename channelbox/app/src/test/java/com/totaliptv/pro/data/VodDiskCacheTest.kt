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


    @Test
    fun writerCommitsInBatchesAndAbortsLeaveLastGood() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items, nowMs = 1L)
        val writer = VodDiskCache.Writer(dir, "src-a", cats.filter { it.kind != ContentKind.LIVE }, nowMs = 99L)
        writer.add(listOf(item("vod-new-1", ContentKind.VOD)))
        writer.add(listOf(item("vod-new-2", ContentKind.VOD), item("series-new", ContentKind.SERIES)))
        writer.abort()
        val kept = VodDiskCache.read(dir, "src-a")
        assertNotNull(kept)
        assertEquals(1L, kept!!.savedAtMs)
        assertEquals(2, kept.items.count { it.kind == ContentKind.VOD })
        assertTrue(kept.items.any { it.id == "vod-1" })

        val ok = VodDiskCache.Writer(dir, "src-a", listOf(Category("vod-1", "Action", ContentKind.VOD)), nowMs = 7L)
        val many = (1..1200).map { item("vod-n-$it", ContentKind.VOD) }
        many.chunked(400).forEach { ok.add(it) }
        assertTrue(ok.commit())
        val catsOut = ArrayList<Category>()
        val itemsOut = ArrayList<MediaItem>()
        val meta = VodDiskCache.readStreaming(dir, "src-a", { catsOut.addAll(it) }, { itemsOut.addAll(it) })
        assertNotNull(meta)
        assertEquals(7L, meta!!.savedAtMs)
        assertEquals(1200, itemsOut.size)
        assertEquals("vod-n-1", itemsOut.first().id)
        assertEquals("vod-n-1200", itemsOut.last().id)
        assertEquals(1, catsOut.size)
    }

    @Test
    fun writerWithoutMoviesDoesNotReplace() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items, nowMs = 3L)
        val w = VodDiskCache.Writer(dir, "src-a", listOf(Category("series-1", "Drama", ContentKind.SERIES)), nowMs = 8L)
        w.add(listOf(item("series-only", ContentKind.SERIES)))
        assertFalse(w.commit())
        assertEquals(3L, VodDiskCache.read(dir, "src-a")!!.savedAtMs)
    }
}
