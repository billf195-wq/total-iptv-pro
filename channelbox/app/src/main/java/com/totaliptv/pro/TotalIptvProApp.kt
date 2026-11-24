package com.totaliptv.pro

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.ParentalLockStore
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.totaliptv.pro.diagnostics.CrashLog
import com.totaliptv.pro.data.LogoUrls
import com.totaliptv.pro.billing.ExistingAppData
import com.totaliptv.pro.billing.LicenseController
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.LastPlaceStore
import com.totaliptv.pro.data.local.SavedLoginStore
import com.totaliptv.pro.data.model.SourceType
import com.totaliptv.pro.data.local.WatchProgressStore
import java.io.File
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.util.MemoryBudget
import com.totaliptv.pro.data.PosterPrefetch
import com.totaliptv.pro.dvr.DvrRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

open class TotalIptvProApp : Application(), ImageLoaderFactory {
    lateinit var preferences: AppPreferences
        private set
    lateinit var repository: CatalogRepository
        private set
    lateinit var watchProgress: WatchProgressStore
        private set
    lateinit var watched: com.totaliptv.pro.data.local.WatchedStore
        private set
    lateinit var dvr: DvrRecorder
        private set
    lateinit var lastPlace: LastPlaceStore
        private set
    lateinit var license: LicenseController
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        preferences = AppPreferences(this)
        // PIN lock: load the salted hash + choices before the catalog publishes its first lists.
        ParentalGate.install(ParentalLockStore(this))
        registerActivityLifecycleCallbacks(ParentalForegroundTracker())
        license = LicenseController.create(this) {
            preferences.hasStoredUserData() || ExistingAppData.presentOnDevice(this, emptySet())
        }
        license.start()
        repository = CatalogRepository(preferences)
        repository.attachVodCacheDir(filesDir)
        PosterPrefetch.start(this, repository, appScope)
        val memory = MemoryBudget.snapshot(this)
        repository.attachMemoryBudget(memory.tight)
        lastPlace = LastPlaceStore(File(filesDir, "last-place.json"))
        // Load off the main thread so a crash-restore is ready after the splash.
        lastPlace.preload()
        // Apply a saved service index only. FullRecheck starts after the catalog
        // is ready so first launch does not spike RAM before sign-in.
        repository.preloadServiceIndexFromCache()
        watchProgress = WatchProgressStore(this)
        watched = com.totaliptv.pro.data.local.WatchedStore(this)
        dvr = DvrRecorder(this)
        appScope.launch {
            runCatching { dvr.reconcileInterrupted() }
            dvr.ensureScheduler()
        }
        appScope.launch {
            preferences.recordingsDir.collect { dvr.overrideDir = it }
        }
        appScope.launch {
            runCatching { preferences.migrateSavedShelfHost() }
        }
        appScope.launch {
            // Upgrade path: an account signed in before 1.4.83 becomes the saved sign-in,
            // so the form stays filled after a later sign-out.
            runCatching {
                val store = SavedLoginStore(this@TotalIptvProApp)
                if (!store.seeded()) {
                    val src = preferences.sources.first()
                        .lastOrNull { it.type == SourceType.XTREAM && !it.xtreamBaseUrl.isNullOrBlank() }
                    if (src != null) {
                        store.seedOnce(
                            src.xtreamBaseUrl.orEmpty(),
                            src.xtreamUsername.orEmpty(),
                            src.xtreamPassword.orEmpty()
                        )
                    }
                }
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(MemoryBudget.coilMemoryCacheBytes(this@TotalIptvProApp))
                    .build()
            }
            .diskCache {
                val dir = cacheDir.resolve("image_cache")
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(MemoryBudget.coilDiskCacheBytes(dir))
                    .build()
            }
            .respectCacheHeaders(false)
            .allowHardware(false)
            .okHttpClient(
                OkHttpClient.Builder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", LogoUrls.USER_AGENT)
                                .build()
                        )
                    }
                    .build()
            )
            .build()
    }
}

/** Counts started activities so the PIN lock knows when the whole app goes to / returns from the background. */
private class ParentalForegroundTracker : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) ParentalGate.onAppForeground()
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) ParentalGate.onAppBackground()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
