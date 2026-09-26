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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String = "",
    val apk: String = "TotalIPTVPro.apk"
)

sealed class UpdateCheckResult {
    data object UpToDate : UpdateCheckResult()
    data class Available(val manifest: UpdateManifest, val apkUrl: String) : UpdateCheckResult()
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

@Serializable
private data class GithubReleaseDto(
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAssetDto> = emptyList()
)

@Serializable
private data class GithubAssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = ""
)

object AppUpdateChecker {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun normalizeBase(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    /**
     * GitHub Releases first (list releases; do not trust /releases/latest, because
     * a desktop tag can be Latest). An optional shelf URL is the fallback.
     * A blank shelf is skipped. Failures never include a server address.
     */
    suspend fun check(baseUrl: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val github = runCatching { checkGitHub() }.getOrElse {
            UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
        }
        if (github is UpdateCheckResult.Available) return@withContext github
        if (!UpdateSources.shouldCheckShelf(baseUrl)) {
            return@withContext when (github) {
                is UpdateCheckResult.UpToDate -> github
                else -> UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
            }
        }
        val shelf = checkShelf(baseUrl)
        if (shelf is UpdateCheckResult.Available) return@withContext shelf
        if (github is UpdateCheckResult.UpToDate || shelf is UpdateCheckResult.UpToDate) {
            return@withContext UpdateCheckResult.UpToDate
        }
        UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
    }

    private fun checkGitHub(): UpdateCheckResult {
        val releases = mutableListOf<UpdateSources.ReleaseListing>()
        var pageUrl: String? = UpdateSources.GITHUB_RELEASES_URL + "?per_page=100"
        var pages = 0
        while (pageUrl != null && pages < 5) {
            val req = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", "TotalIPTVPro/${BuildConfig.VERSION_NAME}")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) error("Empty releases list")
                val parsed = json.decodeFromString<List<GithubReleaseDto>>(body)
                releases += parsed.map { dto ->
                    UpdateSources.ReleaseListing(
                        draft = dto.draft,
                        prerelease = dto.prerelease,
                        assets = dto.assets.map { asset ->
                            UpdateSources.Asset(asset.name, asset.browserDownloadUrl)
                        }
                    )
                }
                pageUrl = UpdateSources.nextPageUrl(resp.header("Link"))
            }
            pages++
        }
        val picked = UpdateSources.pickNewerApk(
            releasesNewestFirst = releases,
            flavor = BuildConfig.FLAVOR,
            currentVersionName = BuildConfig.VERSION_NAME
        ) ?: return UpdateCheckResult.UpToDate
        return UpdateCheckResult.Available(
            manifest = UpdateManifest(
                versionCode = 0,
                versionName = picked.versionName,
                apk = picked.url.substringAfterLast('/')
            ),
            apkUrl = picked.url
        )
    }

    private fun checkShelf(baseUrl: String): UpdateCheckResult {
        return try {
            val base = normalizeBase(baseUrl)
            val req = Request.Builder().url(base + "version.json").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return UpdateCheckResult.Failed("Server returned ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return UpdateCheckResult.Failed("Empty version.json")
                val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
                if (manifest.versionCode > BuildConfig.VERSION_CODE) {
                    val apkName = manifest.apk.ifBlank { "TotalIPTVPro.apk" }
                    UpdateCheckResult.Available(manifest, base + apkName)
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (_: Throwable) {
            UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
        }
    }

    suspend fun downloadApk(context: Context, apkUrl: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "TotalIPTVPro-update.apk")
        if (out.exists()) out.delete()
        val req = Request.Builder().url(apkUrl).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val bytes = resp.body?.bytes() ?: error("Empty APK body")
            if (bytes.size < 100_000) error("APK too small (${bytes.size} bytes)")
            out.writeBytes(bytes)
        }
        out
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
        }
        context.startActivity(intent)
    }
}
