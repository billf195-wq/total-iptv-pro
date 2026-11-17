package com.totaliptv.pro.data.local

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.ui.home.ContinueWatchingAction
import com.totaliptv.pro.ui.home.ContinueWatchingActions
import com.totaliptv.pro.ui.home.WatchedActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TotalIptvProApp::class)
class ContinueWatchingStoreTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun entry(id: String, catalog: String? = null) = WatchProgress(
        id = id,
        positionMs = 120_000L,
        durationMs = 3_600_000L,
        title = "Title $id",
        kind = if (id.startsWith("series-ep-")) ContentKind.SERIES else ContentKind.VOD,
        streamUrl = "http://example.invalid/$id.mp4",
        catalogId = catalog
    )

    private fun seed(store: WatchProgressStore, vararg ids: String) {
        ids.forEach {
            store.save(entry(it))
            Thread.sleep(3)
        }
    }

    @Test
    fun removeHidesUntilPlayedAgainAndKeepsResume() {
        val store = WatchProgressStore(ctx)
        seed(store, "vod-1", "vod-2", "vod-3")
        assertEquals(listOf("vod-3", "vod-2", "vod-1"), store.continueWatching().map { it.id })
        store.hideFromContinueWatching("vod-2")
        assertEquals(listOf("vod-3", "vod-1"), store.continueWatching().map { it.id })
        // Resume from the detail sheet still works.
        assertNotNull(store.forCatalogItem("vod-2"))
        // Persists across a new store instance (app restart).
        assertEquals(listOf("vod-3", "vod-1"), WatchProgressStore(ctx).continueWatching().map { it.id })
        Thread.sleep(3)
        store.save(entry("vod-2"))
        assertEquals("vod-2", store.continueWatching().first().id)
    }

    @Test
    fun movesHoldAcrossRestartsUntilPlayedAgain() {
        val store = WatchProgressStore(ctx)
        seed(store, "vod-1", "vod-2", "vod-3", "vod-4")
        // newest first: 4 3 2 1
        assertTrue(store.moveInContinueWatching("vod-1", ContinueWatchingOrder.Move.FRONT))
        assertEquals(listOf("vod-1", "vod-4", "vod-3", "vod-2"), store.continueWatching().map { it.id })
        assertTrue(store.moveInContinueWatching("vod-3", ContinueWatchingOrder.Move.LEFT))
        assertEquals(listOf("vod-1", "vod-3", "vod-4", "vod-2"), store.continueWatching().map { it.id })
        assertTrue(store.moveInContinueWatching("vod-1", ContinueWatchingOrder.Move.RIGHT))
        assertEquals(listOf("vod-3", "vod-1", "vod-4", "vod-2"), store.continueWatching().map { it.id })
        assertFalse(store.moveInContinueWatching("vod-3", ContinueWatchingOrder.Move.LEFT))
        assertFalse(store.moveInContinueWatching("vod-2", ContinueWatchingOrder.Move.RIGHT))
        assertEquals(listOf("vod-3", "vod-1", "vod-4", "vod-2"), WatchProgressStore(ctx).continueWatching().map { it.id })
        Thread.sleep(3)
        store.save(entry("vod-2"))
        assertEquals("vod-2", store.continueWatching().first().id)
    }

    @Test
    fun markWatchedDropsFromRowAndUnwatchedClearsCheck() {
        val app = ctx as TotalIptvProApp
        seed(app.watchProgress, "vod-7", "series-ep-55")
        val ep = app.watchProgress.continueWatching().first { it.id == "series-ep-55" }
        ContinueWatchingActions.apply(ctx, ep, ContinueWatchingAction.MARK_WATCHED)
        assertTrue(WatchedActions.isWatched(ctx, "series-ep-55"))
        assertNull(app.watchProgress.continueWatching().firstOrNull { it.id == "series-ep-55" })
        WatchedActions.setWatched(ctx, "series-ep-55", false)
        assertFalse(WatchedActions.isWatched(ctx, "series-ep-55"))
        WatchedActions.setWatched(ctx, "vod-7", true)
        assertTrue(WatchedStore(ctx).isWatched("vod-7"))
        assertTrue(app.watchProgress.continueWatching().isEmpty())
    }

    @Test
    fun menuOffersOnlyMovesThatDoSomething() {
        val first = ContinueWatchingAction.forPosition(0, 3, includeFavorite = true)
        assertFalse(ContinueWatchingAction.MOVE_LEFT in first)
        assertFalse(ContinueWatchingAction.MOVE_FRONT in first)
        assertTrue(ContinueWatchingAction.MOVE_RIGHT in first)
        assertTrue(ContinueWatchingAction.REMOVE in first)
        val last = ContinueWatchingAction.forPosition(2, 3, includeFavorite = false)
        assertTrue(ContinueWatchingAction.MOVE_FRONT in last)
        assertFalse(ContinueWatchingAction.MOVE_RIGHT in last)
        assertFalse(ContinueWatchingAction.TOGGLE_FAVORITE in last)
    }
}
