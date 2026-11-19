package com.totaliptv.pro.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Where the updater looks. Real builds use [defaults]; test builds may point them at a local server. */
data class UpdateEndpoints(
    val driveManifestUrl: String,
    val githubReleasesUrl: String,
    val versionFileUrl: String
) {
    companion object {
        fun defaults(flavor: String): UpdateEndpoints {
            val id = UpdateSources.driveManifestId(flavor)
            return UpdateEndpoints(
                driveManifestUrl = if (id.isBlank()) "" else UpdateSources.driveDownloadUrl(id),
                githubReleasesUrl = UpdateSources.GITHUB_RELEASES_URL,
                versionFileUrl = UpdateSources.versionFileUrl(flavor)
            )
        }

        /**
         * Test builds only (TIP_TEST_UPDATE_ENDPOINTS at build time, blank in every shipped build):
         * "drive=<url>;github=<url>;vfile=<url>". Missing keys keep the default.
         */
        fun withOverride(base: UpdateEndpoints, raw: String): UpdateEndpoints {
            if (raw.isBlank()) return base
            val map = raw.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim().lowercase() to part.substring(i + 1).trim()
            }.toMap()
            return UpdateEndpoints(
                driveManifestUrl = map["drive"] ?: base.driveManifestUrl,
                githubReleasesUrl = map["github"] ?: base.githubReleasesUrl,
                versionFileUrl = map["vfile"] ?: base.versionFileUrl
            )
        }
    }
}

@Serializable
internal data class GhAsset(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    val digest: String? = null
)

@Serializable
internal data class GhRelease(
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GhAsset> = emptyList()
)

/**
 * 1.4.108 update engine (no Android types, so it is unit-tested against a local HTTP server).
 *
 * Check order: Google Drive manifest -> GitHub releases API -> per-release version file
 * (releases/latest/download/version-<flavor>.json) -> the optional home shelf. The first source
 * that answers decides (an "up to date" answer stops the search). Failures are kept per source.
 *
 * Download: the manifest URL (Drive) first, then mirrors (manifest "mirrors", then the same
 * version's GitHub asset). Each source gets [ATTEMPTS] tries with [BACKOFF_MS] waits. A dropped
 * download resumes from the .part file with an HTTP Range request. Free space is checked first.
 * The finished file must start with "PK" and match the expected size and SHA-256.
 */
class UpdateEngine(
    private val client: OkHttpClient,
    private val endpoints: UpdateEndpoints,
    private val flavor: String,
    private val versionCode: Int,
    private val versionName: String,
    private val localIpv4: () -> List<String> = { emptyList() },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val freeBytes: (File) -> Long = { it.usableSpace }
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun check(shelfBase: String): UpdateCheckResult {
        val failures = mutableListOf<Pair<String, String>>()
        val sources: List<Pair<String, () -> UpdateCheckResult>> = listOf(
            SITE_DRIVE to { checkManifest(endpoints.driveManifestUrl, SITE_DRIVE) },
            SITE_GITHUB to { checkGithub() },
            SITE_RELEASE_FILE to { checkManifest(endpoints.versionFileUrl, SITE_RELEASE_FILE) }
        )
        for ((name, run) in sources) {
            try {
                return run()
            } catch (t: Throwable) {
                if (t is InterruptedException) throw t
                failures += name to UpdateErrors.reason(t)
            }
        }
        if (UpdateSources.shouldCheckShelf(shelfBase)) {
            if (!UpdateSources.shelfUsableHere(shelfBase, localIpv4())) {
                failures += SITE_SHELF to UpdateErrors.SHELF_SKIPPED
            } else {
                try {
                    return checkShelf(shelfBase)
                } catch (t: Throwable) {
                    failures += SITE_SHELF to UpdateErrors.reason(t)
                }
            }
        }
        return UpdateCheckResult.Failed(UpdateErrors.checkFailed(failures))
    }

    private fun getText(url: String, site: String, github: Boolean = false): String {
        if (url.isBlank()) error("$site is not set up for this build")
        val b = Request.Builder().url(url).get()
            .header("User-Agent", "TotalIPTVPro/$versionName")
        if (github) b.header("Accept", "application/vnd.github+json")
        client.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val limited = github && resp.code == 403 && resp.header("x-ratelimit-remaining") == "0"
                throw HttpStatusException(resp.code, site, rateLimited = limited || (github && resp.code == 429))
            }
            val body = resp.body?.string().orEmpty()
            if (UpdateSources.looksLikeWebPage(body)) throw WebPageException(site)
            if (body.isBlank()) throw WebPageException(site)
            return body
        }
    }

    private fun checkManifest(url: String, site: String): UpdateCheckResult {
        val manifest = json.decodeFromString(UpdateManifest.serializer(), getText(url, site))
        if (manifest.versionCode <= versionCode) return UpdateCheckResult.UpToDate
        // A plain-http app link is only accepted from a plain-http manifest (local test server).
        val plainOk = url.startsWith("http://") && manifest.url.startsWith("http://")
        val apk = (if (plainOk) manifest.url.trim() else UpdateSources.driveApkUrl(manifest.url, manifest.driveFileId))
            ?: error("$site manifest has no app link")
        return UpdateCheckResult.Available(
            manifest = manifest,
            apkUrl = apk,
            mirrors = manifest.mirrors.filter { it.startsWith("https://") || it.startsWith("http://") },
            source = site
        )
    }

    private fun githubReleases(): List<GhRelease> {
        val body = getText(endpoints.githubReleasesUrl + PER_PAGE, SITE_GITHUB, github = true)
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GhRelease.serializer()), body)
    }

    private fun checkGithub(): UpdateCheckResult {
        val releases = githubReleases()
        val listings = releases.map { r ->
            UpdateSources.ReleaseListing(r.draft, r.prerelease, r.assets.map { UpdateSources.Asset(it.name, it.url) })
        }
        val picked = UpdateSources.pickNewerApk(listings, flavor, versionName) ?: return UpdateCheckResult.UpToDate
        val asset = releases.asSequence().flatMap { it.assets.asSequence() }.first { it.url == picked.url }
        return UpdateCheckResult.Available(
            manifest = UpdateManifest(
                versionCode = 0,
                versionName = picked.versionName,
                apk = asset.name,
                url = asset.url,
                size = asset.size,
                sha256 = UpdateSources.shaFromDigest(asset.digest)
            ),
            apkUrl = asset.url,
            source = SITE_GITHUB
        )
    }

    private fun checkShelf(baseUrl: String): UpdateCheckResult {
        val base = AppUpdateCheckerBase.normalizeBase(baseUrl)
        val manifest = json.decodeFromString(UpdateManifest.serializer(), getText(base + "version.json", SITE_SHELF))
        if (manifest.versionCode <= versionCode) return UpdateCheckResult.UpToDate
        val apkName = manifest.apk.ifBlank { "TotalIPTVPro.apk" }
        val url = manifest.url.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: (base + apkName)
        return UpdateCheckResult.Available(manifest, url, source = SITE_SHELF)
    }

    /** The same version's GitHub asset, used as a download mirror. Null when none or GitHub fails. */
    fun githubMirror(targetVersion: String): String? = runCatching {
        githubReleases().asSequence()
            .filter { !it.draft && !it.prerelease }
            .flatMap { it.assets.asSequence() }
            .firstOrNull { a ->
                val v = UpdateSources.versionFromAsset(a.name, flavor)
                v != null && UpdateSources.compareVersions(v, targetVersion) == 0 && a.url.isNotBlank()
            }?.url
    }.getOrNull()

    /**
     * Downloads [ready] into [dir]. Returns the verified APK file.
     * Throws [UpdateDownloadException] (wrapping the last error) when every source failed,
     * or [InsufficientSpaceException] at once when storage is short.
     */
    fun download(
        dir: File,
        ready: UpdateCheckResult.Available,
        onProgress: ((Int) -> Unit)? = null
    ): File {
        dir.mkdirs()
        val out = File(dir, OUT_NAME)
        val part = File(dir, "$OUT_NAME.part")
        val meta = File(dir, "$OUT_NAME.part.meta")
        val size = ready.manifest.size
        val sha = ready.manifest.sha256.trim().lowercase()
        val key = listOf(ready.manifest.versionName, size.toString(), sha.ifBlank { ready.apkUrl }).joinToString("|")
        if (!part.exists() || runCatching { meta.readText() }.getOrNull() != key) {
            part.delete()
            meta.writeText(key)
        }
        val needed = (if (size > 0) size else UNKNOWN_SIZE_GUESS) - part.length() + SPACE_MARGIN
        val free = freeBytes(dir)
        if (free in 0 until needed) throw InsufficientSpaceException(needed - free)

        val urls = (listOf(ready.apkUrl) + ready.mirrors).distinct().toMutableList()
        val tried = mutableListOf<String>()
        val siteOf = HashMap<String, String>()
        var last: Throwable? = null
        var githubLooked = ready.source == SITE_GITHUB
        var i = 0
        while (true) {
            if (i >= urls.size) {
                if (githubLooked) break
                githubLooked = true
                val mirror = githubMirror(ready.manifest.versionName) ?: break
                if (mirror in urls) break
                urls += mirror
                siteOf[mirror] = SITE_GITHUB
            }
            val url = urls[i]
            val site = siteOf[url] ?: siteName(url, ready.source)
            if (site !in tried) tried += site
            for (attempt in 1..ATTEMPTS) {
                if (attempt > 1) sleep(BACKOFF_MS.getOrElse(attempt - 2) { BACKOFF_MS.last() })
                try {
                    downloadOnce(url, site, part, size, sha, onProgress)
                    if (out.exists()) out.delete()
                    if (!part.renameTo(out)) {
                        part.copyTo(out, overwrite = true)
                        part.delete()
                    }
                    meta.delete()
                    return out
                } catch (t: Throwable) {
                    if (t is InterruptedException) throw t
                    last = t
                    if (t is InsufficientSpaceException) throw t
                    // A web page, a 404 or a damaged file will not get better by retrying this source.
                    val retryable = t !is WebPageException && t !is DamagedDownloadException &&
                        !(t is HttpStatusException && t.code in 400..499 && t.code != 408 && t.code != 429)
                    if (t is WebPageException || t is DamagedDownloadException) {
                        part.delete(); meta.writeText(key)
                    }
                    if (!retryable) break
                }
            }
            i++
        }
        throw UpdateDownloadException(tried, last ?: IllegalStateException("Download failed"))
    }

    private fun downloadOnce(
        url: String,
        site: String,
        part: File,
        expectedSize: Long,
        expectedSha: String,
        onProgress: ((Int) -> Unit)?
    ) {
        var offset = if (part.exists()) part.length() else 0L
        if (expectedSize in 1..offset) { part.delete(); offset = 0 }
        val b = Request.Builder().url(url).get().header("User-Agent", "TotalIPTVPro/$versionName")
        if (offset > 0) b.header("Range", "bytes=$offset-")
        client.newCall(b.build()).execute().use { resp ->
            if (resp.code == 416) { part.delete(); throw IncompleteDownloadException(0, expectedSize) }
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, site)
            val body = resp.body ?: throw IncompleteDownloadException(0, expectedSize)
            val resumed = offset > 0 && resp.code == 206 &&
                resp.header("Content-Range").orEmpty().startsWith("bytes $offset-")
            if (!resumed) { offset = 0 }
            val md = MessageDigest.getInstance("SHA-256")
            val head = ByteArray(2)
            var headLen = 0
            if (resumed) {
                part.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (headLen < 2) { val t = minOf(2 - headLen, n); System.arraycopy(buf, 0, head, headLen, t); headLen += t }
                        md.update(buf, 0, n)
                    }
                }
            }
            val total = if (expectedSize > 0) expectedSize else body.contentLength().let { if (it > 0) it + offset else -1 }
            var count = offset
            var lastPct = -1
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        if (headLen < 2) {
                            val take = minOf(2 - headLen, n)
                            System.arraycopy(buf, 0, head, headLen, take)
                            headLen += take
                            if (headLen == 2 && !UpdateSources.isZipHead(head)) throw WebPageException(site)
                        }
                        raf.write(buf, 0, n)
                        md.update(buf, 0, n)
                        count += n
                        if (expectedSize > 0 && count > expectedSize) {
                            throw DamagedDownloadException("Download size mismatch ($count vs $expectedSize)")
                        }
                        if (total > 0 && onProgress != null) {
                            val pct = (count * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (expectedSize > 0 && count < expectedSize) throw IncompleteDownloadException(count, expectedSize)
            val got = md.digest().joinToString("") { "%02x".format(it) }
            UpdateSources.verifyDownload(count, head.copyOf(headLen), got, expectedSize, expectedSha)?.let {
                if (it == "The download was not an app file") throw WebPageException(site)
                throw DamagedDownloadException(it)
            }
        }
    }

    companion object {
        const val ATTEMPTS = 3
        val BACKOFF_MS = listOf(2_000L, 5_000L)
        const val OUT_NAME = "TotalIPTVPro-update.apk"
        const val UNKNOWN_SIZE_GUESS = 40L * 1024 * 1024
        const val SPACE_MARGIN = 20L * 1024 * 1024
        private const val PER_PAGE = "?per_page=50"
        const val SITE_DRIVE = "Google Drive"
        const val SITE_GITHUB = "GitHub"
        const val SITE_RELEASE_FILE = "the release file"
        const val SITE_SHELF = "the home shelf"

        fun siteName(url: String, fallback: String): String {
            val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            return when {
                host.endsWith("google.com") || host.endsWith("googleusercontent.com") -> SITE_DRIVE
                host.endsWith("github.com") || host.endsWith("githubusercontent.com") -> SITE_GITHUB
                else -> fallback.ifBlank { "the update server" }
            }
        }
    }
}

/** Shared by the engine and the Settings "Save" button. */
object AppUpdateCheckerBase {
    fun normalizeBase(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }
}
