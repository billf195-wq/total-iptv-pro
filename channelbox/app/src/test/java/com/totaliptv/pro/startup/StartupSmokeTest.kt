package com.totaliptv.pro.startup

import android.content.Context
import android.content.Intent
import com.totaliptv.pro.MainActivity
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.dvr.DvrPaths
import com.totaliptv.pro.dvr.DvrStore
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.dvr.RecordingStatus
import com.totaliptv.pro.ui.player.PlayerActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Creates the real application and launcher activities. 1.4.75 died in
 * Application.onCreate before any screen because DvrRecorder's init block
 * used a field that was not initialized yet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = TotalIptvProApp::class)
class StartupSmokeTest {

    @Test
    fun emptyStorageLaunchesHomeAndPlayer() {
        val app = launchHomeAndPlayer()
        assertNotNull(app.dvr)
        assertNotNull(app.repository)
        assertTrue(app.dvr.snapshot.value.recordings.isEmpty() || app.dvr.snapshot.value.recordings.none { it.isActive() })
    }

    @Test
    @Config(sdk = [30], application = StaleRecordingsApplication::class)
    fun staleRecordingFileLaunchesAndIsReconciled() {
        val app = launchHomeAndPlayer()
        val recordings = waitForReconcile(app)
        val stale = recordings.single { it.id == "stale" }
        val zero = recordings.single { it.id == "zero" }
        val normal = recordings.single { it.id == "normal" }
        assertEquals(RecordingStatus.STOPPED.name, stale.status)
        assertEquals("Interrupted", stale.errorMessage)
        assertTrue(stale.playable())
        assertEquals(RecordingStatus.FAILED.name, zero.status)
        assertEquals("No video saved", zero.errorMessage)
        assertFalse(zero.playable())
        assertEquals(RecordingStatus.COMPLETED.name, normal.status)
        assertTrue(normal.playable())
    }

    private fun launchHomeAndPlayer(): TotalIptvProApp {
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(home.get().isFinishing)
        val app = home.get().application as TotalIptvProApp
        val playerIntent = Intent(app, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, "")
            putExtra(PlayerActivity.EXTRA_TITLE, "Smoke")
            putExtra(PlayerActivity.EXTRA_ID, "smoke")
            putExtra(PlayerActivity.EXTRA_KIND, "LIVE")
        }
        val player = Robolectric.buildActivity(PlayerActivity::class.java, playerIntent).setup()
        assertFalse(player.get().isFinishing)
        player.close()
        return app
    }

    private fun waitForReconcile(app: TotalIptvProApp): List<RecordingEntry> {
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            val list = app.dvr.snapshot.value.recordings
            val stale = list.find { it.id == "stale" }
            if (stale != null && stale.status == RecordingStatus.STOPPED.name) return list
            Thread.sleep(40)
        }
        return app.dvr.snapshot.value.recordings
    }
}

/** Writes a recordings file before [TotalIptvProApp.onCreate]. */
class StaleRecordingsApplication : TotalIptvProApp() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        val media = File(filesDir, "seed-media").apply { mkdirs() }
        val stale = File(media, "stale.ts").apply { writeBytes(ByteArray(128)) }
        val zero = File(media, "zero.ts").apply { writeBytes(ByteArray(0)) }
        val normal = File(media, "normal.ts").apply { writeBytes(ByteArray(64)) }
        val store = DvrStore(File(DvrPaths.metadataDir(filesDir.absolutePath)))
        store.upsert(entry("normal", normal, RecordingStatus.COMPLETED))
        store.upsert(entry("zero", zero, RecordingStatus.RECORDING))
        store.upsert(entry("stale", stale, RecordingStatus.RECORDING))
    }

    private fun entry(id: String, file: File, status: RecordingStatus) = RecordingEntry(
        id = id,
        channelName = "Example",
        title = id,
        startMs = 1_700_000_000_000L,
        filePath = file.absolutePath,
        streamUrl = "http://example.test/live/u/p/1.ts",
        status = status.name
    )
}
