package com.totaliptv.pro

import android.app.Application
import com.totaliptv.pro.diagnostics.CrashLog
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.dvr.DvrRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TotalIptvProApp : Application() {
    lateinit var preferences: AppPreferences
        private set
    lateinit var repository: CatalogRepository
        private set
    lateinit var watchProgress: WatchProgressStore
        private set
    lateinit var dvr: DvrRecorder
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        preferences = AppPreferences(this)
        repository = CatalogRepository(preferences)
        watchProgress = WatchProgressStore(this)
        dvr = DvrRecorder(this)
        dvr.ensureScheduler()
        appScope.launch {
            preferences.recordingsDir.collect { dvr.overrideDir = it }
        }
        appScope.launch {
            runCatching { preferences.migrateSavedShelfHost() }
        }
    }
}
