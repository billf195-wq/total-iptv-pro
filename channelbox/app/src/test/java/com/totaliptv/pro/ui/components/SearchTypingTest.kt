package com.totaliptv.pro.ui.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchTypingTest {
    @Test
    fun resultsStayUnfocusableWhileTheFieldIsEditing() {
        assertFalse(SearchTyping.resultsMayTakeFocus(editing = true))
        assertTrue(SearchTyping.resultsMayTakeFocus(editing = false))
    }

    @Test
    fun onlyTheOkThatOpenedTheKeyboardInsertsAStrayQ() {
        val opened = 1_000L
        assertTrue(SearchTyping.isStrayOkCharacter("", "q", opened, opened + 50L))
        assertTrue(SearchTyping.isStrayOkCharacter("ab", "abq", opened, opened + 100L))
        assertFalse(SearchTyping.isStrayOkCharacter("ab", "abc", opened, opened + 50L))
        assertFalse(SearchTyping.isStrayOkCharacter("", "q", opened, opened + SearchTyping.STRAY_OK_WINDOW_MS + 1L))
        assertFalse(SearchTyping.isStrayOkCharacter("", "qq", opened, opened + 10L))
    }
}
