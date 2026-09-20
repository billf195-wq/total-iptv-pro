package com.totaliptv.pro.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.PreferredPlayer
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.update.AppUpdateChecker
import com.totaliptv.pro.data.update.UpdateCheckResult
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import kotlinx.coroutines.launch

@Composable
fun PhoneSettingsScreen(
    repository: CatalogRepository,
    onAddSource: () -> Unit,
    onClearedToOnboarding: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val context = LocalContext.current
    val app = context.applicationContext as TotalIptvProApp
    val scope = rememberCoroutineScope()
    val sources by repository.sources.collectAsState(initial = emptyList())
    val preferredPlayer by app.preferences.preferredPlayer.collectAsState(initial = PreferredPlayer.BUILTIN)
    val appearance by app.preferences.appearanceMode.collectAsState(initial = AppearanceMode.DARK)
    val accent by app.preferences.accentPreset.collectAsState(initial = AccentPreset.BLUE)
    val updateBaseUrl by app.preferences.updateBaseUrl.collectAsState(
        initial = AppPreferences.DEFAULT_UPDATE_BASE_URL
    )

    var refreshing by remember { mutableStateOf(false) }
    var refreshNote by remember { mutableStateOf<String?>(null) }

    var editingUpdateUrl by remember { mutableStateOf(false) }
    var updateUrlDraft by remember(updateBaseUrl) { mutableStateOf(updateBaseUrl) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var pendingInstall by remember { mutableStateOf<UpdateCheckResult.Available?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = OnCinema
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Total IPTV Pro Phone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "App update",
            style = MaterialTheme.typography.titleMedium,
            color = OnCinema,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Phone channel only — does not use the TV Shield shelf.",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Update server: $updateBaseUrl",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                updateUrlDraft = updateBaseUrl
                editingUpdateUrl = !editingUpdateUrl
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (editingUpdateUrl) "Cancel edit server URL" else "Edit update server URL")
        }
        if (editingUpdateUrl) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = updateUrlDraft,
                onValueChange = { updateUrlDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("PC shelf URL") },
                supportingText = {
                    Text("Default: ${AppPreferences.DEFAULT_UPDATE_BASE_URL}")
                }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        val normalized = AppUpdateChecker.normalizeBase(updateUrlDraft)
                        app.preferences.setUpdateBaseUrl(normalized)
                        editingUpdateUrl = false
                        pendingInstall = null
                        updateStatus = "Update server saved"
                        Toast.makeText(context, "Saved $normalized", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save update server URL")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (updateBusy) return@Button
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
                                    "Enable install unknown apps for Total IPTV Pro Phone",
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
            },
            enabled = !updateBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    updateBusy && pendingInstall == null -> "Checking…"
                    updateBusy && pendingInstall != null -> "Downloading…"
                    pendingInstall != null ->
                        "Install update ${pendingInstall!!.manifest.versionName}"
                    else -> "Check for update"
                }
            )
        }
        if (updateStatus != null) {
            Spacer(Modifier.height(6.dp))
            Text(updateStatus!!, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (pendingInstall != null) {
            TextButton(onClick = { pendingInstall = null; updateStatus = null }) {
                Text("Dismiss update")
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Catalog",
            style = MaterialTheme.typography.titleMedium,
            color = OnCinema,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (refreshing) return@Button
                scope.launch {
                    refreshing = true
                    refreshNote = "Refreshing…"
                    Toast.makeText(context, "Refreshing…", Toast.LENGTH_SHORT).show()
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
            },
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (refreshing) "Refreshing…" else "Refresh data")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            refreshNote
                ?: "Re-download live, movies & series from your provider (keeps login)",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(24.dp))
        Text("Sources", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (sources.isEmpty()) {
            Text("No playlist yet", color = OnCinemaMuted)
        } else {
            sources.forEach { src ->
                Text("• ${src.name} (${src.type})", color = OnCinema, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onAddSource, modifier = Modifier.fillMaxWidth()) {
            Text("Add / replace source")
        }

        Spacer(Modifier.height(24.dp))
        Text("Personal DVR", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            com.totaliptv.pro.dvr.DvrActions.recorder(context).recordingsDir().absolutePath,
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Recordings save on this phone only. Open the Recordings tab to play or delete.",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(24.dp))
        Text("Player", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            PreferredPlayer.entries.forEach { p ->
                FilterChip(
                    selected = preferredPlayer == p,
                    onClick = { scope.launch { app.preferences.setPreferredPlayer(p) } },
                    label = { Text(p.label) }
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("Appearance", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppearanceMode.entries.forEach { mode ->
                FilterChip(
                    selected = appearance == mode,
                    onClick = { scope.launch { app.preferences.setAppearanceMode(mode) } },
                    label = { Text(mode.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Accent", style = MaterialTheme.typography.titleSmall, color = OnCinemaMuted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AccentPreset.entries.forEach { preset ->
                FilterChip(
                    selected = accent == preset,
                    onClick = { scope.launch { app.preferences.setAccentPreset(preset) } },
                    label = { Text(preset.label) }
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    repository.clearAllData()
                    onClearedToOnboarding()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Clear all data & sources")
        }
        Spacer(Modifier.height(24.dp))
    }
}
