package com.totaliptv.pro.ui.player

import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameDayChannelsTest {
    @Test
    fun eachSideKeepsItsOwnChannel() {
        val left = channel("l", "Left Game", "sports")
        val right = channel("r", "Right Game", "news")
        val other = channel("o", "Other", "sports")
        val afterLeft = GameDayChannels.assign(null, null, "LEFT", left)
        assertEquals(left, afterLeft.first)
        assertEquals(null, afterLeft.second)
        val afterRight = GameDayChannels.assign(afterLeft.first, afterLeft.second, "RIGHT", right)
        assertEquals("l", afterRight.first?.id)
        assertEquals("r", afterRight.second?.id)
        val replaced = GameDayChannels.assign(afterRight.first, afterRight.second, "LEFT", other)
        assertEquals("o", replaced.first?.id)
        assertEquals("r", replaced.second?.id)
        assertTrue(GameDayChannels.canStart(replaced.first, replaced.second))
        assertFalse(GameDayChannels.canStart(replaced.first, null))
    }

    @Test
    fun filterByCategoryFavoritesAndSearch() {
        val sports = channel("a", "Team A", "sports", categoryId = "live-sports")
        val news = channel("b", "News One", "news", categoryId = "live-news")
        val fav = channel("c", "Favorite Game", "sports", categoryId = "live-sports")
        val all = listOf(sports, news, fav)
        assertEquals(listOf("a", "c"), GameDayChannels.filter(all, setOf("c"), "live-sports", "").map { it.id })
        assertEquals(listOf("c"), GameDayChannels.filter(all, setOf("c"), GameDayChannels.FAVORITES, "").map { it.id })
        assertEquals(listOf("b"), GameDayChannels.filter(all, emptySet(), null, "news").map { it.id })
        assertTrue(GameDayChannels.filter(all, emptySet(), GameDayChannels.FAVORITES, "").isEmpty())
    }

    @Test
    fun setupAndRunningSplitCanPickEachScreen() {
        val picker = File("src/main/java/com/totaliptv/pro/ui/player/GameDay.kt").readText()
        assertTrue(picker.contains("Choose a channel for the left screen"))
        assertTrue(picker.contains("title = \"LEFT\""))
        assertTrue(picker.contains("title = \"RIGHT\""))
        assertTrue(picker.contains("label = \"Start\""))
        assertTrue(picker.contains("Favorites"))
        assertTrue(picker.contains("Search"))
        assertTrue(picker.contains("onFocusChanged"))
        val split = File("src/main/java/com/totaliptv/pro/ui/player/SplitPlayerActivity.kt").readText()
        val back = split.substringAfter("if (pickingSide != null)")
            .substringBefore("when (event.keyCode)")
        assertTrue(back.contains("closePicker()"))
        assertFalse(back.contains("stopSplit()"))
        val theme = File("src/main/java/com/totaliptv/pro/ui/theme/TipColors.kt").readText()
        assertTrue(theme.contains("Color(0xFF000000)"))
        assertTrue(theme.contains("SolidColor"))
    }

    private fun channel(
        id: String,
        name: String,
        group: String,
        categoryId: String? = null
    ) = MediaItem(
        id = id,
        name = name,
        streamUrl = "http://example/live/u/p/$id.ts",
        categoryId = categoryId,
        kind = ContentKind.LIVE,
        groupTitle = group,
        categoryIds = listOfNotNull(categoryId)
    )
}
