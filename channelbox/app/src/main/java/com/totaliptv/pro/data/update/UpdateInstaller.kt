package com.totaliptv.pro.data.update

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 1.4.107: the Install update step shared by TV Classic, TV Desktop and phone.
 *
 * 1.4.106 ran the download in the Settings screen's scope, so leaving Settings while
 * "Downloading…" cancelled it and showed "Download failed" even though nothing was wrong.
 * The download now finishes (and opens the installer) even if Settings is closed.
 */
object UpdateInstaller {
    private const val PREFS = "tip_update_pending"
    private const val KEY_CODE = "code"
    private const val KEY_NAME = "name"
    private const val KEY_AT = "at"
    private const val PENDING_MAX_AGE_MS = 24L * 60 * 60 * 1000

    const val UNKNOWN_APPS_HINT =
        "Turn on Total IPTV Pro under Install unknown apps. Android closes the app when you " +
            "allow it, so open the app again and choose Check for update."

    suspend fun downloadAndInstall(
        context: Context,
        ready: UpdateCheckResult.Available,
        onProgress: (Int) -> Unit
    ) = withContext(NonCancellable) {
        val app = context.applicationContext
        val file = AppUpdateChecker.downloadApk(
            app,
            ready.apkUrl,
            ready.manifest.size,
            ready.manifest.sha256,
            onProgress
        )
        rememberPending(app, ready.manifest)
        withContext(Dispatchers.Main) { AppUpdateChecker.launchInstaller(app, file) }
    }

    fun downloadingLabel(versionName: String, percent: Int?): String {
        val name = versionName.ifBlank { "update" }
        return if (percent == null) "Downloading $name…" else "Downloading $name… $percent%"
    }

    /** Plain words for the brother on a TV; never includes a URL. */
    fun friendlyError(t: Throwable): String {
        val root = generateSequence(t) { it.cause }.toList()
        val msg = root.joinToString(" ") { it.message.orEmpty() }
        val reason = when {
            root.any { it is UnknownHostException || it is ConnectException } ->
                "No internet connection, or Google Drive is blocked on this network."
            root.any { it is SocketTimeoutException || it is InterruptedIOException } ->
                "The download timed out."
            root.any { it is SSLException } ->
                "A secure connection to Google Drive could not be made. Check the date and time on this device."
            root.any { it is OutOfMemoryError } -> "The device ran out of memory."
            msg.contains("mismatch", true) || msg.contains("too small", true) ||
                msg.contains("not an app file", true) -> "The download was incomplete or blocked."
            msg.contains("ENOSPC", true) || msg.contains("No space", true) -> "The device storage is full."
            else -> com.totaliptv.pro.util.SensitiveText.forUser(t)
        }
        return "Download failed: $reason Choose Install again."
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
        return msg
    }

    fun updatedMessage(pendingCode: Int, installedCode: Int, installedName: String, ageMs: Long): String? =
        if (pendingCode > 0 && installedCode >= pendingCode && ageMs in 0..PENDING_MAX_AGE_MS) {
            "Updated to $installedName"
        } else {
            null
        }
}
