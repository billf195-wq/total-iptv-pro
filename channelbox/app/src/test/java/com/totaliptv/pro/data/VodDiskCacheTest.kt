package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test
import java.io.File
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

    private fun readKind(dir: File, kind: ContentKind, src: String = "src-a"): Pair<List<Category>, List<MediaItem>>? {
        val c = ArrayList<Category>()
        val i = ArrayList<MediaItem>()
        VodDiskCache.readKind(dir, src, kind, { c.addAll(it) }, { i.addAll(it) }) ?: return null
        return c to i
    }

    /** Exactly what 1.4.113 wrote: one kotlinx-encoded Snapshot with movies + series. */
    private fun writeLegacy113(dir: File, src: String = "src-a", snapItems: List<MediaItem> = items) {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        File(dir, VodDiskCache.FILE_NAME).writeText(
            json.encodeToString(
                VodDiskCache.Snapshot.serializer(),
                VodDiskCache.Snapshot(src, 5L, cats.filter { it.kind != ContentKind.LIVE }, snapItems.filter { it.kind != ContentKind.LIVE })
            )
        )
    }

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
        assertTrue(VodDiskCache.fileFor(dir, ContentKind.VOD).isFile)
        assertTrue(VodDiskCache.fileFor(dir, ContentKind.SERIES).isFile)
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

    /** 1.4.114 bug: saving a list with no series (series still downloading) wiped the saved series. */
    @Test
    fun saveWithoutSeriesNeverWipesSavedSeries() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items)
        VodDiskCache.write(dir, "src-a", cats, items.filter { it.kind != ContentKind.SERIES })
        val series = readKind(dir, ContentKind.SERIES)
        assertNotNull(series)
        assertEquals(listOf("series-1"), series!!.second.map { it.id })
        assertEquals(listOf("series-1"), series.first.map { it.id })
    }

    @Test
    fun corruptFileReadsAsNull() {
        val dir = Files.createTempDirectory("vod").toFile()
        File(dir, VodDiskCache.FILE_NAME).writeText("{not json")
        VodDiskCache.fileFor(dir, ContentKind.SERIES).writeText("{not json")
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
        val writer = VodDiskCache.Writer(dir, "src-a", ContentKind.VOD, cats, nowMs = 99L)
        writer.add(listOf(item("vod-new-1", ContentKind.VOD)))
        writer.abort()
        val kept = VodDiskCache.read(dir, "src-a")!!
        assertEquals(1L, kept.savedAtMs)
        assertEquals(2, kept.items.count { it.kind == ContentKind.VOD })

        val ok = VodDiskCache.Writer(dir, "src-a", ContentKind.VOD, listOf(Category("vod-1", "Action", ContentKind.VOD)), nowMs = 7L)
        val many = (1..1200).map { item("vod-n-$it", ContentKind.VOD) }
        many.chunked(400).forEach { ok.add(it) }
        assertTrue(ok.commit())
        val (c, i) = readKind(dir, ContentKind.VOD)!!
        assertEquals(1200, i.size)
        assertEquals("vod-n-1", i.first().id)
        assertEquals("vod-n-1200", i.last().id)
        assertEquals(1, c.size)
        // Series untouched by the movie commit.
        assertEquals(listOf("series-1"), readKind(dir, ContentKind.SERIES)!!.second.map { it.id })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    /** 1.4.114 bug: a series-only commit was refused unless the file also held a movie. */
    @Test
    fun seriesCommitNeedsNoMovies() {
        val dir = Files.createTempDirectory("vod").toFile()
        val w = VodDiskCache.Writer(dir, "src-a", ContentKind.SERIES, cats, nowMs = 8L)
        w.add(listOf(item("series-only", ContentKind.SERIES), item("vod-ignored", ContentKind.VOD)))
        assertTrue(w.commit())
        val (c, i) = readKind(dir, ContentKind.SERIES)!!
        assertEquals(listOf("series-only"), i.map { it.id })
        assertEquals(listOf("series-1"), c.map { it.id })
        assertNull(readKind(dir, ContentKind.VOD))
    }

    @Test
    fun emptySeriesWriterKeepsLastGoodSeries() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items, nowMs = 3L)
        val w = VodDiskCache.Writer(dir, "src-a", ContentKind.SERIES, cats, nowMs = 8L)
        w.add(listOf(item("vod-x", ContentKind.VOD)))
        assertFalse(w.commit())
        assertEquals(listOf("series-1"), readKind(dir, ContentKind.SERIES)!!.second.map { it.id })
    }

    /** 1.4.114 bug: two loads (refresh during a download) shared one temp file. */
    @Test
    fun overlappingWritersDoNotClobberEachOther() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items)
        val stale = VodDiskCache.Writer(dir, "src-a", ContentKind.SERIES, cats)
        stale.add(listOf(item("series-stale", ContentKind.SERIES)))
        val fresh = VodDiskCache.Writer(dir, "src-a", ContentKind.SERIES, cats)
        val movies = VodDiskCache.Writer(dir, "src-a", ContentKind.VOD, cats)
        fresh.add(listOf(item("series-fresh-1", ContentKind.SERIES), item("series-fresh-2", ContentKind.SERIES)))
        movies.add(listOf(item("vod-fresh", ContentKind.VOD)))
        stale.abort()
        assertTrue(fresh.commit())
        assertTrue(movies.commit())
        assertEquals(listOf("series-fresh-1", "series-fresh-2"), readKind(dir, ContentKind.SERIES)!!.second.map { it.id })
        assertEquals(listOf("vod-fresh"), readKind(dir, ContentKind.VOD)!!.second.map { it.id })
    }

    @Test
    fun legacy113CacheIsReadPerKindWithoutMigration() {
        val dir = Files.createTempDirectory("vod").toFile()
        writeLegacy113(dir)
        val (sc, si) = readKind(dir, ContentKind.SERIES)!!
        assertEquals(listOf("series-1"), si.map { it.id })
        assertEquals(listOf("series-1"), sc.map { it.id })
        assertEquals(listOf("vod-1", "vod-2"), readKind(dir, ContentKind.VOD)!!.second.map { it.id })
        assertNull(readKind(dir, ContentKind.SERIES, src = "src-b"))
    }

    @Test
    fun legacy113CacheMigratesToPerKindFiles() {
        val dir = Files.createTempDirectory("vod").toFile()
        writeLegacy113(dir)
        File(dir, "${VodDiskCache.FILE_NAME}.tmp").writeText("junk")
        assertTrue(VodDiskCache.migrateLegacy(dir))
        assertFalse(File(dir, VodDiskCache.FILE_NAME).exists())
        assertFalse(File(dir, "${VodDiskCache.FILE_NAME}.tmp").exists())
        assertEquals(listOf("series-1"), readKind(dir, ContentKind.SERIES)!!.second.map { it.id })
        assertEquals(listOf("series-1"), readKind(dir, ContentKind.SERIES)!!.first.map { it.id })
        assertEquals(listOf("vod-1", "vod-2"), readKind(dir, ContentKind.VOD)!!.second.map { it.id })
        // Running again is a no-op.
        assertFalse(VodDiskCache.migrateLegacy(dir))
        assertEquals(1, readKind(dir, ContentKind.SERIES)!!.second.size)
    }

    @Test
    fun migrationNeverOverwritesNewerPerKindFile() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.writeKind(dir, "src-a", ContentKind.SERIES, cats, listOf(item("series-new", ContentKind.SERIES)))
        writeLegacy113(dir)
        VodDiskCache.migrateLegacy(dir)
        assertEquals(listOf("series-new"), readKind(dir, ContentKind.SERIES)!!.second.map { it.id })
        assertEquals(listOf("vod-1", "vod-2"), readKind(dir, ContentKind.VOD)!!.second.map { it.id })
    }

    @Test
    fun corruptLegacyIsKeptNotDeleted() {
        val dir = Files.createTempDirectory("vod").toFile()
        File(dir, VodDiskCache.FILE_NAME).writeText("{not json")
        assertFalse(VodDiskCache.migrateLegacy(dir))
        assertTrue(File(dir, VodDiskCache.FILE_NAME).exists())
    }

    @Test
    fun clearRemovesEverything() {
        val dir = Files.createTempDirectory("vod").toFile()
        VodDiskCache.write(dir, "src-a", cats, items)
        writeLegacy113(dir)
        VodDiskCache.clear(dir)
        assertNull(VodDiskCache.read(dir, "src-a"))
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
