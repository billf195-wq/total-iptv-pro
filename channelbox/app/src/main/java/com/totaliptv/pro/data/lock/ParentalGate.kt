package com.totaliptv.pro.data.lock

import com.totaliptv.pro.data.model.Category
import com.totaliptv.pro.data.model.WatchProgress
import com.totaliptv.pro.dvr.DvrRecorder
import com.totaliptv.pro.dvr.RecordingEntry
import com.totaliptv.pro.dvr.ScheduledRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App-wide PIN lock state. The catalog repository filters lists with [config]; Favorites,
 * Continue watching and Recordings ask [isHiddenId]. There is no master PIN or bypass: the
 * only way past a forgotten PIN is [resetForgotten], which sets a new PIN, wipes the user's
 * locked-category choices (adult categories stay locked) and leaves the session locked.
 */
object ParentalGate {
    interface Store {
        fun readConfig(): LockConfig
        fun writeConfig(config: LockConfig)
        fun readRemembered(): Set<String>
        fun writeRemembered(ids: Set<String>)
    }

    private object MemoryStore : Store {
        private var config = LockConfig()
        private var remembered: Set<String> = emptySet()
        override fun readConfig() = config
        override fun writeConfig(config: LockConfig) { this.config = config }
        override fun readRemembered() = remembered
        override fun writeRemembered(ids: Set<String>) { remembered = ids }
    }

    @Volatile
    private var store: Store = MemoryStore

    @Volatile
    var config: LockConfig = LockConfig()
        private set

    @Volatile
    var session: ParentalSession = ParentalSession()
        private set

    /** Ids hidden by the last catalog filter pass. */
    @Volatile
    private var computedHidden: Set<String> = emptySet()

    /** Ids hidden at the last complete catalog load; covers titles whose list has not loaded yet. */
    @Volatile
    private var remembered: Set<String> = emptySet()

    private val _revision = MutableStateFlow(0)

    /** Bumps whenever what is hidden may have changed. UI keys refreshes on this. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun install(store: Store, session: ParentalSession = ParentalSession()) {
        this.store = store
        this.session = session
        config = runCatching { store.readConfig() }.getOrDefault(LockConfig())
        remembered = runCatching { store.readRemembered() }.getOrDefault(emptySet())
        computedHidden = emptySet()
        changed()
    }

    /** Called by the repository; returns nothing. Listeners re-filter the catalog. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    val isEnabled: Boolean get() = config.enabled

    /** True when locked content must be hidden right now. */
    val isActive: Boolean get() = config.enabled && !session.unlocked

    fun isHiddenId(id: String?): Boolean {
        if (id.isNullOrBlank() || !isActive) return false
        return id in computedHidden || id in remembered
    }

    fun hides(progress: WatchProgress): Boolean =
        isHiddenId(progress.id) || isHiddenId(progress.catalogId)

    fun hides(entry: RecordingEntry): Boolean = isHiddenId(entry.channelId)

    fun hides(entry: ScheduledRecording): Boolean = isHiddenId(entry.channelId)

    /** Recordings screen view: locked titles left out of the library, schedules and the active card. */
    fun visible(snapshot: DvrRecorder.Snapshot): DvrRecorder.Snapshot {
        if (!isActive) return snapshot
        return snapshot.copy(
            active = snapshot.active?.takeUnless { hides(it) },
            recordings = snapshot.recordings.filterNot { hides(it) },
            schedules = snapshot.schedules.filterNot { hides(it) }
        )
    }

    fun isCategoryLocked(category: Category): Boolean = config.isLocked(category)

    /** Repository callback after each filter pass. [complete] = every list (live, movies, series) loaded. */
    fun onCatalogFiltered(hidden: Set<String>, complete: Boolean) {
        val before = computedHidden
        computedHidden = hidden
        if (complete && config.enabled && hidden != remembered) {
            remembered = hidden
            runCatching { store.writeRemembered(hidden) }
        }
        if (before != hidden) _revision.value = _revision.value + 1
    }

    fun verify(pin: String): Boolean = PinHash.verify(pin, config.pinHash)

    /** First-time set. The lock starts active right away. */
    fun setPin(pin: String): Boolean {
        if (!PinHash.isValidPin(pin)) return false
        saveConfig(config.copy(pinHash = PinHash.create(pin)))
        session.lockNow()
        changed()
        return true
    }

    fun unlock(pin: String): Boolean {
        if (!verify(pin)) return false
        session.unlock()
        changed()
        return true
    }

    fun lockNow() {
        session.lockNow()
        changed()
    }

    fun changePin(current: String, newPin: String): Boolean {
        if (!verify(current) || !PinHash.isValidPin(newPin)) return false
        saveConfig(config.copy(pinHash = PinHash.create(newPin)))
        changed()
        return true
    }

    /** Turns the lock off. Ticked categories are kept for the next time it is turned on. */
    fun turnOff(current: String): Boolean {
        if (!verify(current)) return false
        saveConfig(config.copy(pinHash = null))
        session.lockNow()
        remembered = emptySet()
        runCatching { store.writeRemembered(emptySet()) }
        changed()
        return true
    }

    /** Forgotten PIN: new PIN, locked list wiped back to the automatic adult categories, session locked. */
    fun resetForgotten(newPin: String): Boolean {
        if (!PinHash.isValidPin(newPin)) return false
        saveConfig(LockConfig(pinHash = PinHash.create(newPin)))
        session.lockNow()
        changed()
        return true
    }

    /** Tick / untick a category. Only allowed while unlocked. */
    fun toggleCategory(category: Category): Boolean {
        if (!config.enabled || !session.unlocked) return false
        saveConfig(config.toggled(category))
        changed()
        return true
    }

    fun onAppBackground() {
        session.onBackground()
    }

    fun onAppForeground() {
        if (session.onForeground()) changed()
    }

    private fun saveConfig(next: LockConfig) {
        config = next
        runCatching { store.writeConfig(next) }
    }

    private fun changed() {
        _revision.value = _revision.value + 1
        for (l in listeners) runCatching { l() }
    }
}
