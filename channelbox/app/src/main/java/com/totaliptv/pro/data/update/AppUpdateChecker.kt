package com.totaliptv.pro.data.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.totaliptv.pro.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "TotalIPTVPro.apk",
    /** Google Drive manifest extras (1.4.92+). Shelf manifests leave these blank. */
    val driveFileId: String = "",
    val url: String = "",
    val size: Long = 0,
    val sha256: String = "",
    /** 1.4.108: extra download links for the same file (tried after [url]). */
    val mirrors: List<String> = emptyList()
)

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(
        val manifest: UpdateManifest,
        val apkUrl: String,
        val mirrors: List<String> = emptyList(),
        /** Plain source name ("Google Drive", "GitHub", ...). */
        val source: String = ""
    ) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

fun UpdateCheckResult.Available.installLabel(): String {
    val name = manifest.versionName.ifBlank { "update" }
    return if (manifest.versionCode > 0) {
        "Update available: $name (code ${manifest.versionCode}). Tap to install."
    } else {
        "Update available: $name. Tap to install."
    }
}

object AppUpdateChecker {
    const val DOWNLOAD_ATTEMPTS = UpdateEngine.ATTEMPTS
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun normalizeBase(raw: String): String = AppUpdateCheckerBase.normalizeBase(raw)

    private fun engine(): UpdateEngine = UpdateEngine(
        client = client,
        endpoints = UpdateEndpoints.withOverride(
            UpdateEndpoints.defaults(BuildConfig.FLAVOR),
            BuildConfig.TEST_UPDATE_ENDPOINTS
        ),
        flavor = BuildConfig.FLAVOR,
        versionCode = BuildConfig.VERSION_CODE,
        versionName = BuildConfig.VERSION_NAME,
        localIpv4 = ::localIpv4
    )

    /** This device's IPv4 addresses (no permission needed). */
    fun localIpv4(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /**
     * 1.4.108: Google Drive manifest, then the GitHub releases list, then the per-release
     * version file, then the optional home shelf (skipped when it is a private address on
     * another network). A failure names the real reason for each source.
     */
    suspend fun check(baseUrl: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED) {
            return@withContext UpdateCheckResult.UpToDate
        }
        engine().check(baseUrl)
    }

    /**
     * 1.4.108: streams to a .part file (resumes with HTTP Range after a drop), checks free space,
     * tries the manifest link then mirrors / GitHub, [DOWNLOAD_ATTEMPTS] tries each with backoff,
     * and verifies the zip header, size and SHA-256. [onProgress] gets 0..100 when the size is known.
     */
    suspend fun downloadApk(
        context: Context,
        ready: UpdateCheckResult.Available,
        onProgress: ((Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (!com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED) error("Updates come from Google Play")
        engine().download(File(context.cacheDir, "updates"), ready, onProgress)
    }

    /** 1.4.108: null when [apk] can replace this app; otherwise a plain reason (e.g. uninstall first). */
    fun installProblem(context: Context, apk: File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val archive = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
        val installed = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        return UpdateSources.installMismatch(
            installedPackage = context.packageName,
            archivePackage = archive?.packageName,
            installedCerts = certs(installed),
            archiveCerts = certs(archive),
            installedVersionCode = installed?.let { versionCodeOf(it) } ?: 0L,
            archiveVersionCode = archive?.let { versionCodeOf(it) } ?: 0L
        )
    }

    @Suppress("DEPRECATION")
    private fun versionCodeOf(info: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun certs(info: android.content.pm.PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        val sigs = runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val si = info.signingInfo ?: return@runCatching null
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory?.takeLast(1)?.toTypedArray()
            } else {
                info.signatures
            }
        }.getOrNull() ?: return emptySet()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return sigs.filterNotNull().map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else true
    }

    fun openUnknownSourcesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    fun launchInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // 1.4.107: start the installer fresh. Without this, an installer screen left
            // open from an earlier update ("App installed." or "App not installed.") was
            // shown again on top of the new install prompt.
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }
}
