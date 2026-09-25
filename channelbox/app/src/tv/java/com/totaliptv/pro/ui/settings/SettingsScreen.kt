package com.totaliptv.pro.ui.settings

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Button
import androidx.tv.material3.Surface
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.PreferredPlayer
import com.totaliptv.pro.data.local.AppLayoutMode
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.update.AppUpdateChecker
import com.totaliptv.pro.data.update.UpdateCheckResult
import com.totaliptv.pro.ui.components.FocusableCard
import com.totaliptv.pro.ui.theme.FocusBorder
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.components.SectionTitle
import kotlinx.coroutines.launch

private class UrlFieldHolder(var text: String)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CatalogRepository,
    onBack: () -> Unit,
    onClearedToOnboarding: () -> Unit
) {
    val sources by repository.sources.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showPrivacy by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }
    val app = context.applicationContext as TotalIptvProApp
    val preferredPlayer by app.preferences.preferredPlayer.collectAsState(initial = PreferredPlayer.BUILTIN)
    val autoPip by app.preferences.autoPip.collectAsState(initial = AppPreferences.defaultAutoPip())
    val appearanceMode by app.preferences.appearanceMode.collectAsState(initial = AppearanceMode.DARK)
    val accentPreset by app.preferences.accentPreset.collectAsState(initial = AccentPreset.BLUE)
    val appLayoutMode by app.preferences.appLayoutMode.collectAsState(initial = AppLayoutMode.CLASSIC)
    val posterColumns by app.preferences.posterColumns.collectAsState(initial = 6)
    val updateBaseUrl by app.preferences.updateBaseUrl.collectAsState(
        initial = AppPreferences.DEFAULT_UPDATE_BASE_URL
    )
    var editingUpdateUrl by remember { mutableStateOf(false) }
    val urlHolder = remember(updateBaseUrl) { UrlFieldHolder(updateBaseUrl) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }

    BackHandler {
        when {
            showPrivacy -> showPrivacy = false
            editingUpdateUrl -> editingUpdateUrl = false
            else -> onBack()
        }
    }

    if (showPrivacy) {
        PrivacyPolicyScreen(onBack = { showPrivacy = false })
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Button(onClick = onBack) { Text("Back") }
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )

        LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                SectionTitle("App update")
                Text(
                    text = "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
            item {
                FocusableCard(
                    title = if (editingUpdateUrl) "Update server (editing)" else "Update server",
                    subtitle = updateBaseUrl,
                    onClick = {
                        urlHolder.text = updateBaseUrl
                        editingUpdateUrl = !editingUpdateUrl
                    }
                )
            }
            if (editingUpdateUrl) {
                item {
                    AndroidView(
                        factory = { ctx ->
                            val density = ctx.resources.displayMetrics.density
                            fun dp(v: Int) = (v * density).toInt()
                            LinearLayout(ctx).apply {
                                orientation = LinearLayout.VERTICAL
                                setPadding(dp(4), dp(4), dp(4), dp(4))
                                addView(TextView(ctx).apply {
                                    text = "PC shelf URL (http.server on :8765). Example: http://192.168.4.37:8765/"
                                    setTextColor(AndroidColor.parseColor("#B0BEC5"))
                                    textSize = 13f
                                })
                                addView(EditText(ctx).apply {
                                    setText(urlHolder.text)
                                    setTextColor(AndroidColor.WHITE)
                                    setHintTextColor(AndroidColor.parseColor("#78909C"))
                                    setBackgroundColor(AndroidColor.parseColor("#1A2332"))
                                    setPadding(dp(12), dp(10), dp(12), dp(10))
                                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                                    setSingleLine()
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    addTextChangedListener(object : android.text.TextWatcher {
                                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                            urlHolder.text = s?.toString().orEmpty()
                                        }
                                        override fun afterTextChanged(s: android.text.Editable?) {}
                                    })
                                })
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 88.dp)
                    )
                }
                item {
                    FocusableCard(
                        title = "Save update server URL",
                        subtitle = "Default: ${AppPreferences.DEFAULT_UPDATE_BASE_URL}",
                        onClick = {
                            scope.launch {
                                val normalized = AppUpdateChecker.normalizeBase(urlHolder.text)
                                app.preferences.setUpdateBaseUrl(normalized)
                                editingUpdateUrl = false
                                pendingInstall = null
                                updateStatus = "Update server saved"
                                Toast.makeText(context, "Saved $normalized", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
            item {
                FocusableCard(
                    title = when {
                        updateBusy && pendingInstall == null -> "Checking…"
                        updateBusy && pendingInstall != null -> "Downloading…"
                        pendingInstall != null ->
                            "Install update ${pendingInstall!!.manifest.versionName}"
                        else -> "Check for update"
                    },
                    subtitle = updateStatus
                        ?: "Fetches version.json from your PC download shelf, then installs the APK",
                    onClick = {
                        if (updateBusy) return@FocusableCard
                        val activity = context as? Activity
                        scope.launch {
                            val ready = pendingInstall
                            if (ready != null) {
                                updateBusy = true
                                updateStatus = "Downloading ${ready.manifest.versionName}…"
                                try {
                                    if (activity != null && !AppUpdateChecker.canInstallPackages(context)) {
                                        updateStatus = "Allow install unknown apps, then tap Install again"
                                        Toast.makeText(
                                            context,
                                            "Enable install unknown apps for Total IPTV Pro",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        AppUpdateChecker.openUnknownSourcesSettings(activity)
                                        return@launch
                                    }
                                    val file = AppUpdateChecker.downloadApk(context, ready.apkUrl)
                                    updateStatus = "Opening installer…"
                                    AppUpdateChecker.launchInstaller(context, file)
                                } catch (t: Throwable) {
                                    updateStatus = "Download failed: ${t.message ?: t.javaClass.simpleName}"
                                    Toast.makeText(context, updateStatus, Toast.LENGTH_LONG).show()
                                } finally {
                                    updateBusy = false
                                }
                                return@launch
                            }

                            updateBusy = true
                            updateStatus = "Checking…"
                            pendingInstall = null
                            when (val result = AppUpdateChecker.check(updateBaseUrl)) {
                                is UpdateCheckResult.UpToDate -> {
                                    updateStatus = "Up to date (${BuildConfig.VERSION_NAME})"
                                    Toast.makeText(context, "Up to date", Toast.LENGTH_SHORT).show()
                                }
                                is UpdateCheckResult.Available -> {
                                    pendingInstall = result
                                    updateStatus =
                                        "Update available: ${result.manifest.versionName} (code ${result.manifest.versionCode}). Tap to install."
                                    Toast.makeText(
                                        context,
                                        "Update ${result.manifest.versionName} available",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                is UpdateCheckResult.Failed -> {
                                    updateStatus = "Check failed: ${result.message}"
                                    Toast.makeText(context, updateStatus, Toast.LENGTH_LONG).show()
                                }
                            }
                            updateBusy = false
                        }
                    }
                )
            }



            item { SectionTitle("App layout") }
            item {
                Text(
                    text = "Classic keeps the current TV UI. Desktop uses the amber sidebar look.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppLayoutMode.entries.forEach { mode ->
                        ThemeChoiceCard(
                            label = mode.label,
                            subtitle = if (mode == AppLayoutMode.CLASSIC) "Current TV UI (default)" else "Amber sidebar / Ubuntu look",
                            selected = appLayoutMode == mode,
                            swatch = null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    app.preferences.setAppLayoutMode(mode)
                                    Toast.makeText(context, "Layout: ${mode.label}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            item { SectionTitle("Theme") }
            item {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppearanceMode.entries.forEach { mode ->
                        ThemeChoiceCard(
                            label = mode.label,
                            subtitle = if (mode == AppearanceMode.DARK) "Cinema (default)" else "Bright & readable",
                            selected = appearanceMode == mode,
                            swatch = null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    app.preferences.setAppearanceMode(mode)
                                    Toast.makeText(context, "Appearance: ${mode.label}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
            item {
                Text(
                    text = "Accent color",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    AccentPreset.entries.forEach { preset ->
                        ThemeAccentCard(
                            label = preset.label,
                            color = preset.primary,
                            selected = accentPreset == preset,
                            onClick = {
                                scope.launch {
                                    app.preferences.setAccentPreset(preset)
                                    Toast.makeText(context, "Accent: ${preset.label}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            item { SectionTitle("Posters per row") }
            item {
                Text(
                    text = "Home / browse grids on Classic and Desktop: 5, 6, 8, or 11",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AppPreferences.POSTER_COLUMN_OPTIONS.forEach { cols ->
                        ThemeChoiceCard(
                            label = "$cols",
                            subtitle = "per row",
                            selected = posterColumns == cols,
                            swatch = null,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                scope.launch {
                                    app.preferences.setPosterColumns(cols)
                                    Toast.makeText(context, "Posters per row: $cols", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }

            item { SectionTitle("Personal DVR") }
            item {
                val dvr = com.totaliptv.pro.dvr.DvrActions.recorder(context)
                val snap by dvr.snapshot.collectAsState()
                FocusableCard(
                    title = if (snap.active != null) "Recording now — tap to stop" else "Recordings on this TV",
                    subtitle = snap.active?.let { "${it.channelName} · ${it.title}" }
                        ?: snap.recordingsDir,
                    onClick = {
                        if (snap.active != null) com.totaliptv.pro.dvr.DvrActions.stop(context)
                    }
                )
            }
            item {
                Text(
                    "Record Live/Guide (HLS capture), or download a movie or series episode from detail / the player. Library shows type. Files stay on this Shield/TV — not a shared NAS. No ffmpeg-kit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
            item { SectionTitle("Player") }
            item {
                FocusableCard(
                    title = "Preferred player: ${preferredPlayer.label}",
                    subtitle = "Built-in always starts ExoPlayer. Ask suggests VLC on decode fail. VLC is a hand-off button, not the only player. Tap to cycle.",
                    onClick = {
                        scope.launch {
                            val next = PreferredPlayer.next(preferredPlayer)
                            app.preferences.setPreferredPlayer(next)
                            Toast.makeText(context, "Preferred player: ${next.label}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
            item {
                FocusableCard(
                    title = if (autoPip) "Picture-in-Picture on Home: On" else "Picture-in-Picture on Home: Off",
                    subtitle = "Off by default on TV. When on, pressing Home shrinks playback into a small window.",
                    onClick = {
                        scope.launch {
                            val next = !autoPip
                            app.preferences.setAutoPip(next)
                            Toast.makeText(
                                context,
                                if (next) "Home button enters Picture-in-Picture" else "Home button leaves playback",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }

            item { SectionTitle("Catalog") }
            item {
                FocusableCard(
                    title = if (refreshing) "Refreshing..." else "Refresh data",
                    subtitle = refreshNote
                        ?: "Re-download live, movies & series from your provider (keeps login)",
                    onClick = {
                        if (!refreshing) {
                            scope.launch {
                                refreshing = true
                                refreshNote = "Refreshing..."
                                Toast.makeText(context, "Refreshing...", Toast.LENGTH_SHORT).show()
                                try {
                                    repository.ensureCatalogLoaded(force = true)
                                    val warn = repository.lastWarning
                                    if (!warn.isNullOrBlank()) {
                                        refreshNote = warn
                                        Toast.makeText(context, warn, Toast.LENGTH_LONG).show()
                                    } else {
                                        refreshNote = "Data updated"
                                        Toast.makeText(context, "Data updated", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (t: Throwable) {
                                    val msg = t.message ?: "Refresh failed"
                                    refreshNote = msg
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                } finally {
                                    refreshing = false
                                }
                            }
                        }
                    }
                )
            }

            item { SectionTitle("Playlists / sources") }
            items(sources, key = { it.id }) { source ->
                FocusableCard(
                    title = source.name,
                    subtitle = "${source.type} - tap to remove",
                    onClick = {
                        scope.launch {
                            repository.removeSource(source.id)
                            val left = sources.count { it.id != source.id }
                            if (left == 0) onClearedToOnboarding()
                        }
                    }
                )
            }
            item { SectionTitle("About") }
            item {
                Text(
                    text = "Developed by Bill Foster",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "\u00A9 2026 Bill Foster. All rights reserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                Text(
                    text = "Total IPTV Pro ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            item {
                FocusableCard(
                    title = "Privacy Policy",
                    subtitle = "Required before Play Store submit",
                    onClick = { showPrivacy = true }
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            repository.clearAllData()
                            onClearedToOnboarding()
                        }
                    },
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text("Clear all data")
                }
            }
        }
    }
}



@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeChoiceCard(
    label: String,
    subtitle: String,
    selected: Boolean,
    swatch: Color?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .height(88.dp)
            .border(
                width = when {
                    focused -> 3.dp
                    selected -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> FocusBorder
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.border
                },
                shape = shape
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            else MaterialTheme.colorScheme.surface,
            focusedContainerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface,
            pressedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (swatch != null) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(swatch, CircleShape)
                            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ThemeAccentCard(
    label: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier
            .width(132.dp)
            .height(100.dp)
            .border(
                width = when {
                    focused -> 3.dp
                    selected -> 2.dp
                    else -> 1.dp
                },
                color = when {
                    focused -> FocusBorder
                    selected -> color
                    else -> MaterialTheme.colorScheme.border
                },
                shape = shape
            ),
        shape = ClickableSurfaceDefaults.shape(
            shape = shape,
            focusedShape = shape,
            pressedShape = shape
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) color.copy(alpha = 0.28f)
            else MaterialTheme.colorScheme.surface,
            focusedContainerColor = if (selected) color.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface,
            pressedContainerColor = color.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.45f), CircleShape)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(28.dp)) {
        Button(onClick = onBack) { Text("Back") }
        Text(
            "Privacy Policy",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Text(
            text = """
                Last updated: September 5, 2026
                Developer: Bill Foster

                Total IPTV Pro is a player for playlists and credentials YOU supply (M3U or Xtream). We do not sell your personal information and we do not run a content server.

                On your device the App may store: source settings (including Xtream login), favorites, preferences, and cached listings/artwork/EPG from your provider.

                Network requests go to the servers you configure so the App can load catalogs, posters, TV guide data, and streams. Your provider's own policy applies to those services.

                We do not require a Total IPTV Pro account. Clear data in Settings or uninstall to remove local data.

                Full policy on this PC:
                C:\Users\billf\ChannelBox\privacy-policy.html

                Before Google Play: host that HTML at a public HTTPS URL, add your contact email, and paste the URL into Play Console.
            """.trimIndent(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f)
        )
    }
}
