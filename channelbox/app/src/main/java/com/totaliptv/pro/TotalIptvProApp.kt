package com.totaliptv.pro

import android.app.Application
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.WatchProgressStore
import com.totaliptv.pro.data.repo.CatalogRepository

class TotalIptvProApp : Application() {
    lateinit var preferences: AppPreferences
        private set
    lateinit var repository: CatalogRepository
        private set
    lateinit var watchProgress: WatchProgressStore
        private set

    override fun onCreate() {
        super.onCreate()
        preferences = AppPreferences(this)
        repository = CatalogRepository(preferences)
        watchProgress = WatchProgressStore(this)
    }
}
