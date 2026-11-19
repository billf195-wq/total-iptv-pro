package com.totaliptv.pro.data.lock

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.dvr.DvrRecorder
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.ui.settings.PinLockDialog
import com.totaliptv.pro.ui.settings.PinLockEntry
import com.totaliptv.pro.ui.settings.pinLockEntryClicked
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeStore : ParentalGate.Store {
    var config = LockConfig()
    var remembered: Set<String> = emptySet()
    override fun readConfig() = config
    override fun writeConfig(config: LockConfig) { this.config = config }
    override fun readRemembered() = remembered
    override fun writeRemembered(ids: Set<String>) { remembered = ids }
}

class PinHashTest {
    @Test
    fun storesSaltedHashNeverThePin() {
        val a = PinHash.create("1234")
        val b = PinHash.create("1234")
        assertFalse(a.contains("1234"))
        assertNotEquals(a, b, "a fresh salt each time")
        assertTrue(a.startsWith("v1:"))
        assertEquals(4, a.split(':').size)
    }

    @Test
    fun verifiesOnlyTheRightPin() {
        val stored = PinHash.create("0420")
        assertTrue(PinHash.verify("0420", stored))
        assertFalse(PinHash.verify("0421", stored))
        assertFalse(PinHash.verify("", stored))
        assertFalse(PinHash.verify("0420", null))
        assertFalse(PinHash.verify("0420", "garbage"))
        assertFalse(PinHash.verify("0420", "v1:0:zz:zz"))
    }

    @Test
    fun sameSaltSamePinSameHash() {
        val salt = ByteArray(16) { it.toByte() }
        assertEquals(PinHash.create("9876", salt), PinHash.create("9876", salt))
    }

    @Test
    fun onlyFourDigitPinsAreValid() {
        assertTrue(PinHash.isValidPin("0000"))
        listOf("123", "12345", "12a4", "", "１２３４").forEach { assertFalse(PinHash.isValidPin(it), it) }
    }
}

class AdultCategoriesTest {
    @Test
    fun matchesAdultMarkersCaseInsensitive() {
        listOf(
            "Adult", "ADULTS", "XXX Movies", "xxx", "18+", "Movies 18 +", "+18 Live", "For Adults",
            "VOD | ADULT", "Adult-Live", "porn", "|XXX| HD",
            // 1.4.108
            "ADULTOS", "Peliculas Adultas", "Adulte", "Films adultes", "Film per adulti", "Erotic",
            "EROTICA HD", "Eróticos", "X X X", "x.x.x", "X-X-X Live", "(18+)", "[18 +]", "18 + VOD",
            "+ 18", "18-+", "18 PLUS", "Movies 18＋"
        ).forEach { assertTrue(AdultCategories.isAdult(it), it) }
    }

    @Test
    fun isConservative() {
        listOf(
            "Young Adult", "Adult Swim", "Kids", "News", "Sports 1080p", "Top 100", "UK 18",
            "Series 2018+", "Classics", "Boxxx", "Maxxx", "Adultery Drama", "", "Netflix",
            // 1.4.108
            "Jóvenes adultos", "Box x", "Top 180", "XL Sports", "Heroic Tales", "Season 18", "2018 plus"
        ).forEach { assertFalse(AdultCategories.isAdult(it), it) }
        assertFalse(AdultCategories.isAdult(null))
    }
}

class CatalogLockFilterTest {
    private val news = Category("live-1", "News", ContentKind.LIVE)
    private val adult = Category("live-2", "XXX Live", ContentKind.LIVE)
    private val movies = Category("vod-1", "Action", ContentKind.VOD)
    private val kids = Category("vod-2", "Kids", ContentKind.VOD)
    private val cats = listOf(news, adult, movies, kids)
    private fun item(id: String, cat: String, kind: ContentKind = ContentKind.LIVE, also: List<String> = emptyList()) =
        MediaItem(id = id, name = id, streamUrl = "", categoryId = cat, kind = kind, categoryIds = also)
    private val items = listOf(
        item("ch-news", "live-1"),
        item("ch-adult", "live-2"),
        item("ch-both", "live-1", also = listOf("live-1", "live-2")),
        item("m-action", "vod-1", ContentKind.VOD),
        item("m-kids", "vod-2", ContentKind.VOD)
    )

    @Test
    fun adultCategoriesAreLockedAutomatically() {
        val r = CatalogLockFilter.apply(cats, items, LockConfig(pinHash = "x"))
        assertEquals(listOf("live-1", "vod-1", "vod-2"), r.categories.map { it.id })
        assertEquals(setOf("ch-adult", "ch-both"), r.hiddenItemIds)
        assertEquals(listOf("ch-news", "m-action", "m-kids"), r.items.map { it.id })
    }

    @Test
    fun tickedCategoriesAreHiddenAndUntickedAdultIsShown() {
        var cfg = LockConfig(pinHash = "x").toggled(kids)
        assertTrue(cfg.isLocked(kids))
        cfg = cfg.toggled(adult)
        assertFalse(cfg.isLocked(adult))
        val r = CatalogLockFilter.apply(cats, items, cfg)
        assertEquals(setOf("m-kids"), r.hiddenItemIds)
        cfg = cfg.toggled(adult).toggled(kids)
        assertTrue(cfg.isLocked(adult))
        assertFalse(cfg.isLocked(kids))
    }

    @Test
    fun nothingLockedReturnsTheSameLists() {
        val clean = listOf(news, movies)
        val r = CatalogLockFilter.apply(clean, items, LockConfig(pinHash = "x"))
        assertTrue(r.items === items)
        assertTrue(r.categories === clean)
    }

    @Test
    fun episodeParentsAreHiddenToo() {
        val ep = MediaItem(id = "series-ep-1", name = "e", streamUrl = "", categoryId = "live-2", kind = ContentKind.SERIES, seriesCatalogId = "series-9")
        val r = CatalogLockFilter.apply(cats, listOf(ep), LockConfig(pinHash = "x"))
        assertTrue("series-9" in r.hiddenItemIds)
    }
}

class ParentalSessionTest {
    @Test
    fun relocksOnlyAfterLongBackground() {
        var now = 0L
        val s = ParentalSession(relockAfterMs = 1000, clock = { now })
        assertFalse(s.unlocked, "a new session starts locked")
        s.unlock()
        s.onBackground(); now = 999
        assertFalse(s.onForeground())
        assertTrue(s.unlocked)
        s.onBackground(); now += 1000
        assertTrue(s.onForeground())
        assertFalse(s.unlocked)
    }

    @Test
    fun lockNowLocks() {
        val s = ParentalSession()
        s.unlock(); s.lockNow()
        assertFalse(s.unlocked)
    }
}

class ParentalGateTest {
    private lateinit var store: FakeStore
    private var now = 0L
    private val adult = Category("live-2", "Adult", ContentKind.LIVE)
    private val kids = Category("vod-2", "Kids", ContentKind.VOD)

    @BeforeTest
    fun setUp() {
        store = FakeStore()
        now = 0L
        ParentalGate.install(store, ParentalSession(relockAfterMs = 1000, clock = { now }))
    }

    @AfterTest
    fun tearDown() {
        ParentalGate.install(FakeStore())
    }

    private fun progress(id: String, catalogId: String? = null) =
        WatchProgress(id = id, positionMs = 1, durationMs = 2, title = id, kind = ContentKind.VOD, streamUrl = "", catalogId = catalogId)

    @Test
    fun offByDefaultHidesNothing() {
        assertFalse(ParentalGate.isEnabled)
        ParentalGate.onCatalogFiltered(setOf("a"), true)
        assertFalse(ParentalGate.isHiddenId("a"))
    }

    @Test
    fun setPinStoresHashLocksAndHides() {
        assertTrue(ParentalGate.setPin("1357"))
        assertFalse(store.config.pinHash!!.contains("1357"))
        assertTrue(ParentalGate.isActive)
        ParentalGate.onCatalogFiltered(setOf("vod-9", "series-3"), complete = true)
        assertTrue(ParentalGate.isHiddenId("vod-9"))
        assertTrue(ParentalGate.hides(progress("series-ep-1", catalogId = "series-3")))
        assertFalse(ParentalGate.hides(progress("vod-1")))
        assertEquals(setOf("vod-9", "series-3"), store.remembered)
    }

    @Test
    fun unlockShowsUntilLockNowOrBackground() {
        ParentalGate.setPin("1357")
        ParentalGate.onCatalogFiltered(setOf("x"), true)
        assertFalse(ParentalGate.unlock("0000"))
        assertTrue(ParentalGate.isHiddenId("x"))
        assertTrue(ParentalGate.unlock("1357"))
        assertFalse(ParentalGate.isHiddenId("x"))
        ParentalGate.lockNow()
        assertTrue(ParentalGate.isHiddenId("x"))
        ParentalGate.unlock("1357")
        ParentalGate.onAppBackground(); now += 5_000
        ParentalGate.onAppForeground()
        assertTrue(ParentalGate.isHiddenId("x"), "relocked after the background timeout")
    }

    @Test
    fun appRestartIsLocked() {
        ParentalGate.setPin("1357")
        ParentalGate.unlock("1357")
        ParentalGate.install(store) // new process
        assertTrue(ParentalGate.isEnabled)
        assertTrue(ParentalGate.isActive)
    }

    @Test
    fun rememberedIdsHideTitlesBeforeTheirListLoads() {
        ParentalGate.setPin("1357")
        ParentalGate.onCatalogFiltered(setOf("vod-adult"), complete = true)
        ParentalGate.install(store)
        ParentalGate.onCatalogFiltered(emptySet(), complete = false) // live loaded, movies not yet
        assertTrue(ParentalGate.isHiddenId("vod-adult"))
    }

    @Test
    fun changeAndTurnOffNeedTheCurrentPin() {
        ParentalGate.setPin("1111")
        assertFalse(ParentalGate.changePin("2222", "3333"))
        assertTrue(ParentalGate.changePin("1111", "3333"))
        assertTrue(ParentalGate.verify("3333"))
        assertFalse(ParentalGate.turnOff("1111"))
        assertTrue(ParentalGate.isEnabled)
        assertTrue(ParentalGate.turnOff("3333"))
        assertFalse(ParentalGate.isEnabled)
        assertNull(store.config.pinHash)
    }

    @Test
    fun categoriesCanOnlyChangeWhileUnlocked() {
        ParentalGate.setPin("1111")
        assertFalse(ParentalGate.toggleCategory(kids))
        ParentalGate.unlock("1111")
        assertTrue(ParentalGate.toggleCategory(kids))
        assertTrue(ParentalGate.isCategoryLocked(kids))
        assertEquals(setOf("vod-2"), store.config.extraLocked)
    }

    @Test
    fun forgotPinResetWipesTheListKeepsAdultLockedAndStaysLocked() {
        ParentalGate.setPin("1111")
        ParentalGate.unlock("1111")
        ParentalGate.toggleCategory(kids)
        ParentalGate.toggleCategory(adult) // untick adult
        assertTrue(ParentalGate.resetForgotten("2468"))
        assertTrue(ParentalGate.isActive, "reset never unlocks")
        assertFalse(ParentalGate.verify("1111"))
        assertTrue(ParentalGate.verify("2468"))
        assertEquals(emptySet(), store.config.extraLocked)
        assertEquals(emptySet(), store.config.autoExempt)
        assertTrue(ParentalGate.isCategoryLocked(adult))
        assertFalse(ParentalGate.isCategoryLocked(kids))
    }

    @Test
    fun recordingsAreFiltered() {
        ParentalGate.setPin("1111")
        ParentalGate.onCatalogFiltered(setOf("ch-adult"), true)
        val snap = DvrRecorder.Snapshot(
            active = RecordingEntry("r0", "Adult", "now", 1, channelId = "ch-adult"),
            recordings = listOf(
                RecordingEntry("r1", "Adult", "a", 1, channelId = "ch-adult"),
                RecordingEntry("r2", "News", "b", 2, channelId = "ch-news")
            ),
            schedules = emptyList(),
            recordingsDir = "d"
        )
        val v = ParentalGate.visible(snap)
        assertNull(v.active)
        assertEquals(listOf("r2"), v.recordings.map { it.id })
        ParentalGate.unlock("1111")
        assertTrue(ParentalGate.visible(snap) === snap)
    }
}

class PinFlowTest {
    @BeforeTest
    fun setUp() = ParentalGate.install(FakeStore())

    @AfterTest
    fun tearDown() = ParentalGate.install(FakeStore())

    private fun PinFlow.type(pin: String): Boolean {
        pin.forEach { digit(it) }
        return submit()
    }

    @Test
    fun setNeedsTheSamePinTwice() {
        val f = PinFlow(PinMode.SET)
        assertFalse(f.type("1234"))
        assertEquals(PinFlow.Step.CONFIRM, f.step)
        assertFalse(f.type("9999"))
        assertEquals(PinFlow.Step.NEW, f.step)
        assertTrue(f.error!!.contains("match"))
        assertFalse(f.type("1234"))
        assertTrue(f.type("1234"))
        assertTrue(ParentalGate.verify("1234"))
    }

    @Test
    fun wrongCurrentPinIsRejected() {
        ParentalGate.setPin("1234")
        val f = PinFlow(PinMode.UNLOCK)
        assertFalse(f.type("0000"))
        assertEquals("Wrong PIN", f.error)
        assertTrue(f.type("1234"))
        assertFalse(ParentalGate.isActive)
    }

    @Test
    fun changeTurnOffAndReset() {
        ParentalGate.setPin("1234")
        val c = PinFlow(PinMode.CHANGE)
        assertFalse(c.type("1234")); assertFalse(c.type("5678")); assertTrue(c.type("5678"))
        assertTrue(ParentalGate.verify("5678"))
        val r = PinFlow(PinMode.RESET)
        assertEquals(PinFlow.Step.NEW, r.step, "reset never asks for the old PIN")
        assertFalse(r.type("1111")); assertTrue(r.type("1111"))
        assertTrue(ParentalGate.verify("1111"))
        val off = PinFlow(PinMode.TURN_OFF)
        assertTrue(off.type("1111"))
        assertFalse(ParentalGate.isEnabled)
    }

    @Test
    fun digitsAreCappedAndBackspaceWorks() {
        val f = PinFlow(PinMode.SET)
        "123456".forEach { f.digit(it) }
        assertEquals("1234", f.digits)
        f.backspace()
        assertEquals("123", f.digits)
        f.digit('x')
        assertEquals("123", f.digits)
        assertFalse(f.submit())
        assertEquals("Enter 4 digits", f.error)
        assertEquals("\u25CF  \u25CF  \u25CF  \u25CB", PinPadView.of(f).dots)
    }

    @Test
    fun settingsEntriesFollowTheLockState() {
        assertEquals(listOf(PinLockEntry.SET), PinLockEntry.visibleEntries())
        ParentalGate.setPin("1234")
        assertEquals(PinLockEntry.UNLOCK, PinLockEntry.visibleEntries().first())
        assertTrue(PinLockEntry.FORGOT in PinLockEntry.visibleEntries())
        var opened: PinLockDialog? = null
        pinLockEntryClicked(PinLockEntry.CATEGORIES, { opened = it }, {})
        assertEquals(PinLockDialog.Pad(PinMode.PICK_CATEGORIES), opened, "picker needs the PIN while locked")
        ParentalGate.unlock("1234")
        assertEquals(PinLockEntry.LOCK_NOW, PinLockEntry.visibleEntries().first())
        pinLockEntryClicked(PinLockEntry.CATEGORIES, { opened = it }, {})
        assertEquals(PinLockDialog.Categories, opened)
        pinLockEntryClicked(PinLockEntry.LOCK_NOW, { opened = it }, {})
        assertTrue(ParentalGate.isActive)
        pinLockEntryClicked(PinLockEntry.FORGOT, { opened = it }, {})
        assertEquals(PinLockDialog.Forgot, opened)
    }
}

/** Source wiring: every list the user can see goes through the lock. */
class ParentalWiringSourceTest {
    private fun src(path: String): String {
        val roots = listOf(File("src"), File("app/src"), File("channelbox/app/src"))
        val root = roots.first { File(it, "main").isDirectory }
        return File(root, path).readText()
    }

    @Test
    fun repositoryFiltersAndKeepsRawForLoads() {
        val repo = src("main/java/com/totaliptv/pro/data/repo/CatalogRepository.kt")
        assertTrue(repo.contains("CatalogLockFilter.apply(cats, items, ParentalGate.config)"))
        assertTrue(repo.contains("VodDiskCache.write(dir, sourceId, rawCategories, rawItems)"))
        assertTrue(repo.contains("ParentalGate.addListener(lockListener)"))
        assertTrue(repo.contains("list.filterNot { ParentalGate.isHiddenId(it.id) }"))
        assertFalse(repo.contains("keepItems = cachedItems"))
    }

    @Test
    fun continueWatchingRecordingsAndSettingsUseTheLock() {
        assertTrue(src("main/java/com/totaliptv/pro/data/local/WatchProgressStore.kt").contains("!ParentalGate.hides(it)"))
        assertTrue(src("tv/java/com/totaliptv/pro/ui/dvr/RecordingsScreen.kt").contains("ParentalGate.visible(dvrSnapshot)"))
        assertTrue(src("phone/java/com/totaliptv/pro/ui/dvr/PhoneRecordingsScreen.kt").contains("ParentalGate.visible(dvrSnapshot)"))
        assertTrue(src("tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").contains("PinLockDialogHost("))
        assertTrue(src("tv/java/com/totaliptv/pro/ui/desktop/DesktopPanes.kt").contains("PinLockDialogHost("))
        assertTrue(src("phone/java/com/totaliptv/pro/ui/settings/PhoneSettingsScreen.kt").contains("PhonePinLockDialogHost("))
        // Classic Settings is lazy: PIN cards keyed by slot so focus survives a lock-state change.
        assertTrue(src("tv/java/com/totaliptv/pro/ui/settings/SettingsScreen.kt").contains("key = \"pin-slot-\$index\""))
        // Phone is touch-first: its picker hint must not tell the user to press OK.
        assertTrue(src("phone/java/com/totaliptv/pro/ui/settings/PhonePinLockDialogs.kt").contains("PinLockEntry.PICKER_HINT_TOUCH"))
        assertTrue(src("main/java/com/totaliptv/pro/TotalIptvProApp.kt").contains("ParentalGate.install(ParentalLockStore(this))"))
    }
}

/** 1.4.108: PIN status line and Unlock wording. */
class PinStatusLineTest {
    @Test
    fun statusLineCountsLockedCategoriesAndWarnsWhenNone() {
        assertTrue(PinFlow.statusLine(false, false, -1).startsWith("Off."))
        assertEquals("On — locked. 3 categories hidden (adult and chosen categories).", PinFlow.statusLine(true, true, 3))
        assertEquals("On — locked. 1 category hidden (adult and chosen categories).", PinFlow.statusLine(true, true, 1))
        assertTrue(PinFlow.statusLine(true, true, 0).contains("no categories are locked yet"))
        assertTrue(PinFlow.statusLine(true, true, -1).contains("Adult and chosen categories are hidden"))
        val unlocked = PinFlow.statusLine(true, false, 3)
        assertTrue(unlocked.contains("until you choose Lock now or leave the app for 5 minutes"), unlocked)
        assertFalse(unlocked.contains("app closes"))
        assertTrue(PinFlow.statusLine(true, false, 0).contains("No categories are locked yet"))
    }

    @Test
    fun unlockWordingNoLongerSaysUntilTheAppCloses() {
        val e = com.totaliptv.pro.ui.settings.PinLockEntry.UNLOCK
        assertFalse(e.subtitle.contains("app closes"))
        assertTrue(e.subtitle.contains("Lock now") && e.subtitle.contains("5 minutes"))
        val done = com.totaliptv.pro.ui.settings.PinLockEntry.doneMessage(PinMode.UNLOCK)!!
        assertTrue(done.contains("5 minutes"))
    }

    @Test
    fun gateReportsLockedCategoryCount() {
        ParentalGate.install(object : ParentalGate.Store {
            override fun readConfig() = LockConfig()
            override fun writeConfig(config: LockConfig) {}
            override fun readRemembered() = emptySet<String>()
            override fun writeRemembered(ids: Set<String>) {}
        })
        val before = ParentalGate.revision.value
        ParentalGate.onCatalogFiltered(setOf("a"), false, 4)
        assertEquals(4, ParentalGate.lockedCategoryCount)
        assertTrue(ParentalGate.revision.value > before)
    }
}
