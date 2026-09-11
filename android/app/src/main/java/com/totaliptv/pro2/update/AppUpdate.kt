package com.totaliptv.pro2.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    val apk: String = APP_APK_NAME
)

enum class UpdatePhase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    ReadyToInstall,
    Error
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val message: String = "",
    val localVersionName: String = "",
    val localVersionCode: Int = 0,
    val remote: RemoteVersion? = null,
    val shelfBaseUrl: String? = null,
    val apkFile: File? = null
)

object AppUpdateConfig {
    /** Pro 2 shelves only — never fetch original TotalIPTVPro version.json. */
    val shelfBases: List<String> = listOf(
        "http://192.168.4.39:8766/",
        "http://10.0.2.2:8766/", // Android emulator → this Ubuntu host
        "http://192.168.4.37:8765/"
    )

    /** Tried in order under each shelf base. */
    val versionPaths: List<String> = listOf(
        "version-pro2.json",
        "pro2/version.json"
    )

    const val APP_APK_NAME = "TotalIptvPro2.apk"
}

private const val APP_APK_NAME = AppUpdateConfig.APP_APK_NAME

class AppUpdateManager(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun localVersion(): Pair<Int, String> {
        return try {
            val pi = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
            code to (pi.versionName ?: "?")
        } catch (_: Throwable) {
            0 to "?"
        }
    }

    /**
     * Fetch Pro-2 version metadata from the first reachable shelf.
     * Rejects any payload that points at the original TotalIPTVPro.apk.
     */
    fun check(): UpdateUiState {
        val (localCode, localName) = localVersion()
        val errors = mutableListOf<String>()

        for (base in AppUpdateConfig.shelfBases) {
            val baseNorm = base.trimEnd('/') + "/"
            for (path in AppUpdateConfig.versionPaths) {
                val url = baseNorm + path.trimStart('/')
                try {
                    val body = httpGetString(url) ?: continue
                    val remote = json.decodeFromString(RemoteVersion.serializer(), body)
                    if (!isPro2ApkName(remote.apk)) {
                        errors += "$url: refused non-Pro2 apk='${remote.apk}'"
                        continue
                    }
                    return if (remote.versionCode > localCode) {
                        UpdateUiState(
                            phase = UpdatePhase.Available,
                            message = "Update available: ${remote.versionName} (${remote.versionCode})",
                            localVersionName = localName,
                            localVersionCode = localCode,
                            remote = remote,
                            shelfBaseUrl = baseNorm
                        )
                    } else {
                        UpdateUiState(
                            phase = UpdatePhase.UpToDate,
                            message = "Up to date",
                            localVersionName = localName,
                            localVersionCode = localCode,
                            remote = remote,
                            shelfBaseUrl = baseNorm
                        )
                    }
                } catch (t: Throwable) {
                    errors += "$url: ${t.message ?: t.javaClass.simpleName}"
                }
            }
        }

        return UpdateUiState(
            phase = UpdatePhase.Error,
            message = "Could not check for updates. " +
                (errors.lastOrNull() ?: "No Pro 2 shelf responded."),
            localVersionName = localName,
            localVersionCode = localCode
        )
    }

    fun download(remote: RemoteVersion, shelfBaseUrl: String): UpdateUiState {
        val (localCode, localName) = localVersion()
        if (!isPro2ApkName(remote.apk)) {
            return UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Refused non-Pro2 APK name: ${remote.apk}",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = shelfBaseUrl
            )
        }
        val apkName = APP_APK_NAME
        val url = shelfBaseUrl.trimEnd('/') + "/" + apkName
        return try {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, apkName)
            if (out.exists()) out.delete()
            httpDownload(url, out)
            if (!out.exists() || out.length() < 1024L) {
                throw IllegalStateException("Downloaded APK is empty or missing")
            }
            UpdateUiState(
                phase = UpdatePhase.ReadyToInstall,
                message = "Downloaded ${remote.versionName} — tap Install update",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = shelfBaseUrl,
                apkFile = out
            )
        } catch (t: Throwable) {
            UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Download failed: ${t.message ?: t.javaClass.simpleName}",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = shelfBaseUrl
            )
        }
    }

    companion object {
        fun isPro2ApkName(name: String): Boolean {
            val n = name.trim().substringAfterLast('/')
            if (n.equals("TotalIPTVPro.apk", ignoreCase = true)) return false
            if (n.contains("Phone", ignoreCase = true) && !n.contains("Pro2", ignoreCase = true)) {
                return false
            }
            return n.equals(APP_APK_NAME, ignoreCase = true) ||
                (n.startsWith("TotalIptvPro2", ignoreCase = true) && n.endsWith(".apk", ignoreCase = true))
        }

        fun canInstallPackages(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.packageManager.canRequestPackageInstalls()
            } else {
                true
            }
        }

        fun intentUnknownSources(context: Context): Intent {
            return Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            )
        }

        fun installApk(activity: Activity, apkFile: File) {
            val authority = "${activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(activity, authority, apkFile)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(view)
        }
    }

    private fun httpGetString(url: String): String? {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()?.takeIf { it.isNotBlank() }
        }
    }

    private fun httpDownload(url: String, dest: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code} for $url")
            }
            val body = resp.body ?: throw IllegalStateException("Empty body")
            dest.outputStream().use { out ->
                body.byteStream().use { inp -> inp.copyTo(out) }
            }
        }
    }
}
