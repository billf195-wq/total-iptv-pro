package com.totaliptv.pro2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.totaliptv.pro2.AppUiState
import com.totaliptv.pro2.NavSection
import com.totaliptv.pro2.data.MediaItem
import com.totaliptv.pro2.data.ResumeStore
import com.totaliptv.pro2.data.SavedPrefs

@Composable
fun AppRoot(
    state: AppUiState,
    onSection: (NavSection) -> Unit,
    onConnect: (SavedPrefs) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (String) -> Unit,
    onPosterColumns: (Int) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onCloseSeries: () -> Unit,
    onNeedEpg: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onResumeEntry: (ResumeStore.ResumeEntry) -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit
) {
    when {
        state.showOnboarding -> OnboardingScreen(
            initial = state.prefs,
            busy = state.loading,
            error = state.error,
            onConnect = onConnect
        )
        state.loading && state.catalog == null -> {
            Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = TipAmber)
                    Spacer(Modifier.height(TipDimens.dp(16)))
                    Text("Loading catalog…", color = TipGoldText)
                    Text("Using saved login", color = TipGoldMuted, fontSize = TipDimens.BodyMediumSp)
                }
            }
        }
        state.catalog == null && state.error != null -> {
            Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(TipDimens.dp(24))) {
                    Text("Could not load catalog", color = TipGoldText, fontSize = TipDimens.sp(22), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(TipDimens.dp(8)))
                    Text(state.error ?: "", color = androidx.compose.ui.graphics.Color(0xFFFF8A80))
                    Spacer(Modifier.height(TipDimens.dp(16)))
                    AmberButton("Update", onClick = onRefresh)
                    Spacer(Modifier.height(TipDimens.dp(8)))
                    AmberButton("Change source", onClick = onChangeSource)
                }
            }
        }
        state.catalog != null -> {
            MainShell(
                state = state,
                onSection = onSection,
                onRefresh = onRefresh,
                onChangeSource = onChangeSource,
                onSearch = onSearch,
                onCategory = onCategory,
                onSort = onSort,
                onPosterColumns = onPosterColumns,
                onOpenSeries = onOpenSeries,
                onCloseSeries = onCloseSeries,
                onNeedEpg = onNeedEpg,
                onPlay = onPlay,
                onResumeEntry = onResumeEntry,
                onCheckAppUpdate = onCheckAppUpdate,
                onDownloadAppUpdate = onDownloadAppUpdate,
                onInstallAppUpdate = onInstallAppUpdate
            )
        }
        else -> Box(Modifier.fillMaxSize().background(TipBg), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = TipAmber)
        }
    }
}

@Composable
private fun MainShell(
    state: AppUiState,
    onSection: (NavSection) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    onSearch: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSort: (String) -> Unit,
    onPosterColumns: (Int) -> Unit,
    onOpenSeries: (MediaItem) -> Unit,
    onCloseSeries: () -> Unit,
    onNeedEpg: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
    onResumeEntry: (ResumeStore.ResumeEntry) -> Unit,
    onCheckAppUpdate: () -> Unit,
    onDownloadAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(TipBg)) {
        TopBanner()
        // weight(1f): remaining space under banner — fillMaxSize() here clipped the top (desktop fix)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Sidebar(
                section = state.section,
                refreshing = state.refreshing,
                onSection = onSection,
                onRefresh = onRefresh,
                onChangeSource = onChangeSource,
                modifier = Modifier
                    .width(TipDimens.SidebarWidth)
                    .fillMaxHeight()
                    .background(TipSurface)
            )
            Column(Modifier.weight(1f).fillMaxHeight().padding(TipDimens.ContentPad)) {
                state.statusMessage?.let {
                    Text(it, color = TipAccent, fontSize = TipDimens.BodyMediumSp, modifier = Modifier.padding(bottom = TipDimens.dp(8)))
                }
                when (state.section) {
                    NavSection.HOME -> HomePane(
                        catalog = state.catalog!!,
                        resume = state.resumeEntries,
                        onPlay = onPlay,
                        onOpenSeries = onOpenSeries,
                        onResume = onResumeEntry
                    )
                    NavSection.LIVE -> LivePane(
                        catalog = state.catalog!!,
                        search = state.searchQuery,
                        categoryId = state.selectedCategoryId,
                        onSearch = onSearch,
                        onCategory = onCategory,
                        onPlay = onPlay
                    )
                    NavSection.MOVIES -> BrowseGridPane(
                        title = "Movies",
                        items = state.catalog!!.vodItems,
                        categories = state.catalog!!.vodCategories,
                        search = state.searchQuery,
                        categoryId = state.selectedCategoryId,
                        sort = state.browseSort,
                        columns = state.prefs.posterColumns,
                        onSearch = onSearch,
                        onCategory = onCategory,
                        onSort = onSort,
                        onClick = onPlay
                    )
                    NavSection.SERIES -> {
                        if (state.seriesDetail != null || state.seriesLoading || state.seriesError != null) {
                            SeriesDetailPane(
                                detail = state.seriesDetail,
                                loading = state.seriesLoading,
                                error = state.seriesError,
                                onBack = onCloseSeries,
                                onPlay = onPlay
                            )
                        } else {
                            BrowseGridPane(
                                title = "Series",
                                items = state.catalog!!.seriesItems,
                                categories = state.catalog!!.seriesCategories,
                                search = state.searchQuery,
                                categoryId = state.selectedCategoryId,
                                sort = state.browseSort,
                                columns = state.prefs.posterColumns,
                                onSearch = onSearch,
                                onCategory = onCategory,
                                onSort = onSort,
                                onClick = onOpenSeries
                            )
                        }
                    }
                    NavSection.GUIDE -> GuidePane(
                        catalog = state.catalog!!,
                        epgByStreamId = state.epgByStreamId,
                        epgLoadingIds = state.epgLoadingIds,
                        categoryId = state.selectedCategoryId,
                        onCategory = onCategory,
                        onNeedEpg = onNeedEpg,
                        onPlay = onPlay
                    )
                    NavSection.SETTINGS -> SettingsPane(
                        prefs = state.prefs,
                        catalog = state.catalog!!,
                        update = state.update,
                        onPosterColumns = onPosterColumns,
                        onRefresh = onRefresh,
                        onChangeSource = onChangeSource,
                        onCheckAppUpdate = onCheckAppUpdate,
                        onDownloadAppUpdate = onDownloadAppUpdate,
                        onInstallAppUpdate = onInstallAppUpdate
                    )
                }
            }
        }
    }
}

@Composable
private fun Sidebar(
    section: NavSection,
    refreshing: Boolean,
    onSection: (NavSection) -> Unit,
    onRefresh: () -> Unit,
    onChangeSource: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        NavSection.HOME to "Home",
        NavSection.LIVE to "Live",
        NavSection.MOVIES to "Movies",
        NavSection.SERIES to "Series",
        NavSection.GUIDE to "TV Guide",
        NavSection.SETTINGS to "Settings"
    )
    Column(
        modifier.padding(TipDimens.SidebarPad),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(TipDimens.NavGap)
    ) {
        Text("TOTAL IPTV PRO", color = TipAmber, fontWeight = FontWeight.Bold, fontSize = TipDimens.BrandSp)
        Text("Android TV", color = TipGoldMuted, fontSize = TipDimens.SubBrandSp)
        Spacer(Modifier.height(TipDimens.dp(12)))
        items.forEach { (sec, label) ->
            val selected = section == sec
            TipFocusable(
                onClick = { onSection(sec) },
                modifier = Modifier.fillMaxWidth()
            ) { focused ->
                Text(
                    label,
                    color = when {
                        selected -> TipAmber
                        focused -> TipAccent
                        else -> TipGoldText
                    },
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = TipDimens.BodyLargeSp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selected -> TipAmber.copy(alpha = 0.25f)
                                focused -> TipSurfaceAlt
                                else -> TipSurfaceAlt.copy(alpha = 0.55f)
                            },
                            androidx.compose.foundation.shape.RoundedCornerShape(TipDimens.NavCorner)
                        )
                        .padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.NavItemPadV)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        TipFocusable(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { focused ->
            Text(
                if (refreshing) "Updating…" else "Update",
                color = TipOnAmber,
                fontWeight = FontWeight.Bold,
                fontSize = TipDimens.LabelLargeSp,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (focused) TipAccent else TipAmber, androidx.compose.foundation.shape.RoundedCornerShape(TipDimens.dp(8)))
                    .padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.NavItemPadV)
            )
        }
        TipFocusable(onClick = onChangeSource, modifier = Modifier.fillMaxWidth()) { focused ->
            Text(
                "Change source",
                color = if (focused) TipAccent else TipGoldMuted,
                fontSize = TipDimens.LabelLargeSp,
                modifier = Modifier.padding(horizontal = TipDimens.NavItemPadH, vertical = TipDimens.dp(10))
            )
        }
    }
}
