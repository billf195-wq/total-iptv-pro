package com.totaliptv.pro.ui.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpadSearchFocusTest {
    @Test
    fun searchDoesNotOpenTheKeyboardUntilOkAndGameDayIsAboveTheList() {
        val field = java.io.File("src/main/java/com/totaliptv/pro/ui/components/DpadSearchField.kt").readText()
        val idle = field.substringAfter("if (!editing) {").substringBefore("BackHandler")
        assertTrue(idle.contains("Key.DirectionCenter"))
        assertFalse(idle.contains("BasicTextField"))
        assertTrue(idle.contains("else -> false"))
        assertTrue(field.contains("Key.DirectionDown, Key.Back, Key.Escape"))
        assertTrue(field.contains("onDone"))
        assertTrue(field.contains("keyboard?.hide()"))
        assertTrue(field.contains("ImeAction.Done"))
        assertFalse(field.contains("LaunchedEffect(Unit)"))

        val panes = java.io.File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").readText()
        val live = panes.substringAfter("fun LivePane").substringBefore("fun sortDesktopRecentlyAdded")
        val filterAt = live.indexOf("FilterBar(")
        val gameDayAt = live.indexOf("label = \"Game Day\"")
        val listAt = live.indexOf("LazyColumn(")
        assertTrue(filterAt in 0 until gameDayAt)
        assertTrue(gameDayAt < listAt)
        assertTrue(live.contains("up = gameDayFocus"))
        assertTrue(live.contains("belowFocus = gameDayFocus"))
        val filter = panes.substringAfter("fun FilterBar")
        assertTrue(filter.contains("DpadSearchField("))
        assertFalse(filter.contains("BasicTextField("))

        val search = java.io.File("src/tv/java/com/totaliptv/pro/ui/search/SearchScreen.kt").readText()
        assertTrue(search.contains("DpadSearchField("))
        assertTrue(search.contains("closeFocus.requestFocus()"))
        assertFalse(search.contains("BasicTextField("))
        assertFalse(search.contains("fieldFocus.requestFocus"))

        val gameDay = java.io.File("src/main/java/com/totaliptv/pro/ui/player/GameDay.kt").readText()
        assertTrue(gameDay.contains("isTelevisionUi()"))
        assertTrue(gameDay.contains("DpadSearchField("))
        assertTrue(gameDay.contains("OutlinedTextField("))
    }
}
