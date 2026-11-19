package com.totaliptv.pro.data.update

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1.4.107: the Install update step shared by TV Classic, TV Desktop and phone.
 *
 * 1.4.106 ran the download in the Settings screen's scope, so leaving Settings while
 * "Downloading…" cancelled it and showed "Download failed" even though nothing was wrong.
 * The download now finishes (and opens the installer) even if Settings is closed.
 *
 * 1.4.108: real failure reasons (kept as "Last update problem" in Settings), a package and
 * signing-key check before the installer opens, and the update carries on by itself after
 * the user turns on "Install unknown apps".
 */
object UpdateInstaller {
    private const val PREFS = "tip_update_pending"
    private const val KEY_CODE = "code"
    private const val KEY_NAME = "name"
    private const val KEY_AT = "at"
    private const val PENDING_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private const val STATUS_PREFS = "tip_update_status"
    private const val KEY_PROBLEM = "last_problem"
    private const val KEY_PROBLEM_AT = "last_problem_at"
    private const val KEY_AWAIT = "await_manifest"
    private const val KEY_AWAIT_URL = "await_url"
    private const val KEY_AWAIT_MIRRORS = "await_mirrors"
    private const val KEY_AWAIT_SOURCE = "await_source"
    private const val KEY_AWAIT_AT = "await_at"

    const val UNKNOWN_APPS_HINT =
        "Turn on Total IPTV Pro under Install unknown apps. Android may close the app when you " +
            "allow it; open the app again and the update carries on by itself."

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val resuming = AtomicBoolean(false)

    private val _status = MutableStateFlow<String?>(null)
    /** Status of an update running outside Settings (auto-resume after the permission). */
    val status: StateFlow<String?> = _status.asStateFlow()

    suspend fun downloadAndInstall(
        context: Context,
        ready: UpdateCheckResult.Available,
        onProgress: (Int) -> Unit
    ) = withContext(NonCancellable) {
        val app = context.applicationContext
        try {
            val file = AppUpdateChecker.downloadApk(app, ready, onProgress)
            val problem = withContext(Dispatchers.IO) { AppUpdateChecker.installProblem(app, file) }
            if (problem != null) {
                file.delete()
                throw InstallBlockedException(problem)
            }
            rememberPending(app, ready.manifest)
            clearProblem(app)
            withContext(Dispatchers.Main) { AppUpdateChecker.launchInstaller(app, file) }
        } catch (t: Throwable) {
            recordProblem(app, friendlyError(t))
            throw t
        }
    }

    /** Check, and keep the last problem (or clear it after a good answer). */
    suspend fun check(context: Context, baseUrl: String): UpdateCheckResult {
        val result = AppUpdateChecker.check(baseUrl)
        val app = context.applicationContext
        when (result) {
            is UpdateCheckResult.Failed -> recordProblem(app, checkFailedLabel(result.message))
            else -> clearProblem(app)
        }
        return result
    }

    fun checkFailedLabel(message: String): String = "Check failed: $message"

    fun downloadingLabel(versionName: String, percent: Int?): String {
        val name = versionName.ifBlank { "update" }
        return if (percent == null) "Downloading $name…" else "Downloading $name… $percent%"
    }

    /** Plain words for the brother on a TV; never includes a URL. */
    fun friendlyError(t: Throwable): String {
        val blocked = generateSequence(t) { it.cause }.firstOrNull { it is InstallBlockedException }
        if (blocked != null) return "Can't install: ${(blocked as InstallBlockedException).reason}"
        val reason = UpdateErrors.reason(t)
        val tried = (t as? UpdateDownloadException)?.tried.orEmpty()
        val triedText = if (tried.size > 1) " Tried ${UpdateErrors.joinNames(tried)}." else ""
        return "Download failed: $reason$triedText Choose Install again."
    }

    // ---- last problem (shown in Settings until a check or update works) ----

    fun recordProblem(context: Context, text: String) {
        context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROBLEM, text)
            .putLong(KEY_PROBLEM_AT, System.currentTimeMillis())
            .apply()
    }

    fun clearProblem(context: Context) {
        context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PROBLEM).remove(KEY_PROBLEM_AT).apply()
    }

    /** "Last update problem (Nov 19, 4:40 PM): …" or null. */
    fun lastProblemLine(context: Context): String? {
        val p = context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        val text = p.getString(KEY_PROBLEM, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = p.getLong(KEY_PROBLEM_AT, 0L)
        return lastProblemLine(text, at)
    }

    fun lastProblemLine(text: String, atMs: Long): String {
        val whenText = if (atMs > 0) {
            java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.US).format(java.util.Date(atMs))
        } else ""
        return if (whenText.isBlank()) "Last update problem: $text" else "Last update problem ($whenText): $text"
    }

    // ---- carry on after "Install unknown apps" ----

    /** Call right before sending the user to the Install unknown apps screen. */
    fun awaitPermission(context: Context, ready: UpdateCheckResult.Available) {
        context.applicationContext.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_AWAIT, json.encodeToString(UpdateManifest.serializer(), ready.manifest))
            .putString(KEY_AWAIT_URL, ready.apkUrl)
            .putString(KEY_AWAIT_MIRRORS, ready.mirrors.joinToString("\n"))
            .putString(KEY_AWAIT_SOURCE, ready.source)
            .putLong(KEY_AWAIT_AT, System.currentTimeMillis())
            .commit()
    }

    /** The update waiting for the permission, if it is recent. Pure parse for tests. */
    fun awaitingFrom(manifestJson: String?, url: String?, mirrors: String?, source: String?, atMs: Long, nowMs: Long): UpdateCheckResult.Available? {
        if (manifestJson.isNullOrBlank() || url.isNullOrBlank()) return null
        if (nowMs - atMs !in 0..PENDING_MAX_AGE_MS) return null
        val manifest = runCatching { json.decodeFromString(UpdateManifest.serializer(), manifestJson) }.getOrNull() ?: return null
        return UpdateCheckResult.Available(
            manifest, url,
            mirrors = mirrors.orEmpty().split('\n').filter { it.isNotBlank() },
            source = source.orEmpty()
        )
    }

    /**
     * Call from the activity's onResume. When an update was waiting for "Install unknown apps"
     * and the permission is now on, download and install it without another click.
     */
    fun resumeAfterPermission(activity: Activity) {
        if (!com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED) return
        val app = activity.applicationContext
        val p = app.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE)
        val raw = p.getString(KEY_AWAIT, null) ?: return
        val ready = awaitingFrom(
            raw, p.getString(KEY_AWAIT_URL, null), p.getString(KEY_AWAIT_MIRRORS, null),
            p.getString(KEY_AWAIT_SOURCE, null), p.getLong(KEY_AWAIT_AT, 0L), System.currentTimeMillis()
        )
        if (ready == null) { clearAwait(app); return }
        if (!AppUpdateChecker.canInstallPackages(app)) return
        if (!resuming.compareAndSet(false, true)) return
        clearAwait(app)
        val name = ready.manifest.versionName
        _status.value = downloadingLabel(name, null)
        android.widget.Toast.makeText(app, "Install permission is on. Downloading update $name…", android.widget.Toast.LENGTH_LONG).show()
        scope.launch {
            try {
                downloadAndInstall(app, ready) { pct -> _status.value = downloadingLabel(name, pct) }
                _status.value = "Opening installer…"
            } catch (t: Throwable) {
                val msg = friendlyError(t)
                _status.value = msg
                android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_LONG).show()
            } finally {
                resuming.set(false)
            }
        }
    }

    private fun clearAwait(context: Context) {
        context.getSharedPreferences(STATUS_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_AWAIT).remove(KEY_AWAIT_URL).remove(KEY_AWAIT_MIRRORS)
            .remove(KEY_AWAIT_SOURCE).remove(KEY_AWAIT_AT).apply()
    }

    private fun rememberPending(context: Context, manifest: UpdateManifest) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_CODE, manifest.versionCode)
            .putString(KEY_NAME, manifest.versionName)
            .putLong(KEY_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Call once on app start. Returns "Updated to X" when the installer finished, so the
     * user knows it worked, then forgets the pending update.
     */
    fun consumeUpdatedMessage(context: Context, installedCode: Int, installedName: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val code = prefs.getInt(KEY_CODE, 0)
        if (code <= 0) return null
        val at = prefs.getLong(KEY_AT, 0L)
        val msg = updatedMessage(code, installedCode, installedName, System.currentTimeMillis() - at)
        if (msg != null || installedCode >= code || System.currentTimeMillis() - at > PENDING_MAX_AGE_MS) {
            prefs.edit().clear().apply()
        }
        if (msg != null) clearProblem(context)
        return msg
    }

    fun updatedMessage(pendingCode: Int, installedCode: Int, installedName: String, ageMs: Long): String? =
        if (pendingCode > 0 && installedCode >= pendingCode && ageMs in 0..PENDING_MAX_AGE_MS) {
            "Updated to $installedName"
        } else {
            null
        }
}
