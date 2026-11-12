package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamingServicesTest {
    private fun cat(name: String) = StreamingServices.matchCategoryName(name)
    private fun title(name: String) = StreamingServices.matchTitle(name)

    @Test
    fun netflixCategoriesAndTitles() {
        assertEquals(setOf(StreamingService.NETFLIX), cat("NETFLIX SERIES"))
        assertEquals(setOf(StreamingService.NETFLIX), cat("EN | Netflix Movies"))
        assertEquals(setOf(StreamingService.NETFLIX), cat("NFLX: Originals"))
        assertEquals(setOf(StreamingService.NETFLIX), title("Wednesday (Netflix)"))
        assertTrue(cat("Netflixing and Chill").isEmpty())
        assertTrue(title("Stranger Things").isEmpty())
    }

    @Test
    fun primeCategoriesAndTitles() {
        assertEquals(setOf(StreamingService.PRIME), cat("Amazon Prime Movies"))
        assertEquals(setOf(StreamingService.PRIME), cat("AMAZON SERIES"))
        assertEquals(setOf(StreamingService.PRIME), cat("Prime Video"))
        assertEquals(setOf(StreamingService.PRIME), cat("PRIME | Series"))
        assertEquals(setOf(StreamingService.PRIME), cat("EN - PRIME"))
        assertEquals(setOf(StreamingService.PRIME), cat("[PRIME] 4K"))
        assertEquals(setOf(StreamingService.PRIME), cat("Prime Movies"))
        assertEquals(setOf(StreamingService.PRIME), cat("AMZN VOD"))
        assertEquals(setOf(StreamingService.PRIME), title("The Boys - Amazon Prime"))
        assertEquals(setOf(StreamingService.PRIME), title("Reacher [Prime Video]"))
    }

    @Test
    fun primeFalsePositivesAreIgnored() {
        assertTrue(title("Prime Suspect").isEmpty())
        assertTrue(title("Optimus Prime Returns").isEmpty())
        assertTrue(title("Amazon Women on the Moon").isEmpty())
        assertTrue(title("The Amazing Spider-Man").isEmpty())
        assertTrue(title("Primer").isEmpty())
        assertTrue(cat("Prime Suspect").isEmpty())
        assertTrue(cat("Prime Time Movies").isEmpty())
        assertTrue(cat("Optimus Prime Collection").isEmpty())
        assertTrue(cat("Primetime Series").isEmpty())
        assertTrue(cat("Amazing Movies").isEmpty())
        assertTrue(cat("Prime").isNotEmpty(), "bare standalone PRIME group still counts")
    }

    @Test
    fun hboCategoriesAndTitles() {
        assertEquals(setOf(StreamingService.HBO), cat("HBO Series"))
        assertEquals(setOf(StreamingService.HBO), cat("HBO MAX Movies"))
        assertEquals(setOf(StreamingService.HBO), cat("HBOMAX"))
        assertEquals(setOf(StreamingService.HBO), cat("Max Originals"))
        assertEquals(setOf(StreamingService.HBO), title("The Last of Us (HBO)"))
        assertTrue(title("Mad Max: Fury Road").isEmpty())
        assertTrue(title("Max Payne").isEmpty())
        assertTrue(cat("Maximum Action").isEmpty())
        assertTrue(cat("Hbox Kids").isEmpty())
    }

    @Test
    fun appleCategoriesAndTitles() {
        assertEquals(setOf(StreamingService.APPLE), cat("Apple TV+ Series"))
        assertEquals(setOf(StreamingService.APPLE), cat("APPLE TV Movies"))
        assertEquals(setOf(StreamingService.APPLE), cat("AppleTV+"))
        assertEquals(setOf(StreamingService.APPLE), cat("Apple+ Originals"))
        assertEquals(setOf(StreamingService.APPLE), cat("ATV+ | 4K"))
        assertEquals(setOf(StreamingService.APPLE), title("Severance - Apple TV+"))
        assertTrue(title("The Apple Dumpling Gang").isEmpty())
        assertTrue(cat("ATV Racing").isEmpty())
        assertTrue(title("Pineapple Express").isEmpty())
        assertTrue(cat("Apple Valley Sports").isEmpty())
    }

    @Test
    fun plainGroupsMatchNothing() {
        listOf("English Series", "Action Movies", "4K UHD", "Kids", "Uncategorized", "Documentaries")
            .forEach { assertTrue(cat(it).isEmpty(), it) }
    }

    @Test
    fun categoryIdsRoundTrip() {
        for (svc in StreamingService.entries) {
            for (kind in listOf(ContentKind.VOD, ContentKind.SERIES)) {
                val id = StreamingServices.categoryId(svc, kind)
                assertTrue(StreamingServices.isServiceCategoryId(id))
                assertEquals(svc to kind, StreamingServices.parseCategoryId(id))
            }
        }
        assertNull(StreamingServices.parseCategoryId("vod-12"))
        assertNull(StreamingServices.parseCategoryId("vod-newly-added"))
        assertNull(StreamingServices.parseCategoryId("vod-svc-unknown"))
        assertFalse(StreamingServices.isServiceCategoryId("series-42"))
    }

    private fun item(id: String, name: String, kind: ContentKind, cat: String?, group: String? = null) =
        MediaItem(id = id, name = name, streamUrl = "u", categoryId = cat, kind = kind, groupTitle = group)

    @Test
    fun groupsByCategoryOrTitleAndHidesEmptyServices() {
        val names = mapOf("vod-1" to "NETFLIX MOVIES", "vod-2" to "Action", "series-3" to "HBO Series")
        val items = listOf(
            item("vod-a", "Glass Onion", ContentKind.VOD, "vod-1"),
            item("vod-b", "Heat", ContentKind.VOD, "vod-2"),
            item("vod-c", "Air (Amazon Prime)", ContentKind.VOD, "vod-2"),
            item("series-d", "Succession", ContentKind.SERIES, "series-3"),
            item("live-e", "HBO East", ContentKind.LIVE, "live-9", group = "HBO"),
            item("vod-a", "Glass Onion", ContentKind.VOD, "vod-1")
        )
        val grouped = StreamingServices.group(items) { names[it.categoryId] }
        assertEquals(listOf("vod-a"), grouped[StreamingService.NETFLIX to ContentKind.VOD]?.map { it.id })
        assertEquals(listOf("vod-c"), grouped[StreamingService.PRIME to ContentKind.VOD]?.map { it.id })
        assertEquals(listOf("series-d"), grouped[StreamingService.HBO to ContentKind.SERIES]?.map { it.id })
        assertNull(grouped[StreamingService.HBO to ContentKind.LIVE], "live channels are never grouped")

        val vodCats = StreamingServices.categoriesFor(ContentKind.VOD, grouped)
        assertEquals(listOf("Netflix", "Prime Video"), vodCats.map { it.name })
        val seriesCats = StreamingServices.categoriesFor(ContentKind.SERIES, grouped)
        assertEquals(listOf("HBO / Max"), seriesCats.map { it.name })
        assertTrue(StreamingServices.categoriesFor(ContentKind.VOD, emptyMap()).isEmpty())
    }

    @Test
    fun groupTitleIsUsedWhenNoCategoryName() {
        val items = listOf(item("m1", "Foo", ContentKind.VOD, "m3u-vod-1", group = "Apple TV+ Movies"))
        val grouped = StreamingServices.group(items) { null }
        assertEquals(1, grouped[StreamingService.APPLE to ContentKind.VOD]?.size)
    }
}
