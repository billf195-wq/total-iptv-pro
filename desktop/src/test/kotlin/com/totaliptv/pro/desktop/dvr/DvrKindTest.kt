package com.totaliptv.pro.desktop.dvr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DvrKindTest {

    @Test
    fun normalizeMapsAliases() {
        assertEquals(DvrKind.VOD, DvrKind.normalize("movie"))
        assertEquals(DvrKind.VOD, DvrKind.normalize("MOVIES"))
        assertEquals(DvrKind.SERIES, DvrKind.normalize("episode"))
        assertEquals(DvrKind.SERIES, DvrKind.normalize("SERIES"))
        assertEquals(DvrKind.LIVE, DvrKind.normalize(null))
        assertEquals(DvrKind.LIVE, DvrKind.normalize("other"))
    }

    @Test
    fun labelsMatchLibraryTypes() {
        assertEquals("Live", DvrKind.label("LIVE"))
        assertEquals("Movie", DvrKind.label("VOD"))
        assertEquals("Series", DvrKind.label("EPISODE"))
    }

    @Test
    fun finiteDownloadForMovieAndSeriesUrls() {
        assertFalse(DvrKind.isFiniteDownload(DvrKind.LIVE, "http://host/live/u/p/1.m3u8"))
        assertFalse(DvrKind.isFiniteDownload(DvrKind.VOD, "http://host/live/u/p/1.m3u8"))
        assertTrue(DvrKind.isFiniteDownload(DvrKind.VOD, "http://host/movie/u/p/99.mp4"))
        assertTrue(DvrKind.isFiniteDownload(DvrKind.SERIES, "http://host/series/u/p/12.mp4"))
        assertTrue(DvrKind.isFiniteDownload(DvrKind.VOD, "http://cdn/film.mkv"))
    }

    @Test
    fun extensionFollowsUrlThenKind() {
        assertEquals("mp4", DvrKind.extensionForUrl("http://host/movie/u/p/9.mp4", DvrKind.VOD))
        assertEquals("mkv", DvrKind.extensionForUrl("http://host/series/u/p/9.mkv", DvrKind.SERIES))
        assertEquals("ts", DvrKind.extensionForUrl("http://host/live/u/p/1.m3u8", DvrKind.LIVE))
        assertEquals("mp4", DvrKind.extensionForUrl("http://host/movie/u/p/9", DvrKind.VOD))
    }
}
