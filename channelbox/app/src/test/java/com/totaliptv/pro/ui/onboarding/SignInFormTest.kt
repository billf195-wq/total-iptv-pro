package com.totaliptv.pro.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInFormTest {
    @Test
    fun dpadMovesThroughEveryItemAndStopsAtEnds() {
        assertEquals(1, SignInForm.move(0, 8, down = true))
        assertEquals(7, SignInForm.move(7, 8, down = true))
        assertEquals(0, SignInForm.move(0, 8, down = false))
        assertEquals(4, SignInForm.move(5, 8, down = false))
        var i = 0
        repeat(10) { i = SignInForm.move(i, 8, down = true) }
        assertEquals(7, i)
    }

    @Test
    fun firstRunSkipsSplash() {
        assertTrue(SignInForm.skipStartupSplash(hasSources = false))
        assertTrue(!SignInForm.skipStartupSplash(hasSources = true))
    }

    @Test
    fun formWiresImeAndSplash() {
        val form = java.io.File("src/main/java/com/totaliptv/pro/ui/onboarding/OnboardingScreen.kt").readText()
        assertTrue(form.contains("EditorInfo.IME_ACTION_DONE"))
        assertTrue(form.contains("EditorInfo.IME_ACTION_NEXT"))
        assertTrue(form.contains("saveXtream.performClick()"))
        assertTrue(form.contains("LogoBannerSplash("))
        val tv = java.io.File("src/tv/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(tv.contains("SignInForm.skipStartupSplash"))
        val phone = java.io.File("src/phone/java/com/totaliptv/pro/MainActivity.kt").readText()
        assertTrue(phone.contains("SignInForm.skipStartupSplash"))
        assertTrue(phone.contains("SignInForm.active"))
        assertTrue(tv.contains("SignInForm.active) Screen.Onboarding"))
    }
}
