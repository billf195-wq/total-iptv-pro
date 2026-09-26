package com.totaliptv.pro.util

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensitiveTextTest {
    @Test
    fun redactsXtreamPathAndQueryBeforeAnythingIsShown() {
        val path = SensitiveText.redact("http://example.test/live/alice/secret/12.m3u8")
        assertFalse(path.contains("alice"))
        assertFalse(path.contains("secret"))
        assertTrue(path.contains("/live/***/***/"))

        val shown = SensitiveText.forUser(
            "timeout http://example.test/player_api.php?username=alice&password=secret"
        )
        assertFalse(shown.contains("alice"))
        assertFalse(shown.contains("secret"))
        assertFalse(shown.contains("player_api"))
        assertTrue(shown.contains("the server"))
        val leftover = SensitiveText.forUser("player_api.php?username=alice&password=secret")
        assertFalse(leftover.contains("alice"))
        assertFalse(leftover.contains("secret"))
        assertTrue(leftover.contains("Could not reach the server"))
    }

    @Test
    fun hidesPrivateLanAddressesFromUsers() {
        val msg = SensitiveText.forUser("failed to connect to 192.168.0.50")
        assertFalse(msg.contains("192.168"))
        assertTrue(msg.contains("a local network"))
        val plain = SensitiveText.forUser("Xtream login rejected (auth=0). Check URL/user/pass.")
        assertTrue(plain.contains("auth=0"))
    }

    @Test
    fun storageFailureMatchesDiskFullMessages() {
        assertTrue(SensitiveText.isStorageFailure(IOException("ENOSPC (No space left on device)")))
        assertTrue(SensitiveText.isStorageFailure(IOException("No space left on device")))
        assertFalse(SensitiveText.isStorageFailure(IOException("connection reset")))
    }
}
