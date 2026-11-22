package com.totaliptv.pro.data.lock

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AdultSearchFilterTest {
    private val adultCat = Category("live-xxx", "XXX Live", ContentKind.LIVE)
    private val newsCat = Category("live-news", "News", ContentKind.LIVE)
    private val movies = Category("vod-1", "Movies", ContentKind.VOD)
    private val byId = listOf(adultCat, newsCat, movies).associateBy { it.id }

    private fun item(
        id: String,
        name: String,
        kind: ContentKind = ContentKind.VOD,
        categoryId: String? = "vod-1",
        categoryIds: List<String> = emptyList()
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example.test/$id",
        categoryId = categoryId,
        kind = kind,
        categoryIds = categoryIds
    )

    @Test
    fun titleKeywordsHideAdultButKeepCommonTitles() {
        assertTrue(AdultSearchFilter.isAdultTitle("Porn Stream"))
        assertTrue(AdultSearchFilter.isAdultTitle("Erotic Thriller"))
        assertTrue(AdultSearchFilter.isAdultTitle("Movies 18+"))
        assertTrue(AdultSearchFilter.isAdultTitle("For Adults Only"))
        assertTrue(AdultSearchFilter.isAdultTitle("Adult Cinema"))
        assertFalse(AdultSearchFilter.isAdultTitle("xXx"))
        assertFalse(AdultSearchFilter.isAdultTitle("xXx: Return of Xander Cage"))
        assertFalse(AdultSearchFilter.isAdultTitle("Young Adult"))
        assertFalse(AdultSearchFilter.isAdultTitle("Adult Swim"))
        assertFalse(AdultSearchFilter.isAdultTitle("Adultery"))
        assertFalse(AdultSearchFilter.isAdultTitle("Friends"))
        assertFalse(AdultSearchFilter.isAdultTitle("The Irishman"))
        assertFalse(AdultSearchFilter.isAdultTitle("Stranger Things"))
        assertFalse(AdultSearchFilter.isAdultTitle("BBC News"))
        assertFalse(AdultSearchFilter.isAdultTitle("CNN"))
        assertFalse(AdultSearchFilter.isAdultTitle(null))
    }

    @Test
    fun adultCategoryHidesEvenWithACleanTitle() {
        val newsSpecial = item("ch-1", "News Special", ContentKind.LIVE, "live-xxx")
        assertTrue(AdultSearchFilter.shouldHide(newsSpecial, byId))
        val cnn = item("ch-2", "CNN", ContentKind.LIVE, "live-news")
        assertFalse(AdultSearchFilter.shouldHide(cnn, byId))
    }

    @Test
    fun extraCategoryIdsAreChecked() {
        val viaExtra = item("m-1", "A Feature", ContentKind.VOD, "vod-1", listOf("live-xxx"))
        assertTrue(AdultSearchFilter.shouldHide(viaExtra, byId))
    }

    @Test
    fun showAdultFlagLeavesEverythingVisible() {
        val adult = item("a", "Porn Stream", ContentKind.VOD, "vod-1")
        val normal = item("b", "Friends", ContentKind.SERIES, "vod-1")
        val hidden = AdultSearchFilter.filter(listOf(adult, normal), byId, showAdultInSearch = false)
        assertEquals(listOf("Friends"), hidden.map { it.name })
        val shown = AdultSearchFilter.filter(listOf(adult, normal), byId, showAdultInSearch = true)
        assertEquals(listOf("Porn Stream", "Friends"), shown.map { it.name })
    }
}
