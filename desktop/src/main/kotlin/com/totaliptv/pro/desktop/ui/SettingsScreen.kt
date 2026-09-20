package com.totaliptv.pro.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.desktop.AppVersion
import com.totaliptv.pro.desktop.util.AppPaths
import com.totaliptv.pro.desktop.data.SavedPrefs
import com.totaliptv.pro.desktop.data.SourceType
import com.totaliptv.pro.desktop.player.StreamPlayer
import com.totaliptv.pro.desktop.update.AppUpdateManager
import com.totaliptv.pro.desktop.update.AppUpdatePaths
import com.totaliptv.pro.desktop.update.UpdatePhase
import com.totaliptv.pro.desktop.update.UpdateUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    prefs: SavedPrefs,
    playingTitle: String?,
    onSavePrefs: (SavedPrefs) -> Unit,
    onChangeSource: () -> Unit,
    onStop: () -> Unit
) {
    val available = remember { StreamPlayer.availablePlayers() }
    var player by remember(prefs.preferredPlayer) { mutableStateOf(prefs.preferredPlayer) }
    var theme by remember(prefs.themeMode) { mutableStateOf(prefs.themeMode) }
    var columns by remember(prefs.posterColumns) {
        mutableStateOf(prefs.posterColumns.let { if (it in setOf(5, 6, 8, 11)) it else 6 })
    }
    var guideStyle by remember(prefs.guideStyle) {
        mutableStateOf(prefs.guideStyle.let { if (it == "classic") "classic" else "current" })
    }

    val scope = rememberCoroutineScope()
    val updater = remember { AppUpdateManager() }
    var shelfUrl by remember(prefs.updateShelfUrl) {
        mutableStateOf(
            prefs.updateShelfUrl.ifBlank { AppUpdatePaths.DEFAULT_SHELF }
        )
    }
    var updateState by remember {
        mutableStateOf(
            UpdateUiState(
                localVersionName = AppVersion.VERSION_NAME,
                localVersionCode = AppVersion.VERSION_CODE
            )
        )
    }

    fun persist(
        nextPlayer: String = player,
        nextTheme: String = theme,
        nextColumns: Int = columns,
        nextShelf: String = shelfUrl,
        nextGuide: String = guideStyle
    ) {
        player = nextPlayer
        theme = nextTheme
        columns = nextColumns
        shelfUrl = nextShelf
        guideStyle = if (nextGuide == "classic") "classic" else "current"
        onSavePrefs(
            prefs.copy(
                preferredPlayer = nextPlayer,
                themeMode = nextTheme,
                posterColumns = nextColumns,
                updateShelfUrl = AppUpdateManager.normalizeShelf(nextShelf),
                guideStyle = guideStyle
            )
        )
    }

    fun checkUpdate() {
        persist(nextShelf = shelfUrl)
        scope.launch {
            updateState = updateState.copy(
                phase = UpdatePhase.Checking,
                message = "Checking for update…"
            )
            val result = withContext(Dispatchers.IO) {
                updater.check(shelfUrl)
            }
            updateState = result
        }
    }

    fun downloadUpdate() {
        val remote = updateState.remote
        val base = updateState.shelfBaseUrl
        if (remote == null || base.isNullOrBlank()) {
            updateState = updateState.copy(
                phase = UpdatePhase.Error,
                message = "No update metadata — check for update first"
            )
            return
        }
        scope.launch {
            updateState = updateState.copy(
                phase = UpdatePhase.Downloading,
                message = "Downloading ${remote.versionName}…"
            )
            val result = withContext(Dispatchers.IO) {
                updater.downloadAndInstall(remote, base)
            }
            updateState = result
        }
    }

    fun restartNow() {
        try {
            updater.restartNow(updateState.installedBinary)
        } catch (t: Throwable) {
            updateState = updateState.copy(
                phase = UpdatePhase.Error,
                message = "Restart failed: ${t.message ?: t.javaClass.simpleName}. " +
                    "Relaunch from the app menu or: ${updateState.installedBinary ?: AppUpdatePaths.defaultRelaunchHint()}"
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Settings, null, tint = TipBlue)
            Spacer(Modifier.width(8.dp))
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TipOnBg, modifier = Modifier.weight(1f))
            if (playingTitle != null) {
                TextButton(onClick = onStop) { Text("Stop player") }
            }
        }
        Spacer(Modifier.height(16.dp))

        SettingsCard(title = "Playback") {
            Text("Preferred player", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Text(
                "Used when you play Live / Movies / Series. Auto picks the first available.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            val options = listOf("auto") + available.ifEmpty { listOf("vlc", "mpv", "ffplay") }
            options.distinct().forEach { opt ->
                val label = when (opt) {
                    "auto" -> "Auto (VLC → mpv → ffplay)"
                    else -> opt.uppercase()
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (player == opt) TipBlue.copy(alpha = 0.2f) else TipSurfaceAlt)
                        .clickable { persist(nextPlayer = opt) }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = player == opt,
                        onClick = { persist(nextPlayer = opt) },
                        colors = RadioButtonDefaults.colors(selectedColor = TipBlue)
                    )
                    Text(label, color = TipOnBg)
                }
            }
            if (available.isEmpty()) {
                Text(
                    if (AppPaths.isWindows) {
                        "No player detected. Install VLC from videolan.org (recommended), or add mpv/ffplay to PATH."
                    } else {
                        "No player detected. Install vlc (recommended): sudo apt install vlc"
                    },
                    color = TipAccent,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Text("Detected: ${available.joinToString(", ")}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(10.dp))
            Text("Series next episode", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Text(
                if (AppPaths.isWindows) {
                    "Windows plays one episode URL (VLC goes fullscreen over this window). Use the floating Next episode button, Ctrl+Right, or the Media Next key. VLC’s own Next stays on the same episode. Debug: ${AppPaths.configDir.resolve("playback-debug.log")}"
                } else {
                    "Linux still launches the remaining-episode M3U so VLC Next / end-of-file advance inside the player. Ctrl+Right also skips when this window is focused."
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = "Appearance") {
            Text("Theme", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TipChoiceChip(selected = theme == "dark", label = "Dark") {
                    persist(nextTheme = "dark")
                }
                TipChoiceChip(selected = theme == "light", label = "Light") {
                    persist(nextTheme = "light")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Poster grid columns (Movies / Series)", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Text("How many posters across in the browse grid.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "More across = smaller posters. Fewer = closer to Home size.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TipChoiceChip(selected = columns == 5, label = "5 across") { persist(nextColumns = 5) }
                TipChoiceChip(selected = columns == 6, label = "6 across") { persist(nextColumns = 6) }
                TipChoiceChip(selected = columns == 8, label = "8 across") { persist(nextColumns = 8) }
                TipChoiceChip(selected = columns == 11, label = "11 across") { persist(nextColumns = 11) }
            }
            Spacer(Modifier.height(14.dp))
            Text("TV Guide layout", style = MaterialTheme.typography.titleMedium, color = TipOnBg)
            Text(
                "Current keeps the timeline view. Classic matches the Shield channel list + schedule.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TipChoiceChip(selected = guideStyle == "current", label = "Current") {
                    persist(nextGuide = "current")
                }
                TipChoiceChip(selected = guideStyle == "classic", label = "Classic") {
                    persist(nextGuide = "classic")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = "App updates") {
            Text(
                "Installed: ${AppVersion.VERSION_NAME} (${AppVersion.VERSION_CODE})",
                style = MaterialTheme.typography.titleMedium,
                color = TipOnBg
            )
            Text(
                "Works on Bigboybill (Windows) and GTR (Linux). Use the same LAN shelf URL; Shield updates from Settings → App updates on the TV.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = shelfUrl,
                onValueChange = { shelfUrl = it },
                label = { Text("Update shelf URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = TipBlue,
                    cursorColor = TipBlue
                )
            )
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    shelfUrl = AppUpdatePaths.DEFAULT_SHELF
                    persist(nextShelf = AppUpdatePaths.DEFAULT_SHELF)
                }
            ) {
                Text("Reset to default (${AppUpdatePaths.DEFAULT_SHELF})")
            }
            Spacer(Modifier.height(8.dp))
            val busy = updateState.phase == UpdatePhase.Checking ||
                updateState.phase == UpdatePhase.Downloading
            Button(
                onClick = { checkUpdate() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TipBlue,
                    contentColor = TipOnAmber,
                    disabledContainerColor = TipSurfaceAlt,
                    disabledContentColor = TipMuted
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.SystemUpdate, null, Modifier.size(18.dp), tint = TipOnAmber)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (updateState.phase) {
                        UpdatePhase.Checking -> "Checking…"
                        UpdatePhase.Downloading -> "Downloading…"
                        else -> "Check for update"
                    },
                    color = TipOnAmber,
                    fontWeight = FontWeight.Bold
                )
            }
            if (updateState.phase == UpdatePhase.Available) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { downloadUpdate() },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipBlueDark,
                        contentColor = TipOnAmber
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download & install update", color = TipOnAmber, fontWeight = FontWeight.Bold)
                }
            }
            if (updateState.phase == UpdatePhase.ReadyToRestart) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { restartNow() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TipBlueDark,
                        contentColor = TipOnAmber
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Restart now", color = TipOnAmber, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Or relaunch from the app menu / desktop icon after quitting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TipMuted
                )
            }
            if (updateState.message.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                val color = when (updateState.phase) {
                    UpdatePhase.Error -> MaterialTheme.colorScheme.error
                    UpdatePhase.UpToDate -> TipOnBg
                    UpdatePhase.Available, UpdatePhase.ReadyToRestart -> TipBlue
                    else -> TipMuted
                }
                Text(
                    updateState.message,
                    color = color,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                updateState.shelfBaseUrl?.let { base ->
                    Text("Shelf: $base", color = TipMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = "Source / account") {
            val kind = try {
                SourceType.valueOf(prefs.sourceType)
            } catch (_: Exception) {
                SourceType.XTREAM
            }
            Text(
                when (kind) {
                    SourceType.XTREAM -> "Xtream Codes"
                    SourceType.M3U -> "M3U playlist"
                },
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            when (kind) {
                SourceType.XTREAM -> {
                    Text("Server: ${prefs.xtreamBaseUrl.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("User: ${prefs.xtreamUsername.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Password: ${if (prefs.xtreamPassword.isNotEmpty()) "••••••••" else "—"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                SourceType.M3U -> {
                    Text(
                        "Playlist: ${prefs.m3uUrl.ifBlank { "—" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Saved login stays until you change source. Update in the sidebar reloads the catalog without re-entering credentials.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onChangeSource,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TipAccent)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Change source / clear login")
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard(title = "Copyright / About") {
            Text(
                "Total IPTV Pro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TipOnBg
            )
            Text(
                "Version ${AppVersion.VERSION_NAME} (${AppVersion.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "© 2026 Bill Foster",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = TipOnBg
            )
            Text(
                "Developer: Bill Foster",
                style = MaterialTheme.typography.bodyMedium,
                color = TipOnBg
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "All rights reserved. This application and its design are the property of the developer. Unauthorized copying, distribution, or commercial use without permission is prohibited.",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Total IPTV Pro is provided as a media player client. Content availability depends on the playlist or service you connect; the developer does not provide broadcast content.",
                style = MaterialTheme.typography.bodyMedium,
                color = TipMuted
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Prefs file: ${AppPaths.prefsHintPath()}",
            style = MaterialTheme.typography.bodyMedium,
            color = TipMuted
        )
    }
}

@Composable
private fun TipChoiceChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val bg = if (selected) TipBlue else TipSurfaceAlt
    val fg = if (selected) TipOnAmber else TipOnBg
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(
                BorderStroke(1.dp, if (selected) TipBlueDark else TipMuted.copy(alpha = 0.45f)),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = fg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(TipSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TipBlue)
        Spacer(Modifier.height(4.dp))
        content()
    }
}
