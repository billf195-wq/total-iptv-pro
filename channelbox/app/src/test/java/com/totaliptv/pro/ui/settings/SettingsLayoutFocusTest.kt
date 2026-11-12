package com.totaliptv.pro.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsLayoutFocusTest {
    @Test
    fun classicDesktopCardsAreReachableByDpadAndTap() {
        val settings = File("src/tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").readText()
        val layout = settings.substringAfter("SectionTitle(\"App layout\")")
            .substringBefore("SectionTitle(\"Theme\")")

        // Full-width stacked items, not a side-by-side Row that D-pad focus search skipped.
        assertFalse(layout.contains("Modifier.weight(1f)"))
        assertFalse(layout.contains("Row("))
        assertTrue(layout.contains("item(key = \"layout-\${mode.storageValue}\")"))
        assertTrue(layout.contains("Modifier.fillMaxWidth()"))
        assertTrue(layout.contains("setAppLayoutMode(mode)"))

        val choice = settings.substringAfter("private fun ThemeChoiceCard(")
            .substringBefore("private fun ThemeAccentCard(")
        assertTrue(choice.contains("onFocusChanged"))
        assertTrue(choice.contains("pointerInput"))
        assertTrue(choice.contains("detectTapGestures"))

        val accent = settings.substringAfter("private fun ThemeAccentCard(")
            .substringBefore("fun PrivacyPolicyScreen")
        assertTrue(accent.contains("onFocusChanged"))
        assertTrue(accent.contains("pointerInput"))
        assertTrue(accent.contains("detectTapGestures"))
    }

    @Test
    fun posterStarsUseMergedTmdbRating() {
        val main = File("src/main/java/com/totaliptv/pro/ui/MergedRating.kt").readText()
        assertTrue(main.contains("fun mergedDisplayRating"))
        assertTrue(main.contains("displayRatingOf"))

        val repo = File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(repo.contains("fun displayRatingOf"))
        assertTrue(repo.contains("overwriteProvider = tmdbOnly"))

        val phone = File("src/phone/java/com/totaliptv/pro/ui/components/PhoneComponents.kt").readText()
        assertTrue(phone.contains("mergedDisplayRating(item)"))
        assertFalse(phone.contains("item.displayRating()"))

        val desktop = File("src/tv/java/com/totaliptv/pro/ui/desktop/DesktopComponents.kt").readText()
        assertTrue(desktop.contains("mergedDisplayRating(item)"))

        val classic = File("src/tv/java/com/totaliptv/pro/ui/home/HomeScreen.kt").readText()
        assertTrue(classic.contains("mergedDisplayRating("))
        assertFalse(classic.contains(".displayRating()"))

        val category = File("src/tv/java/com/totaliptv/pro/ui/browse/CategoryScreen.kt").readText()
        assertTrue(category.contains("mergedDisplayRating(item)"))
        assertFalse(category.contains("item.displayRating()"))
    }
}
