package com.totaliptv.pro.ui.settings

import com.totaliptv.pro.data.lock.ParentalGate
import com.totaliptv.pro.data.lock.PinFlow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.totaliptv.pro.BuildConfig
import com.totaliptv.pro.R
import com.totaliptv.pro.TotalIptvProApp
import com.totaliptv.pro.billing.LicenseStrings
import com.totaliptv.pro.diagnostics.CrashLog
import com.totaliptv.pro.diagnostics.DebugLog
import com.totaliptv.pro.data.local.AppPreferences
import com.totaliptv.pro.data.local.PreferredPlayer
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.data.update.AppUpdateChecker
import com.totaliptv.pro.data.update.installLabel
import com.totaliptv.pro.data.update.UpdateCheckResult
import com.totaliptv.pro.ui.theme.AccentPreset
import com.totaliptv.pro.ui.theme.AppearanceMode
import com.totaliptv.pro.ui.components.PhonePageHeader
import com.totaliptv.pro.ui.theme.OnCinema
import com.totaliptv.pro.ui.theme.OnCinemaMuted
import com.totaliptv.pro.ui.theme.tipScreenBrush
import com.totaliptv.pro.util.SensitiveText
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
    val autoPip by app.preferences.autoPip.collectAsState(initial = AppPreferences.defaultAutoPip())
    val autoPlayNext by app.preferences.autoPlayNext.collectAsState(initial = true)
    var showHowTo by remember { mutableStateOf(false) }
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
    // 1.4.108: an update that carried on after "Install unknown apps", and the last problem kept for Settings.
    val updateBgStatus by com.totaliptv.pro.data.update.UpdateInstaller.status.collectAsState()
    val updateLastProblem = remember(updateStatus, updateBgStatus) { com.totaliptv.pro.data.update.UpdateInstaller.lastProblemLine(context) }
    val tmdbKey by app.preferences.tmdbApiKey.collectAsState(initial = "")
    val serviceStatus by repository.serviceLookupStatus.collectAsState()
    var editingTmdbKey by remember { mutableStateOf(false) }
    var tmdbDraft by remember(tmdbKey) { mutableStateOf(tmdbKey) }
    val licenseStatus by app.license.status.collectAsState()
    val licensePrice by app.license.price.collectAsState()
    val licenseMessage by app.license.message.collectAsState()
    val showAdultInSearch by app.preferences.showAdultInSearch.collectAsState(initial = false)
    var pendingShowAdult by remember { mutableStateOf(false) }
    val activity = context as? android.app.Activity
    val lockRevision by ParentalGate.revision.collectAsState()
    val pinEntries = remember(lockRevision) { PinLockEntry.visibleEntries() }
    var pinDialog by remember { mutableStateOf<PinLockDialog?>(null) }
    PhonePinLockDialogHost(
        dialog = pinDialog,
        repository = repository,
        onClose = { pinDialog = it },
        onPinVerified = {
            if (pendingShowAdult) {
                pendingShowAdult = false
                scope.launch { app.preferences.setShowAdultInSearch(true) }
            }
        }
    )

    if (showHowTo) {
        PhoneHowToScreen(onBack = { showHowTo = false }, contentPadding = contentPadding)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(tipScreenBrush())
            .padding(contentPadding)
    ) {
        PhonePageHeader()
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Total IPTV Pro Phone ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showHowTo = true }, modifier = Modifier.fillMaxWidth()) {
            Text("How to Use")
        }
        Spacer(Modifier.height(16.dp))
        if (BuildConfig.PLAY_UNLOCK_ENABLED) {
        Text("Unlock", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            LicenseStrings.statusLine(licenseStatus, licensePrice),
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        if (licenseStatus.showBuyButton) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    if (activity != null) scope.launch { app.license.buy(activity) }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(LicenseStrings.buyLabel(licensePrice)) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { scope.launch { app.license.restore() } },
                modifier = Modifier.fillMaxWidth()
            ) { Text(LicenseStrings.RESTORE) }
            if (!licenseMessage.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(licenseMessage!!, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.trademark_disclaimer),
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )

        if (BuildConfig.SELF_UPDATER_ENABLED) {
        Spacer(Modifier.height(20.dp))
        Text(
            "App update",
            style = MaterialTheme.typography.titleMedium,
            color = OnCinema,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Checks Google Drive first, then GitHub, then the shelf URL if set.",
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
                    Text("Leave blank to use Google Drive and GitHub only")
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
                                updateStatus = com.totaliptv.pro.data.update.UpdateInstaller.UNKNOWN_APPS_HINT
                                Toast.makeText(
                                    context,
                                    "Enable install unknown apps for Total IPTV Pro Phone",
                                    Toast.LENGTH_LONG
                                ).show()
                                com.totaliptv.pro.data.update.UpdateInstaller.awaitPermission(context, ready)
                                AppUpdateChecker.openUnknownSourcesSettings(activity)
                                return@launch
                            }
                            com.totaliptv.pro.data.update.UpdateInstaller.downloadAndInstall(context, ready) { pct ->
                                updateStatus = com.totaliptv.pro.data.update.UpdateInstaller.downloadingLabel(ready.manifest.versionName, pct)
                            }
                            updateStatus = "Opening installer…"
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            // Settings was closed; the download keeps going and still opens the installer.
                            throw c
                        } catch (t: Throwable) {
                            updateStatus = com.totaliptv.pro.data.update.UpdateInstaller.friendlyError(t)
                            Toast.makeText(context, updateStatus, Toast.LENGTH_LONG).show()
                        } finally {
                            updateBusy = false
                        }
                        return@launch
                    }

                    updateBusy = true
                    updateStatus = "Checking…"
                    pendingInstall = null
                    when (val result = com.totaliptv.pro.data.update.UpdateInstaller.check(context, updateBaseUrl)) {
                        is UpdateCheckResult.UpToDate -> {
                            updateStatus = "Up to date (${BuildConfig.VERSION_NAME})"
                            Toast.makeText(context, "Up to date", Toast.LENGTH_SHORT).show()
                        }
                        is UpdateCheckResult.Available -> {
                            pendingInstall = result
                            updateStatus = result.installLabel()
                            Toast.makeText(
                                context,
                                "Update ${result.manifest.versionName} available",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is UpdateCheckResult.Failed -> {
                            updateStatus = com.totaliptv.pro.data.update.UpdateInstaller.checkFailedLabel(result.message)
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
        (updateStatus ?: updateBgStatus ?: updateLastProblem)?.let { shownStatus ->
            Spacer(Modifier.height(6.dp))
            Text(shownStatus, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (pendingInstall != null) {
            TextButton(onClick = { pendingInstall = null; updateStatus = null }) {
                Text("Dismiss update")
            }
        }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "Last crash",
            style = MaterialTheme.typography.titleMedium,
            color = OnCinema,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        val crashText = remember {
            CrashLog.labelForSettings(CrashLog.read(context), com.totaliptv.pro.BuildConfig.VERSION_NAME)
        }
        val debugText = remember { DebugLog.read(context) }
        Text(
            text = crashText.ifBlank { "No crash recorded." },
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        if (debugText.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Debug log\n$debugText",
                color = OnCinemaMuted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (CrashLog.read(context).isBlank()) {
                    Toast.makeText(context, "No crash recorded", Toast.LENGTH_SHORT).show()
                } else if (!runCatching { CrashLog.share(context) }.getOrDefault(false)) {
                    Toast.makeText(
                        context,
                        "Couldn't open a share app. The crash text is above.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Share last crash")
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
                        val msg = SensitiveText.forUser(t)
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
        Text("Service groups", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "TMDB API key (for service groups): " + when {
                tmdbKey.isNotBlank() -> "Saved"
                BuildConfig.TMDB_DEFAULT_KEY.isNotBlank() -> "Using the built-in key"
                else -> "Not set"
            },
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                tmdbDraft = tmdbKey
                editingTmdbKey = !editingTmdbKey
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (editingTmdbKey) "Cancel TMDB key edit" else "Edit TMDB API key")
        }
        if (editingTmdbKey) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = tmdbDraft,
                onValueChange = { tmdbDraft = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("TMDB API key (for service groups)") },
                supportingText = { Text("Free key from themoviedb.org (v3 key or v4 read token)") }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        app.preferences.setTmdbApiKey(tmdbDraft)
                        editingTmdbKey = false
                        repository.startServiceLookup(force = true)
                        Toast.makeText(context, "TMDB key saved", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save TMDB API key")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { repository.startServiceLookup(force = true) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Refresh service groups")
        }
        if (serviceStatus.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(serviceStatus, color = OnCinemaMuted, style = MaterialTheme.typography.bodySmall)
        }

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
            "Record Live, or download a movie / series episode from detail or while playing. Library shows type. Files stay on this phone.",
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
        Spacer(Modifier.height(8.dp))
        FilterChip(
            selected = autoPip,
            onClick = { scope.launch { app.preferences.setAutoPip(!autoPip) } },
            label = { Text(if (autoPip) "Picture-in-Picture on Home: On" else "Picture-in-Picture on Home: Off") }
        )
        Spacer(Modifier.height(8.dp))
        FilterChip(
            selected = autoPlayNext,
            onClick = { scope.launch { app.preferences.setAutoPlayNext(!autoPlayNext) } },
            label = { Text(if (autoPlayNext) "Auto-play next episode: On" else "Auto-play next episode: Off") }
        )

        Spacer(Modifier.height(24.dp))
        Text("Search", style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (showAdultInSearch) {
                    scope.launch { app.preferences.setShowAdultInSearch(false) }
                } else if (ParentalGate.isEnabled) {
                    pendingShowAdult = true
                    pinDialog = PinLockDialog.Pad(com.totaliptv.pro.data.lock.PinMode.VERIFY)
                } else {
                    scope.launch { app.preferences.setShowAdultInSearch(true) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (showAdultInSearch) "Show adult in search: On" else "Show adult in search: Off")
        }
        Text(
            if (showAdultInSearch)
                "Adult titles and adult categories can appear in Search."
            else
                "Off by default. Hides adult titles and adult categories from Search. A PIN, if set, is required to turn this on.",
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(20.dp))

        Text(PinLockEntry.SECTION, style = MaterialTheme.typography.titleMedium, color = OnCinema, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            remember(lockRevision) { PinFlow.statusLine() },
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        pinEntries.forEach { entry ->
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    pinLockEntryClicked(
                        entry,
                        open = { pinDialog = it },
                        toast = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(entry.label) }
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
        Spacer(Modifier.height(16.dp))
        Text(
            com.totaliptv.pro.data.tmdb.TmdbAttribution.TMDB + "\n" +
                com.totaliptv.pro.data.tmdb.TmdbAttribution.JUSTWATCH,
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.trademark_disclaimer),
            color = OnCinemaMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(24.dp))
        }
    }
}
