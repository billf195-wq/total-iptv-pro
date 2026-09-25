package com.totaliptv.pro.desktop

import com.totaliptv.pro.desktop.data.ContentKind
import com.totaliptv.pro.desktop.data.EpgProgram
import com.totaliptv.pro.desktop.data.EpgUserOffset
import com.totaliptv.pro.desktop.data.M3uKind
import com.totaliptv.pro.desktop.data.M3uParser
import com.totaliptv.pro.desktop.data.MediaItem
import com.totaliptv.pro.desktop.data.ResumeStore
import com.totaliptv.pro.desktop.data.ServerInfoMemory
import com.totaliptv.pro.desktop.player.FfmpegLocator
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.update.UpdateSources
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Merge924Test {

    @Test
    fun startTimeAppliesOnlyToASingleResumedUrl() {
        val one = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://example.test/ep1.mp4"),
            windows = false,
            startPositionSeconds = 90
        )
        assertTrue(one.contains("--start-time=90"))
        assertEquals("http://example.test/ep1.mp4", one.last())

        val two = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://example.test/ep1.mp4", "http://example.test/ep2.mp4"),
            windows = false,
            startPositionSeconds = 90
        )
        assertFalse(two.any { it.startsWith("--start-time") })
        assertEquals("http://example.test/ep1.mp4", two.last())
        assertFalse(two.contains("http://example.test/ep2.mp4"))

        val mpv = StreamPlayer.mpvCommand(
            "mpv",
            listOf("http://example.test/a.mp4", "http://example.test/b.mp4"),
            windows = false,
            startPositionSeconds = 40
        )
        assertFalse(mpv.any { it.startsWith("--start=") })
    }

    @Test
    fun resumeProgressIsKeyedPerEpisode() {
        val ep1 = episode(id = "ep-101", streamId = 101)
        val ep2 = episode(id = "ep-202", streamId = 202)
        val key1 = ResumeStore.progressKeyFor(ep1)
        val key2 = ResumeStore.progressKeyFor(ep2)
        assertEquals("series-9-ep-101", key1)
        assertEquals("series-9-ep-202", key2)
        assertTrue(key1 != key2)

        val summary = ResumeStore.ResumeEntry(
            key = "series-9",
            catalogId = "series-9",
            name = "Show",
            kind = ContentKind.SERIES.name,
            seriesId = 9,
            episodeId = "101",
            positionMs = 50_000,
            durationMs = 100_000,
            playbackPercent = 50
        )
        val row1 = summary.copy(key = "series-9-ep-101", catalogId = "series-9-ep-101", playbackPercent = 20, positionMs = 20_000)
        val row2 = summary.copy(
            key = "series-9-ep-202",
            catalogId = "series-9-ep-202",
            episodeId = "202",
            playbackPercent = 80,
            positionMs = 80_000
        )
        val entries = listOf(summary, row1, row2)
        assertEquals("series-9", ResumeStore.forSeries(9, entries)?.key)
        assertEquals(20, ResumeStore.progressForEpisode(9, "101", entries)?.playbackPercent)
        assertEquals(80, ResumeStore.progressForEpisode(9, "202", entries)?.playbackPercent)
        assertNull(ResumeStore.progressKeyFor(ep1.copy(kind = ContentKind.LIVE, playable = true, parentSeriesId = null)))
    }

    @Test
    fun liveSportsNamesStayLive() {
        assertFalse(M3uKind.isSeries("http://example.test/live/1.ts", "Sports"))
        assertFalse(M3uKind.isSeries("http://example.test/live/world.ts", "USA", null))
        val text = """
            #EXTM3U url-tvg="http://example.test/guide.xml"
            #EXTINF:-1 tvg-id="fox" group-title="Sports",World Series
            http://example.test/live/world.ts
            #EXTINF:-1 tvg-id="nbc" group-title="NASCAR Cup Series",Cup Race
            http://example.test/live/nascar.ts
            #EXTINF:-1 group-title="Series",Show One
            http://example.test/series/1/2.mp4
            #EXTINF:-1 type="series" group-title="Drama",Other
            http://example.test/vod/other.mp4
        """.trimIndent()
        val catalog = M3uParser.parse(text)
        assertEquals("http://example.test/guide.xml", catalog.xmltvUrl)
        val byName = catalog.liveItems.associateBy { it.name } + catalog.seriesItems.associateBy { it.name }
        assertEquals(ContentKind.LIVE, byName.getValue("World Series").kind)
        assertEquals(ContentKind.LIVE, byName.getValue("Cup Race").kind)
        assertEquals(ContentKind.SERIES, byName.getValue("Show One").kind)
        assertEquals(ContentKind.SERIES, byName.getValue("Other").kind)
    }

    @Test
    fun manualGuideOffsetIsAUserOverride() {
        val program = EpgProgram(id = "1", title = "News", startMs = 1_000, endMs = 2_000, channelStreamId = 4)
        assertEquals(listOf(program), EpgUserOffset.apply(listOf(program), 0))
        val shifted = EpgUserOffset.apply(listOf(program), 1).single()
        assertEquals(1_000 + 3_600_000L, shifted.startMs)
        assertEquals(2_000 + 3_600_000L, shifted.endMs)
    }

    @Test
    fun serverInfoIsCachedEvenWhenTheZoneIsMissing() {
        val memory = ServerInfoMemory()
        memory.remember(null)
        assertTrue(memory.cached)
        assertNull(memory.zone)
        memory.remember(ZoneId.of("America/Cayman"))
        assertNull(memory.zone)
    }

    @Test
    fun updaterIgnoresLanShelvesAndUsesTheRealWindowsFolder() {
        assertEquals(
            UpdateSources.GITHUB_RELEASES_PAGE,
            UpdateSources.shelfForCheck("http://192.168.4.39:8767/")
        )
        assertTrue(UpdateSources.usesGithubReleases(UpdateSources.GITHUB_RELEASES_PAGE))
        val root = UpdateSources.windowsInstallRoot("/home/me/AppData/Local")
        assertTrue(root.toString().replace('\\', '/').endsWith("TotalIptvPro"))
        assertFalse(root.toString().contains("total-iptv-pro"))
        assertTrue(UpdateSources.isNewerName("1.2.21", "1.2.15"))
        assertFalse(UpdateSources.isNewerName("1.2.15", "1.2.21"))
    }

    @Test
    fun ffmpegFolderWalkIsNotAllowedOnTheUiThread() {
        assertFalse(FfmpegLocator.mayWalkFilesystem(onUiThread = true))
        assertTrue(FfmpegLocator.mayWalkFilesystem(onUiThread = false))
    }

    private fun episode(id: String, streamId: Int) = MediaItem(
        id = id,
        name = id,
        streamUrl = "http://example.test/$id.mp4",
        categoryId = null,
        kind = ContentKind.SERIES,
        playable = true,
        parentSeriesId = 9,
        xtreamStreamId = streamId
    )
}
