package com.totaliptv.pro

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import com.totaliptv.pro.ui.dvr.PhoneRecordingsScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import com.totaliptv.pro.ui.theme.LocalTipColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.totaliptv.pro.data.model.ContentKind
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.browse.BrowseSection
import com.totaliptv.pro.ui.browse.PhoneBrowseScreen
import com.totaliptv.pro.ui.home.PhoneHomeScreen
import com.totaliptv.pro.ui.onboarding.OnboardingScreen
import com.totaliptv.pro.ui.player.PlayerActivity
import com.totaliptv.pro.ui.splash.LogoBannerSplash
import com.totaliptv.pro.ui.splash.StartupSplashGate
import com.totaliptv.pro.ui.search.PhoneSearchScreen
import com.totaliptv.pro.ui.settings.PhoneSettingsScreen
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.TotalIptvProTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TotalIptvProApp
        setContent {
            val appearance by app.preferences.appearanceMode.collectAsState(initial = AppearanceMode.DARK)
            val accent by app.preferences.accentPreset.collectAsState(initial = AccentPreset.BLUE)
            TotalIptvProTheme(appearance = appearance, accent = accent) {
                PhoneAppRoot(
                    repository = app.repository,
                    onPlay = { item -> playResolved(app.repository, item, startOver = false) },
                    onPlayFromStart = { item -> playResolved(app.repository, item, startOver = true) }
                )
            }
        }
    }

    private fun playResolved(
        repository: CatalogRepository,
        item: MediaItem,
        startOver: Boolean = false
    ) {
        val bound = repository.playableFrom(item)
        val catalogId = when {
            bound.kind == ContentKind.SERIES && !bound.id.startsWith("series-ep-") -> bound.id
            item.kind == ContentKind.SERIES && !item.id.startsWith("series-ep-") -> item.id
            item.id.startsWith("vod-") -> item.id
            bound.id.startsWith("vod-") -> bound.id
            else -> null
        }
        if (bound.kind != ContentKind.SERIES || bound.id.startsWith("series-ep-")) {
            openPlayer(bound, startOver = startOver, catalogId = catalogId)
            return
        }
        lifecycleScope.launch {
            val resolved = runCatching { repository.resolveSeriesPlayable(bound) }.getOrNull()
            val playable = resolved ?: bound
            val url = playable.streamUrl
            val leaf = url.substringAfterLast('/')
            if (url.isBlank() || (playable.kind == ContentKind.SERIES && !leaf.contains('.'))) {
                Toast.makeText(
                    this@MainActivity,
                    "No playable episode for " + item.name,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            openPlayer(playable, startOver = startOver, catalogId = catalogId ?: bound.id)
        }
    }

    private fun openPlayer(
        item: MediaItem,
        startOver: Boolean = false,
        catalogId: String? = null
    ) {
        if (item.streamUrl.isBlank()) {
            Toast.makeText(this, "No playable URL for " + item.name, Toast.LENGTH_SHORT).show()
            return
        }
        Log.i(
            "TotalIPTV.Live",
            "openPlayer name=${item.name} id=${item.id} sid=${item.xtreamStreamId} num=${item.channelNum} startOver=$startOver catalog=$catalogId url=${item.streamUrl}"
        )
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_URL, item.streamUrl)
                putExtra(PlayerActivity.EXTRA_TITLE, item.name)
                putExtra(PlayerActivity.EXTRA_ID, item.id)
                putExtra(PlayerActivity.EXTRA_KIND, item.kind.name)
                putExtra(PlayerActivity.EXTRA_LOGO, item.logoUrl ?: item.posterUrl)
                putExtra(PlayerActivity.EXTRA_START_OVER, startOver)
                if (!catalogId.isNullOrBlank()) {
                    putExtra(PlayerActivity.EXTRA_CATALOG_ID, catalogId)
                }
            }
        )
    }
}

private enum class PhoneTab(val label: String) {
    Home("Home"),
    Live("Live"),
    Movies("Movies"),
    Series("Series"),
    Search("Search"),
    Recordings("Recordings"),
    Settings("Settings")
}

@Composable
private fun PhoneAppRoot(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit
) {
    val sources by repository.sources.collectAsState(initial = emptyList())
    var prefsReady by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(PhoneTab.Home) }
    var splashDone by remember { mutableStateOf(StartupSplashGate.shownThisProcess) }

    LaunchedEffect(Unit) {
        repository.sources.first()
        prefsReady = true
    }

    if (!splashDone) {
        LogoBannerSplash(
            ready = prefsReady,
            statusMessage = if (prefsReady) null else "Starting…",
            onFinished = { splashDone = true }
        )
        return
    }

    if (!prefsReady) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Text("Starting…")
        }
        return
    }

    if (sources.isEmpty() || showOnboarding) {
        OnboardingScreen(
            repository = repository,
            onDone = {
                showOnboarding = false
                tab = PhoneTab.Home
            }
        )
        return
    }

    val pageBlack = LocalTipColors.current.isDark
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (pageBlack) Color(0xFF000000) else MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(
                containerColor = if (pageBlack) Color(0xFF000000) else MaterialTheme.colorScheme.surface
            ) {
                PhoneTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                imageVector = when (t) {
                                    PhoneTab.Home -> Icons.Default.Home
                                    PhoneTab.Live -> Icons.Default.LiveTv
                                    PhoneTab.Movies -> Icons.Default.Movie
                                    PhoneTab.Series -> Icons.Default.Tv
                                    PhoneTab.Search -> Icons.Default.Search
                                    PhoneTab.Recordings -> Icons.Default.VideoLibrary
                                    PhoneTab.Settings -> Icons.Default.Settings
                                },
                                contentDescription = t.label
                            )
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            PhoneTab.Home -> PhoneHomeScreen(
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                onOpenLive = { tab = PhoneTab.Live },
                onOpenMovies = { tab = PhoneTab.Movies },
                onOpenSeries = { tab = PhoneTab.Series },
                contentPadding = padding
            )
            PhoneTab.Live -> PhoneBrowseScreen(
                section = BrowseSection.Live,
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                contentPadding = padding
            )
            PhoneTab.Movies -> PhoneBrowseScreen(
                section = BrowseSection.Movies,
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                contentPadding = padding
            )
            PhoneTab.Series -> PhoneBrowseScreen(
                section = BrowseSection.Series,
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                contentPadding = padding
            )
            PhoneTab.Search -> PhoneSearchScreen(
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                contentPadding = padding
            )
            PhoneTab.Recordings -> PhoneRecordingsScreen(
                onPlay = onPlay,
                contentPadding = padding
            )
            PhoneTab.Settings -> PhoneSettingsScreen(
                repository = repository,
                onAddSource = { showOnboarding = true },
                onClearedToOnboarding = { showOnboarding = true },
                contentPadding = padding
            )
        }
    }
}
