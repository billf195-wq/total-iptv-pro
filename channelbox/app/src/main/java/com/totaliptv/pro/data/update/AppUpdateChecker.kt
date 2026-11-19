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
import kotlinx.serialization.json.Json
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
    val sha256: String = ""
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

object AppUpdateChecker {
    const val DOWNLOAD_ATTEMPTS = 2
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
     * 1.4.92: Google Drive manifest only; the home shelf is the fallback when
     * Drive is unreachable. Manual downloads stay on the public releases page.
     */
    suspend fun check(baseUrl: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!com.totaliptv.pro.BuildConfig.SELF_UPDATER_ENABLED) {
            return@withContext UpdateCheckResult.UpToDate
        }
        val drive = runCatching { checkDrive() }.getOrElse {
            UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
        }
        if (drive !is UpdateCheckResult.Failed) return@withContext drive
        if (!UpdateSources.shouldCheckShelf(baseUrl)) return@withContext drive
        checkShelf(baseUrl)
    }

    private fun checkDrive(): UpdateCheckResult {
        val manifestId = UpdateSources.driveManifestId(BuildConfig.FLAVOR)
        if (manifestId.isBlank()) return UpdateCheckResult.Failed(UpdateSources.UPDATE_UNAVAILABLE)
        val req = Request.Builder().url(UpdateSources.driveDownloadUrl(manifestId)).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val manifest = json.decodeFromString(UpdateManifest.serializer(), body)
            if (manifest.versionCode <= BuildConfig.VERSION_CODE) return UpdateCheckResult.UpToDate
            val url = UpdateSources.driveApkUrl(manifest.url, manifest.driveFileId)
                ?: error("Drive manifest has no APK")
            return UpdateCheckResult.Available(manifest, url)
        }
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

    /**
     * 1.4.107: streams the APK to a .part file (no 20+ MB byte array in memory, which could
     * run out of memory on small TV sticks with a big catalog loaded), hashes it while it
     * downloads, checks it is really an APK, and tries [DOWNLOAD_ATTEMPTS] times.
     * [onProgress] gets 0..100 when the size is known.
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        expectedSize: Long = 0,
        expectedSha256: String = "",
        onProgress: ((Int) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "TotalIPTVPro-update.apk")
        val part = File(dir, "TotalIPTVPro-update.apk.part")
        var last: Throwable? = null
        for (attempt in 1..DOWNLOAD_ATTEMPTS) {
            try {
                downloadOnce(apkUrl, part, expectedSize, expectedSha256, onProgress)
                if (out.exists()) out.delete()
                if (!part.renameTo(out)) {
                    part.copyTo(out, overwrite = true)
                    part.delete()
                }
                return@withContext out
            } catch (e: kotlinx.coroutines.CancellationException) {
                part.delete()
                throw e
            } catch (e: Throwable) {
                part.delete()
                last = e
            }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    private fun downloadOnce(
        apkUrl: String,
        part: File,
        expectedSize: Long,
        expectedSha256: String,
        onProgress: ((Int) -> Unit)?
    ) {
        if (part.exists()) part.delete()
        val req = Request.Builder().url(apkUrl).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download failed (${resp.code})")
            val body = resp.body ?: error("Empty APK body")
            val total = if (expectedSize > 0) expectedSize else body.contentLength()
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val head = ByteArray(2)
            var headLen = 0
            var count = 0L
            var lastPct = -1
            body.byteStream().use { input ->
                part.outputStream().buffered().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (headLen < 2) {
                            val take = minOf(2 - headLen, n)
                            System.arraycopy(buf, 0, head, headLen, take)
                            headLen += take
                        }
                        sink.write(buf, 0, n)
                        md.update(buf, 0, n)
                        count += n
                        if (total > 0 && onProgress != null) {
                            val pct = (count * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            UpdateSources.verifyDownload(count, head.copyOf(headLen), sha, expectedSize, expectedSha256)
                ?.let { error(it) }
        }
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
