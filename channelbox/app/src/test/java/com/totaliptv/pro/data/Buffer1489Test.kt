package com.totaliptv.pro.data

import com.totaliptv.pro.ui.player.PlayerStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Buffer1489Test {
    @Test
    fun gateBlocksRefreshWhilePlaying() {
        PlaybackGate.resetForTest()
        assertTrue(PlaybackGate.refreshAllowed())
        PlaybackGate.enter()
        assertFalse(PlaybackGate.refreshAllowed())
        PlaybackGate.enter()
        PlaybackGate.exit()
        assertFalse(PlaybackGate.refreshAllowed())
        PlaybackGate.exit()
        assertTrue(PlaybackGate.refreshAllowed())
        PlaybackGate.exit() // extra exit never goes negative
        PlaybackGate.enter()
        assertFalse(PlaybackGate.refreshAllowed())
        PlaybackGate.resetForTest()
    }

    @Test
    fun guideListingFreshForSixHoursUsableForTwelve() {
        assertEquals(6L * 60L * 60L * 1000L, GuideBulkCache.TTL_MS)
        assertEquals(12L * 60L * 60L * 1000L, GuideBulkCache.USABLE_MS)
    }

    @Test
    fun liveBufferHasCushionAndByteCap() {
        assertEquals(2_500, PlayerStream.LIVE_PLAYBACK_BUFFER_MS)
        assertEquals(4_000, PlayerStream.LIVE_REBUFFER_MS)
        assertEquals(10_000, PlayerStream.LIVE_MIN_BUFFER_MS)
        assertTrue(PlayerStream.LIVE_MAX_BUFFER_MS in 20_000..30_000)
        assertTrue(PlayerStream.LIVE_TARGET_BUFFER_BYTES <= 32 * 1024 * 1024)
        assertTrue(PlayerStream.LIVE_STUCK_BUFFER_MS in 12_000L..15_000L)
        assertFalse(PlayerStream.shouldFailOverOnStall(playedFine = true))
        assertTrue(PlayerStream.shouldFailOverOnStall(playedFine = false))
    }

    @Test
    fun repositoryAndPlayersUseGate() {
        val repo = java.io.File("src/main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt").readText()
        assertTrue(repo.contains("PlaybackGate.refreshAllowed()"))
        assertTrue(repo.contains("bulkCall?.cancel()"))
        val player = java.io.File("src/main/java/com/totaliptv/pro/ui/player/PlayerActivity.kt").readText()
        assertTrue(player.contains("PlaybackGate.enter()"))
        assertTrue(player.contains("PlaybackGate.exit()"))
        assertTrue(player.contains("setTargetBufferBytes(PlayerStream.LIVE_TARGET_BUFFER_BYTES)"))
        val split = java.io.File("src/main/java/com/totaliptv/pro/ui/player/SplitPlayerActivity.kt").readText()
        assertTrue(split.contains("PlaybackGate.enter()"))
    }
}
