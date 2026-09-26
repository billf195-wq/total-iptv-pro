package com.totaliptv.pro.data.update

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateShelfTest {
    @Test
    fun missingUsesOwnerDefaultAndBlankStaysBlank() {
        assertEquals(
            "http://example.test/tv/",
            UpdateSources.resolveShelf(null, "http://example.test/tv/")
        )
        assertEquals("", UpdateSources.resolveShelf("", "http://example.test/tv/"))
        assertEquals(
            "http://example.test/mine/",
            UpdateSources.resolveShelf("http://example.test/mine/", "http://example.test/tv/")
        )
    }

    @Test
    fun phoneShelfAppendsPhoneDirectoryUnlessAlreadyThere() {
        assertEquals("", UpdateSources.shelfForDevice("", "phone"))
        assertEquals(
            "http://example.test/updates/",
            UpdateSources.shelfForDevice("http://example.test/updates", "tv")
        )
        assertEquals(
            "http://example.test/updates/phone/",
            UpdateSources.shelfForDevice("http://example.test/updates", "phone")
        )
        assertEquals(
            "http://example.test/updates/phone/",
            UpdateSources.shelfForDevice("http://example.test/updates/phone/", "phone")
        )
    }
}
