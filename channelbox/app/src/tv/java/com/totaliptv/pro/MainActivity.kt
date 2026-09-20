package com.totaliptv.pro

import android.content.Intent
import android.util.Log
import android.widget.Toast
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.data.model.FavoriteRef
import com.totaliptv.pro.data.model.MediaItem
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.browse.BrowseScreen
import com.totaliptv.pro.ui.browse.BrowseSection
import com.totaliptv.pro.ui.epg.EpgGuideScreen
import com.totaliptv.pro.ui.home.HomeScreen
import com.totaliptv.pro.ui.onboarding.OnboardingScreen
import com.totaliptv.pro.ui.player.PlayerActivity
import com.totaliptv.pro.ui.search.SearchScreen
import com.totaliptv.pro.ui.settings.SettingsScreen
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.TotalIptvProTheme
import com.totaliptv.pro.ui.desktop.DesktopAppRoot
import com.totaliptv.pro.ui.StartupSplash
import com.totaliptv.pro.ui.StartupSplashGate
import com.totaliptv.pro.data.local.AppLayoutMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import com.totaliptv.pro.data.model.ContentKind

@OptIn(ExperimentalTvMaterial3Api::class)
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as TotalIptvProApp
        // Snapshot layout for THIS activity instance. Hot-swapping Classic root <-> DesktopAppRoot
        // mid-composition freezes Shield TV; recreate cleanly when the preference changes.
        setContent {
            var layoutReady by remember { mutableStateOf(false) }
            var switchingLayout by remember { mutableStateOf(false) }
            // Skip animated splash on theme recreate / warm process; show once per cold start.
            var splashDone by remember {
                mutableStateOf(StartupSplashGate.shownThisProcess)
            }
            var splashCatalogReady by remember {
                mutableStateOf(StartupSplashGate.shownThisProcess)
            }
            var boundLayout by remember { mutableStateOf(AppLayoutMode.CLASSIC) }
            val appearance by app.preferences.appearanceMode.collectAsState(initial = AppearanceMode.DARK)
            val accent by app.preferences.accentPreset.collectAsState(initial = AccentPreset.BLUE)

            LaunchedEffect(Unit) {
                val initial = runCatching { app.preferences.getAppLayoutMode() }
                    .getOrDefault(AppLayoutMode.CLASSIC)
                boundLayout = initial
                layoutReady = true
                app.preferences.appLayoutMode.collect { next ->
                    if (next != initial && !switchingLayout) {
                        // Paint a brief loading frame before recreate so Shield does not
                        // freeze on a mid-composition Classic <-> Desktop hot-swap.
                        switchingLayout = true
                        kotlinx.coroutines.delay(48)
                        recreate()
                        return@collect
                    }
                }
            }

            // While logo splash is up, load/update catalog so splash can wait (~30s max).
            LaunchedEffect(Unit) {
                if (StartupSplashGate.shownThisProcess) {
                    splashCatalogReady = true
                    return@LaunchedEffect
                }
                runCatching {
                    withContext(Dispatchers.IO) {
                        val sources = app.preferences.getSources()
                        if (sources.isEmpty()) return@withContext
                        app.repository.ensureCatalogLoaded(force = false)
                    }
                }
                splashCatalogReady = true
            }
            val play = { item: MediaItem -> playResolved(app.repository, item, startOver = false) }
            val playFromStart = { item: MediaItem -> playResolved(app.repository, item, startOver = true) }
            val playFav = { fav: FavoriteRef ->
                playResolved(
                    app.repository,
                    MediaItem(
                        id = fav.id,
                        name = fav.name,
                        streamUrl = fav.streamUrl,
                        categoryId = null,
                        kind = fav.kind,
                        logoUrl = fav.logoUrl
                    )
                )
            }

            if (!splashDone && !switchingLayout) {
                StartupSplash(ready = splashCatalogReady, statusMessage = if (splashCatalogReady) null else "Updating Live / Movies / Series...", onFinished = { splashDone = true })
                return@setContent
            }

            if (!layoutReady || switchingLayout) {
                // Animated indicator so Classic <-> Desktop recreate does not look frozen.
                // Keep this short path for theme-switch (splash already skipped via gate).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(androidx.compose.ui.graphics.Color(0xFF0B0F14)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = androidx.compose.ui.graphics.Color(0xFFFFB300)
                        )
                        Spacer(Modifier.height(16.dp))
                        androidx.compose.material3.Text(
                            text = if (switchingLayout) "Switching layoutâ€¦" else "Loading dataâ€¦",
                            color = androidx.compose.ui.graphics.Color(0xFFECEFF1)
                        )
                        Spacer(Modifier.height(6.dp))
                        androidx.compose.material3.Text(
                            text = if (switchingLayout) {
                                "Applying theme â€” almost ready"
                            } else {
                                "Preparing your catalog"
                            },
                            color = androidx.compose.ui.graphics.Color(0xFF78909C)
                        )
                    }
                }
                return@setContent
            }

            if (boundLayout == AppLayoutMode.DESKTOP) {
                DesktopAppRoot(
                    repository = app.repository,
                    onPlay = play,
                    onPlayFromStart = playFromStart,
                    onPlayFavorite = playFav
                )
            } else {
                TotalIptvProTheme(appearance = appearance, accent = accent) {
                    AppRoot(
                        repository = app.repository,
                        onPlay = play,
                        onPlayFromStart = playFromStart,
                        onPlayFavorite = playFav
                    )
                }
            }
        }
    }


    private fun playResolved(
        repository: CatalogRepository,
        item: MediaItem,
        startOver: Boolean = false
    ) {
        // Always re-bind to catalog by id so Live clicks cannot play a mismatched streamUrl
        // after category/sort list churn.
        val bound = repository.playableFrom(item)
        // Prefer the catalog poster id (series-123 / vod-456) so Continue watching
        // and detail Resume can map episode leaves back to the grid poster.
        val catalogId = when {
            item.id.startsWith("series-") && !item.id.startsWith("series-ep-") -> item.id
            bound.id.startsWith("series-") && !bound.id.startsWith("series-ep-") -> bound.id
            item.id.startsWith("vod-") -> item.id
            bound.id.startsWith("vod-") -> bound.id
            bound.id.startsWith("series-ep-") || item.id.startsWith("series-ep-") -> {
                val epId = if (bound.id.startsWith("series-ep-")) bound.id else item.id
                runCatching {
                    (application as TotalIptvProApp).watchProgress.get(epId)?.catalogId
                        ?: (application as TotalIptvProApp).watchProgress.forCatalogItem(epId)?.catalogId
                }.getOrNull()
            }
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

private sealed interface Screen {
    data object Onboarding : Screen
    data object Home : Screen
    data class Browse(val section: BrowseSection) : Screen
    data object Settings : Screen
    data object Guide : Screen
    data object Search : Screen
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppRoot(
    repository: CatalogRepository,
    onPlay: (MediaItem) -> Unit,
    onPlayFromStart: (MediaItem) -> Unit,
    onPlayFavorite: (FavoriteRef) -> Unit
) {
    val sources by repository.sources.collectAsState(initial = emptyList())
    var screen by remember { mutableStateOf<Screen?>(null) }
    var prefsReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        repository.sources.first()
        prefsReady = true
    }

    if (!prefsReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Loading dataâ€¦",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
            }
        }
        return
    }

    val route = screen ?: if (sources.isEmpty()) Screen.Onboarding else Screen.Home

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        when (val s = route) {
            Screen.Onboarding -> OnboardingScreen(
                repository = repository,
                onDone = { screen = Screen.Home }
            )
            Screen.Home -> HomeScreen(
                repository = repository,
                onOpenSection = { screen = Screen.Browse(it) },
                onOpenFavorite = onPlayFavorite,
                onOpenSettings = { screen = Screen.Settings },
                onAddSource = { screen = Screen.Onboarding },
                onOpenGuide = { screen = Screen.Guide },
                onOpenSearch = { screen = Screen.Search },
                onPlayItem = onPlay,
                onPlayFromStart = onPlayFromStart
            )
            is Screen.Browse -> BrowseScreen(
                section = s.section,
                repository = repository,
                onPlay = onPlay,
                    onPlayFromStart = onPlayFromStart,
                onPlayFavorite = onPlayFavorite,
                onBack = { screen = Screen.Home },
                onOpenGuide = { screen = Screen.Guide }
            )
            Screen.Settings -> SettingsScreen(
                repository = repository,
                onBack = { screen = Screen.Home },
                onClearedToOnboarding = { screen = Screen.Onboarding }
            )
            Screen.Guide -> EpgGuideScreen(
                repository = repository,
                onPlay = onPlay,
                onBack = { screen = Screen.Home }
            )
            Screen.Search -> SearchScreen(
                repository = repository,
                onPlay = onPlay,
                onPlayFromStart = onPlayFromStart,
                onBack = { screen = Screen.Home }
            )
        }
    }
}

