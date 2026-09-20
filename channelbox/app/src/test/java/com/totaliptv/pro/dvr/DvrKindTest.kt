package com.totaliptv.pro.dvr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DvrKindTest {
    @Test
    fun normalizeMapsAliases() {
        assertEquals(DvrKind.VOD, DvrKind.normalize("movie"))
        assertEquals(DvrKind.SERIES, DvrKind.normalize("episode"))
        assertEquals(DvrKind.LIVE, DvrKind.normalize(null))
    }

    @Test
    fun labelsMatchLibraryTypes() {
        assertEquals("Live", DvrKind.label("LIVE"))
        assertEquals("Movie", DvrKind.label("VOD"))
        assertEquals("Series", DvrKind.label("SERIES"))
    }

    @Test
    fun finiteDownloadForMovieAndSeriesUrls() {
        assertFalse(DvrKind.isFiniteDownload(DvrKind.LIVE, "http://host/live/u/p/1.m3u8"))
        assertTrue(DvrKind.isFiniteDownload(DvrKind.VOD, "http://host/movie/u/p/99.mp4"))
        assertTrue(DvrKind.isFiniteDownload(DvrKind.SERIES, "http://host/series/u/p/12.mp4"))
    }

    @Test
    fun extensionFollowsUrlThenKind() {
        assertEquals("mp4", DvrKind.extensionForUrl("http://host/movie/u/p/9.mp4", DvrKind.VOD))
        assertEquals("ts", DvrKind.extensionForUrl("http://host/live/u/p/1.m3u8", DvrKind.LIVE))
        assertEquals("mp4", DvrKind.extensionForUrl("http://host/movie/u/p/9", DvrKind.VOD))
    }
}
