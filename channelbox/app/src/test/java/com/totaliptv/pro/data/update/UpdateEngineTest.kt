package com.totaliptv.pro.data.update

import okhttp3.OkHttpClient
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.UnknownHostException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 1.4.108: update source chain, download retry/resume/verify, and install checks. */
class UpdateEngineTest {
    /** Tiny HTTP/1.1 test server on a raw socket (com.sun.net.httpserver is not on the Android unit-test classpath). */
    class Ex(val path: String, val headers: Map<String, String>, val out: OutputStream) {
        val extra = LinkedHashMap<String, String>()
        fun header(name: String) = headers[name.lowercase()]
        fun head(code: Int, length: Long, type: String = "application/octet-stream") {
            val sb = StringBuilder("HTTP/1.1 $code X\r\nContent-Length: $length\r\nContent-Type: $type\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
            sb.append("\r\n")
            out.write(sb.toString().toByteArray()); out.flush()
        }
    }

    private lateinit var server: ServerSocket
    private lateinit var base: String
    private val routes = HashMap<String, (Ex) -> Unit>()
    private val hits = CopyOnWriteArrayList<String>()
    private val ranges = CopyOnWriteArrayList<String>()
    private val sleeps = CopyOnWriteArrayList<Long>()
    private lateinit var dir: File

    private val apk: ByteArray = ByteArray(300_000) { i -> if (i == 0) 'P'.code.toByte() else if (i == 1) 'K'.code.toByte() else (i * 31 % 251).toByte() }
    private val apkSha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }

    private fun serve(sock: Socket) {
        sock.use { s ->
            val input = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val line = input.readLine() ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val path = line.split(' ')[1].substringBefore('?')
            hits += path
            headers["range"]?.let { ranges += "$path $it" }
            val ex = Ex(path, headers, s.getOutputStream())
            val h = routes[path]
            runCatching { if (h == null) send(ex, 404, "nope") else h(ex) }
        }
    }

    @BeforeTest
    fun start() {
        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${server.localPort}"
        Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                Thread { serve(c) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        dir = Files.createTempDirectory("upd").toFile()
    }

    @AfterTest
    fun stop() {
        server.close()
        dir.deleteRecursively()
    }

    private fun send(ex: Ex, code: Int, body: String, type: String = "application/json") {
        val b = body.toByteArray()
        ex.head(code, b.size.toLong(), type)
        ex.out.write(b); ex.out.flush()
    }

    private fun sendApk(ex: Ex, bytes: ByteArray = apk) {
        val range = ex.header("Range")
        if (range != null) {
            val from = range.removePrefix("bytes=").substringBefore('-').toInt()
            ex.extra["Content-Range"] = "bytes $from-${bytes.size - 1}/${bytes.size}"
            ex.head(206, (bytes.size - from).toLong())
            ex.out.write(bytes, from, bytes.size - from)
        } else {
            ex.head(200, bytes.size.toLong())
            ex.out.write(bytes)
        }
        ex.out.flush()
    }

    /** Declares the full length, sends [cut] bytes, then drops the connection. */
    private fun sendCut(ex: Ex, cut: Int) {
        ex.head(200, apk.size.toLong())
        ex.out.write(apk, 0, cut)
        ex.out.flush()
    }

    private fun manifest(code: Int, name: String, url: String, size: Long = apk.size.toLong(), sha: String = apkSha) =
        """{"versionCode":$code,"versionName":"$name","url":"$url","size":$size,"sha256":"$sha"}"""

    private fun ghList(name: String, url: String, digest: String? = "sha256:$apkSha") =
        """[{"draft":false,"prerelease":false,"assets":[{"name":"TotalIPTVPro-android-tv-$name-debug.apk",""" +
            """"browser_download_url":"$url","size":${apk.size}${if (digest != null) ",\"digest\":\"$digest\"" else ""}}]}]"""

    private fun engine(
        drive: String = "$base/drive.json",
        gh: String = "$base/gh",
        vfile: String = "$base/vfile.json",
        ips: List<String> = listOf("10.0.2.15"),
        free: Long = Long.MAX_VALUE,
        noDns: Boolean = false
    ) = UpdateEngine(
        client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).connectTimeout(3, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .apply { if (noDns) dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException(hostname) }) }.build(),
        endpoints = UpdateEndpoints(drive, gh, vfile),
        flavor = "tv", versionCode = 120, versionName = "1.4.108",
        localIpv4 = { ips }, sleep = { sleeps += it }, freeBytes = { free }
    )

    @Test
    fun driveAnswerDecidesAndUpToDateStopsTheChain() {
        routes["/drive.json"] = { send(it, 200, manifest(120, "1.4.108", "$base/a.apk")) }
        routes["/gh"] = { send(it, 200, ghList("1.4.200", "$base/gh.apk")) }
        assertEquals(UpdateCheckResult.UpToDate, engine().check(""))
        assertFalse(hits.contains("/gh"))
    }

    @Test
    fun driveFailsThenGithubOffersTheUpdateWithItsSha() {
        routes["/drive.json"] = { send(it, 503, "busy") }
        routes["/gh"] = { send(it, 200, ghList("1.4.109", "$base/gh.apk")) }
        val r = assertIs<UpdateCheckResult.Available>(engine().check(""))
        assertEquals("GitHub", r.source)
        assertEquals("1.4.109", r.manifest.versionName)
        assertEquals(apkSha, r.manifest.sha256)
        assertEquals(apk.size.toLong(), r.manifest.size)
    }

    @Test
    fun webPageAndRateLimitFallThroughToTheReleaseFile() {
        routes["/drive.json"] = { send(it, 200, "<!DOCTYPE html><html>Sign in</html>", "text/html") }
        routes["/gh"] = { it.extra["x-ratelimit-remaining"] = "0"; send(it, 403, "{}") }
        routes["/vfile.json"] = { send(it, 200, manifest(121, "1.4.109", "$base/v.apk")) }
        val res = engine().check("")
        assertTrue(res is UpdateCheckResult.Available, res.toString())
        val r = res as UpdateCheckResult.Available
        assertEquals("the release file", r.source)
        assertEquals(listOf("/drive.json", "/gh", "/vfile.json"), hits.toList())
    }

    @Test
    fun releaseFileFailsThenShelfIsUsedOnTheSameNetwork() {
        routes["/drive.json"] = { send(it, 500, "x") }
        routes["/gh"] = { send(it, 500, "x") }
        routes["/vfile.json"] = { send(it, 404, "x") }
        routes["/shelf/version.json"] = { send(it, 200, """{"versionCode":121,"versionName":"1.4.109","apk":"T.apk"}""") }
        val r = assertIs<UpdateCheckResult.Available>(engine(ips = listOf("127.0.0.5")).check("$base/shelf/"))
        assertEquals("$base/shelf/T.apk", r.apkUrl)
    }

    @Test
    fun oldHomeShelfIsSkippedOnAnotherNetworkAndEveryReasonIsNamed() {
        routes["/drive.json"] = { send(it, 200, "<html>quota</html>", "text/html") }
        routes["/gh"] = { send(it, 500, "x") }
        routes["/vfile.json"] = { send(it, 404, "x") }
        val r = assertIs<UpdateCheckResult.Failed>(engine(ips = listOf("172.20.1.9")).check("http://192.168.4.33:8765/"))
        assertTrue(r.message.contains("Google Drive: " + UpdateErrors.WEB_PAGE), r.message)
        assertTrue(r.message.contains("GitHub had a problem (HTTP 500)"), r.message)
        assertFalse(r.message.contains("GitHub: GitHub"), r.message)
        assertTrue(r.message.endsWith("Home shelf skipped (not on that network)."), r.message)
        assertFalse(r.message.contains("192.168"), r.message)
        assertFalse(r.message.contains("http"), r.message)
    }

    @Test
    fun noInternetIsSaidOnceWithTheSourcesTried() {
        // No network: every name lookup fails (the box's sandbox resolves any name, so DNS is stubbed).
        val dead = "http://drive.example.test"
        val r = assertIs<UpdateCheckResult.Failed>(engine("$dead/d", "$dead/g", "$dead/v", noDns = true).check(""))
        assertEquals(
            UpdateErrors.NO_INTERNET + " Tried Google Drive, GitHub and the release file.",
            r.message
        )
    }

    @Test
    fun downloadRetriesWithBackoffThenSucceeds() {
        var n = 0
        routes["/a.apk"] = { ex -> if (++n < 3) send(ex, 503, "busy") else sendApk(ex) }
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = apk.size.toLong(), sha256 = apkSha), "$base/a.apk", source = "Google Drive")
        val f = engine().download(dir, ready)
        assertTrue(f.readBytes().contentEquals(apk))
        assertEquals(listOf(2_000L, 5_000L), sleeps.toList())
    }

    @Test
    fun droppedDownloadResumesWithRange() {
        var n = 0
        routes["/a.apk"] = { ex -> if (++n == 1) sendCut(ex, 120_000) else sendApk(ex) }
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = apk.size.toLong(), sha256 = apkSha), "$base/a.apk", source = "Google Drive")
        val pct = CopyOnWriteArrayList<Int>()
        val f = engine().download(dir, ready) { pct += it }
        assertTrue(f.readBytes().contentEquals(apk))
        assertEquals(1, ranges.size, ranges.toString())
        assertTrue(ranges[0].startsWith("/a.apk bytes="), ranges.toString())
        assertFalse(ranges[0].endsWith("bytes=0-"))
        assertEquals(100, pct.last())
        assertFalse(File(dir, "TotalIPTVPro-update.apk.part").exists())
    }

    @Test
    fun webPageFromDriveFallsBackToTheGithubAssetOfTheSameVersion() {
        routes["/a.apk"] = { send(it, 200, "<html>can't scan for viruses</html>" + "x".repeat(2000), "text/html") }
        routes["/gh"] = { send(it, 200, ghList("1.4.109", "$base/gh.apk")) }
        routes["/gh.apk"] = { sendApk(it) }
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = apk.size.toLong(), sha256 = apkSha), "$base/a.apk", source = "Google Drive")
        val f = engine().download(dir, ready)
        assertTrue(f.readBytes().contentEquals(apk))
        assertEquals(1, hits.count { it == "/a.apk" }, "a web page is not retried")
    }

    @Test
    fun truncatedFileThatNeverCompletesIsReportedPlainly() {
        routes["/a.apk"] = { ex -> sendCut(ex, 50_000) }
        routes["/gh"] = { send(it, 200, "[]") }
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = apk.size.toLong(), sha256 = apkSha), "$base/a.apk", source = "Google Drive")
        val e = assertFailsWith<UpdateDownloadException> { engine().download(dir, ready) }
        val msg = UpdateInstaller.friendlyError(e)
        assertTrue(msg.contains(UpdateErrors.STOPPED) || msg.contains(UpdateErrors.DAMAGED), msg)
        assertTrue(msg.startsWith("Download failed: ") && msg.endsWith("Choose Install again."), msg)
        assertEquals(3, hits.count { it == "/a.apk" })
    }

    @Test
    fun checksumMismatchIsDamagedNotRetried() {
        routes["/a.apk"] = { sendApk(it) }
        routes["/gh"] = { send(it, 200, "[]") }
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = apk.size.toLong(), sha256 = "00".repeat(32)), "$base/a.apk", source = "Google Drive")
        val e = assertFailsWith<UpdateDownloadException> { engine().download(dir, ready) }
        assertEquals(UpdateErrors.DAMAGED, UpdateErrors.reason(e))
        assertEquals(1, hits.count { it == "/a.apk" })
    }

    @Test
    fun lowStorageStopsBeforeDownloading() {
        val ready = UpdateCheckResult.Available(UpdateManifest(121, "1.4.109", size = 21_000_000), "$base/a.apk")
        val e = assertFailsWith<InsufficientSpaceException> { engine(free = 5_000_000).download(dir, ready) }
        assertTrue(UpdateErrors.reason(e).startsWith("Not enough free space"), UpdateErrors.reason(e))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun shelfNetworkRule() {
        assertFalse(UpdateSources.shelfUsableHere("http://192.168.4.33:8765/", listOf("192.168.1.20")))
        assertFalse(UpdateSources.shelfUsableHere("http://192.168.4.37:8765/", listOf("10.0.0.4")))
        assertTrue(UpdateSources.shelfUsableHere("http://192.168.4.33:8765/", listOf("192.168.4.20")))
        assertTrue(UpdateSources.shelfUsableHere("https://example.test/u/", listOf("10.0.0.4")))
        assertTrue(UpdateSources.shelfUsableHere("http://192.168.4.33:8765/", emptyList()))
        assertTrue(UpdateSources.shelfUsableHere("http://10.0.2.2:8766/", listOf("10.0.2.15")))
    }

    @Test
    fun installMismatchSaysUninstallFirstOnlyForARealKeyDifference() {
        val a = setOf("e3ee"); val b = setOf("b347")
        assertNull(UpdateSources.installMismatch("p", "p", a, a, 119, 120))
        assertEquals(UpdateSources.UNINSTALL_FIRST, UpdateSources.installMismatch("p", "p", a, b, 119, 120))
        assertTrue(UpdateSources.UNINSTALL_FIRST.contains("Uninstall Total IPTV Pro first"))
        assertNull(UpdateSources.installMismatch("p", "p", a, emptySet(), 119, 120))
        assertNotNull(UpdateSources.installMismatch("p", "q", a, a, 119, 120))
        assertNotNull(UpdateSources.installMismatch("p", null, a, a, 119, 120))
        assertNotNull(UpdateSources.installMismatch("p", "p", a, a, 120, 120))
        assertEquals(
            "Can't install: " + UpdateSources.UNINSTALL_FIRST,
            UpdateInstaller.friendlyError(InstallBlockedException(UpdateSources.UNINSTALL_FIRST))
        )
    }

    @Test
    fun smallHelpers() {
        assertTrue(UpdateSources.looksLikeWebPage("  <!doctype html>"))
        assertFalse(UpdateSources.looksLikeWebPage("{\"versionCode\":1}"))
        assertEquals("ab12", UpdateSources.shaFromDigest("sha256:AB12"))
        assertEquals("", UpdateSources.shaFromDigest(null))
        assertTrue(UpdateSources.versionFileUrl("tv").endsWith("/releases/latest/download/version-tv.json"))
        assertEquals(UpdateErrors.NO_INTERNET, UpdateErrors.reason(UnknownHostException("x")))
        assertEquals(UpdateErrors.PROXY_BLOCKED, UpdateErrors.reason(java.io.IOException("Unexpected response code for CONNECT: 403")))
        assertEquals(UpdateErrors.CANT_CONNECT, UpdateErrors.reason(java.net.ConnectException("Failed to connect to /10.0.2.2:1")))
        assertEquals("Google Drive", UpdateEngine.siteName("https://drive.usercontent.google.com/x", ""))
        assertEquals("GitHub", UpdateEngine.siteName("https://release-assets.githubusercontent.com/x", ""))
        val e = UpdateEndpoints.withOverride(UpdateEndpoints.defaults("tv"), "drive=http://10.0.2.2:1/d.json")
        assertEquals("http://10.0.2.2:1/d.json", e.driveManifestUrl)
        assertEquals(UpdateSources.GITHUB_RELEASES_URL, e.githubReleasesUrl)
        assertEquals(UpdateEndpoints.defaults("tv"), UpdateEndpoints.withOverride(UpdateEndpoints.defaults("tv"), ""))
        assertTrue(UpdateInstaller.lastProblemLine("Check failed: x", 0).startsWith("Last update problem: "))
    }

    @Test
    fun awaitedUpdateIsRestoredOnlyWhenRecent() {
        val m = """{"versionCode":121,"versionName":"1.4.109","size":5,"sha256":"ab"}"""
        val r = UpdateInstaller.awaitingFrom(m, "https://x.test/a.apk", "https://m.test/b.apk", "Google Drive", 1_000, 2_000)
        assertNotNull(r)
        assertEquals(listOf("https://m.test/b.apk"), r.mirrors)
        assertEquals(121, r.manifest.versionCode)
        assertNull(UpdateInstaller.awaitingFrom(m, "https://x.test/a.apk", "", "", 0, 3L * 24 * 3600 * 1000))
        assertNull(UpdateInstaller.awaitingFrom(null, "u", "", "", 0, 0))
    }

    @Test
    fun shippedBuildsHaveNoTestEndpoints() {
        // The field exists for emulator tests; a normal build (no env var) leaves it blank.
        if (System.getenv("TIP_TEST_UPDATE_ENDPOINTS").isNullOrBlank()) {
            assertEquals("", com.totaliptv.pro.BuildConfig.TEST_UPDATE_ENDPOINTS)
        }
    }
}

/** 1.4.107 passed BuildConfig.FLAVOR ("tvSideload") where "tv"/"phone" was expected, so Drive and GitHub never matched. */
class UpdateFlavorTest {
    @Test
    fun deviceFlavorIsTvOrPhone() {
        assertTrue(com.totaliptv.pro.BuildConfig.FLAVOR_device in setOf("tv", "phone"))
    }

    @Test
    fun defaultsForThisBuildAreSetUp() {
        val e = UpdateEndpoints.defaults(com.totaliptv.pro.BuildConfig.FLAVOR_device)
        assertTrue(e.driveManifestUrl.startsWith("https://drive.usercontent.google.com/"))
        assertTrue(e.versionFileUrl.endsWith("version-${com.totaliptv.pro.BuildConfig.FLAVOR_device}.json"))
        assertTrue(e.githubReleasesUrl.startsWith("https://api.github.com/"))
    }

    @Test
    fun updateCodeUsesDeviceFlavorOnly() {
        val checker = File("src/main/java/com/totaliptv/pro/data/update/AppUpdateChecker.kt").readText()
        val prefs = File("src/main/java/com/totaliptv/pro/data/local/AppPreferences.kt").readText()
        val bad = Regex("""BuildConfig\.FLAVOR\s*[,)]""")
        assertFalse(bad.containsMatchIn(checker), "AppUpdateChecker must use BuildConfig.FLAVOR_device")
        assertFalse(Regex("""OWNER_UPDATE_BASE_URL\),\s*BuildConfig\.FLAVOR\s*\)""").containsMatchIn(prefs))
    }

    @Test
    fun multiReasonMessageIsSentences() {
        val msg = UpdateErrors.checkFailed(
            listOf("Google Drive" to "Google Drive is not set up for this build", "GitHub" to "x failed")
        )
        assertEquals("Google Drive is not set up for this build. GitHub: x failed.", msg)
    }

    @Test
    fun sourceNamedInsideTheReasonIsNotRepeated() {
        val msg = UpdateErrors.checkFailed(
            listOf(
                "GitHub" to "GitHub had a problem (HTTP 500). Try again later.",
                "the release file" to "The update file was not found on the release file (HTTP 404)."
            )
        )
        assertEquals(
            "GitHub had a problem (HTTP 500). Try again later. The update file was not found on the release file (HTTP 404).",
            msg
        )
    }

    @Test
    fun httpReasonStartsWithACapital() {
        assertEquals(
            "The home shelf had a problem (HTTP 502). Try again later.",
            UpdateErrors.reason(HttpStatusException(502, "the home shelf"))
        )
    }

    @Test
    fun sameReasonFromSeveralSourcesIsSaidOnce() {
        val p = UpdateErrors.PROXY_BLOCKED
        val msg = UpdateErrors.checkFailed(
            listOf("Google Drive" to p, "GitHub" to p, "the release file" to p, "the home shelf" to UpdateErrors.WEB_PAGE)
        )
        assertEquals(
            "Google Drive, GitHub and the release file: $p The home shelf: ${UpdateErrors.WEB_PAGE}",
            msg
        )
    }
}
