package com.totaliptv.pro.ui.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedLoginSourceTest {
    private fun text(path: String) = java.io.File(path).readText()

    @Test
    fun loginFormStaysFilledUntilForgotten() {
        val form = text("src/main/java/com/totaliptv/pro/ui/onboarding/OnboardingScreen.kt")
        assertTrue(form.contains("savedLogins.load()"))
        assertTrue(form.contains("savedLogins.save(b, u, null)"))
        assertTrue(form.contains("savedLogins.save(b, u, p)"))
        assertTrue(form.contains("Forget saved sign-in"))
        assertTrue(form.contains("savedLogins.clear()"))
        // Public builds start blank: no server, username or password in source.
        assertFalse(form.contains("base.setText(\"http"))
    }

    @Test
    fun passwordIsEncryptedAndNeverLogged() {
        val store = text("src/main/java/com/totaliptv/pro/data/local/SavedLoginStore.kt")
        assertTrue(store.contains("AndroidKeyStore"))
        assertTrue(store.contains("AES/GCM/NoPadding"))
        assertFalse(store.contains("Log."))
        assertTrue(store.contains("<hidden>"))
    }

    @Test
    fun searchDownLandsOnFirstResult() {
        val panes = text("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt")
        assertTrue(panes.contains("onDownToResults = { focusFirstPoster() }"))
        assertTrue(panes.contains("onDownToResults = { focusFirstRow() }"))
        assertFalse(panes.contains(".focusProperties { canFocus = SearchTyping.resultsMayTakeFocus(editingSearch) }"))
        val search = text("src/tv/java/com/totaliptv/pro/ui/search/SearchScreen.kt")
        assertTrue(search.contains("onDownToResults = { goToResults() }"))
        assertFalse(search.contains("down = firstResultFocus"))
    }
}
