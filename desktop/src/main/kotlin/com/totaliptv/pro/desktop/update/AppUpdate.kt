package com.totaliptv.pro.desktop.update

import com.totaliptv.pro.desktop.AppVersion
import com.totaliptv.pro.desktop.util.AppPaths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

@Serializable
data class RemoteVersion(
    val versionCode: Int,
    val versionName: String,
    /** Default / Linux artifact (historically TotalIptvPro-linux.tar.gz). */
    val file: String = "TotalIptvPro-linux.tar.gz",
    /** Optional Windows zip; when present, Windows clients prefer this. */
    val fileWindows: String? = null,
    /** Optional Linux override; when blank, [file] is used on Linux. */
    val fileLinux: String? = null
) {
    fun artifactForCurrentOs(): String {
        return when {
            AppPaths.isWindows -> {
                fileWindows?.takeIf { it.isNotBlank() }
                    ?: file.takeIf { it.endsWith(".zip", ignoreCase = true) }
                    ?: AppUpdatePaths.WINDOWS_ZIP_NAME
            }
            else -> {
                fileLinux?.takeIf { it.isNotBlank() }
                    ?: file.takeIf { it.isNotBlank() }
                    ?: AppUpdatePaths.TARBALL_NAME
            }
        }
    }
}

enum class UpdatePhase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Downloading,
    ReadyToRestart,
    Error
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val message: String = "",
    val localVersionName: String = AppVersion.VERSION_NAME,
    val localVersionCode: Int = AppVersion.VERSION_CODE,
    val remote: RemoteVersion? = null,
    val shelfBaseUrl: String? = null,
    /** Absolute path to the installed app binary after a successful extract. */
    val installedBinary: String? = null
)

object AppUpdatePaths {
    val installRoot: Path = when {
        AppPaths.isWindows -> {
            val local = System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            UpdateSources.windowsInstallRoot(local)
        }
        else -> Path.of(System.getProperty("user.home"), ".local", "share", "total-iptv-pro", "app")
    }

    val desktopFile: Path =
        Path.of(System.getProperty("user.home"), ".local", "share", "applications", "total-iptv-pro.desktop")

    val cacheDir: Path = when {
        AppPaths.isWindows -> {
            val local = System.getenv("LOCALAPPDATA")
                ?.takeIf { it.isNotBlank() }
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local").toString()
            Path.of(local, "TotalIptvPro", "updates")
        }
        else -> Path.of(System.getProperty("user.home"), ".cache", "total-iptv-pro", "updates")
    }

    const val DEFAULT_SHELF = UpdateSources.GITHUB_RELEASES_PAGE
    const val TARBALL_NAME = "TotalIptvPro-linux.tar.gz"
    const val WINDOWS_ZIP_NAME = "TotalIptvPro-windows.zip"

    fun defaultRelaunchHint(): String = when {
        AppPaths.isWindows -> installRoot.resolve("TotalIptvPro.exe").toString()
        else -> installRoot.resolve("bin").resolve("TotalIptvPro").toString()
    }
}

/**
 * Desktop in-app updater: GET shelf/version.json, download OS archive, extract into
 * install root, refresh .desktop Exec= on Linux, offer restart.
 */
class AppUpdateManager {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    fun localState(message: String = ""): UpdateUiState =
        UpdateUiState(
            phase = UpdatePhase.Idle,
            message = message,
            localVersionName = AppVersion.VERSION_NAME,
            localVersionCode = AppVersion.VERSION_CODE
        )

    fun check(shelfBaseUrl: String): UpdateUiState {
        val localCode = AppVersion.VERSION_CODE
        val localName = AppVersion.VERSION_NAME
        val baseNorm = normalizeShelf(shelfBaseUrl)
        if (UpdateSources.usesGithubReleases(baseNorm)) {
            return checkGithub(localCode, localName, baseNorm)
        }
        val url = baseNorm + "version.json"
        return try {
            val body = httpGetString(url)
                ?: return UpdateUiState(
                    phase = UpdatePhase.Error,
                    message = "Could not fetch $url",
                    localVersionName = localName,
                    localVersionCode = localCode,
                    shelfBaseUrl = baseNorm
                )
            val remote = json.decodeFromString(RemoteVersion.serializer(), body)
            val artifact = remote.artifactForCurrentOs()
            if (!isSupportedArchive(artifact)) {
                return UpdateUiState(
                    phase = UpdatePhase.Error,
                    message = if (AppPaths.isWindows) {
                        "No Windows update package on shelf yet (need .zip). Shelf file: $artifact"
                    } else {
                        "Invalid shelf file name: $artifact"
                    },
                    localVersionName = localName,
                    localVersionCode = localCode,
                    remote = remote,
                    shelfBaseUrl = baseNorm
                )
            }
            // On Windows, if shelf only has linux tarball and no fileWindows, treat as no Windows package
            if (AppPaths.isWindows &&
                remote.fileWindows.isNullOrBlank() &&
                !remote.file.endsWith(".zip", ignoreCase = true)
            ) {
                // Probe whether the preferred windows zip exists; if not, report clearly
                val probe = baseNorm + artifact
                if (!httpExists(probe)) {
                    return UpdateUiState(
                        phase = UpdatePhase.UpToDate,
                        message = "Up to date — $localName ($localCode). (No Windows package on shelf yet.)",
                        localVersionName = localName,
                        localVersionCode = localCode,
                        remote = remote,
                        shelfBaseUrl = baseNorm
                    )
                }
            }
            if (remote.versionCode > localCode) {
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
                    message = "Up to date — ${localName} ($localCode)",
                    localVersionName = localName,
                    localVersionCode = localCode,
                    remote = remote,
                    shelfBaseUrl = baseNorm
                )
            }
        } catch (t: Throwable) {
            UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Check failed: ${t.message ?: t.javaClass.simpleName}",
                localVersionName = localName,
                localVersionCode = localCode,
                shelfBaseUrl = baseNorm
            )
        }
    }

    fun downloadAndInstall(remote: RemoteVersion, shelfBaseUrl: String): UpdateUiState {
        val localCode = AppVersion.VERSION_CODE
        val localName = AppVersion.VERSION_NAME
        val baseNorm = normalizeShelf(shelfBaseUrl)
        val artifact = remote.artifactForCurrentOs().trim()
        val fileName = artifact.substringAfterLast('/')
        val url = if (artifact.startsWith("https://") || artifact.startsWith("http://")) {
            artifact
        } else {
            baseNorm + fileName
        }
        return try {
            if (!isSupportedArchive(fileName)) {
                throw IllegalStateException("Unsupported archive for this OS: $fileName")
            }
            Files.createDirectories(AppUpdatePaths.cacheDir)
            val archive = AppUpdatePaths.cacheDir.resolve(fileName).toFile()
            if (archive.exists()) archive.delete()
            httpDownload(url, archive)
            if (!archive.exists() || archive.length() < 1024L) {
                throw IllegalStateException("Downloaded archive is empty or missing")
            }

            val staging = AppUpdatePaths.cacheDir.resolve("extract-${System.currentTimeMillis()}")
            if (Files.exists(staging)) deleteRecursive(staging)
            Files.createDirectories(staging)
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                extractZip(archive.toPath(), staging)
            } else {
                extractTarGz(archive.toPath(), staging)
            }

            val appDir = findAppDir(staging)
                ?: throw IllegalStateException("Could not find app binary inside archive")

            val binary = if (AppPaths.isWindows) {
                // Running exe/jars in the install folder are locked. Stage the new tree
                // and swap it after this process exits.
                val stagedDir = AppUpdatePaths.cacheDir.resolve("staged-update")
                if (Files.exists(stagedDir)) deleteRecursive(stagedDir)
                Files.createDirectories(stagedDir)
                copyTree(appDir, stagedDir)
                deleteRecursive(staging)
                val script = AppUpdatePaths.cacheDir.resolve("apply-update.bat")
                Files.writeString(script, windowsSwapScript())
                AppUpdatePaths.installRoot.resolve("TotalIptvPro.exe")
            } else {
                val installRoot = AppUpdatePaths.installRoot
                if (Files.exists(installRoot)) deleteRecursive(installRoot)
                Files.createDirectories(installRoot.parent)
                copyTree(appDir, installRoot)
                val installed = findBinary(installRoot)
                    ?: throw IllegalStateException("Installed app has no runnable binary")
                installed.toFile().setExecutable(true, false)
                updateDesktopEntry(installed)
                deleteRecursive(staging)
                installed
            }

            UpdateUiState(
                phase = UpdatePhase.ReadyToRestart,
                message = "Installed ${remote.versionName} — restart to finish",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = baseNorm,
                installedBinary = binary.toAbsolutePath().toString()
            )
        } catch (t: Throwable) {
            UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Update failed: ${t.message ?: t.javaClass.simpleName}",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = baseNorm
            )
        }
    }

    fun restartNow(binaryPath: String?) {
        if (AppPaths.isWindows) {
            val script = AppUpdatePaths.cacheDir.resolve("apply-update.bat")
            val stagedDir = AppUpdatePaths.cacheDir.resolve("staged-update")
            val installRoot = AppUpdatePaths.installRoot
            val targetExe = binaryPath?.takeIf { it.isNotBlank() }
                ?: installRoot.resolve("TotalIptvPro.exe").toAbsolutePath().toString()
            if (Files.exists(script) && Files.exists(stagedDir)) {
                ProcessBuilder(
                    "cmd.exe", "/c", "start", "",
                    script.toAbsolutePath().toString(),
                    stagedDir.toAbsolutePath().toString(),
                    installRoot.toAbsolutePath().toString(),
                    targetExe
                ).start()
                Thread {
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                    }
                    kotlin.system.exitProcess(0)
                }.start()
                return
            }
        }
        val bin = binaryPath?.takeIf { it.isNotBlank() && File(it).exists() }
            ?: findBinary(AppUpdatePaths.installRoot)?.toAbsolutePath()?.toString()
            ?: throw IllegalStateException("No installed binary to relaunch")
        val file = File(bin)
        if (!AppPaths.isWindows && !file.canExecute()) {
            throw IllegalStateException("No installed binary to relaunch")
        }
        ProcessBuilder(bin)
            .directory(file.parentFile)
            .redirectErrorStream(true)
            .start()
        Thread {
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
            }
            kotlin.system.exitProcess(0)
        }.start()
    }

    companion object {
        fun normalizeShelf(url: String): String {
            val t = UpdateSources.shelfForCheck(url)
            return if (t.endsWith("/")) t else "$t/"
        }
    }

    private fun checkGithub(localCode: Int, localName: String, baseNorm: String): UpdateUiState {
        return try {
            val windows = AppPaths.isWindows
            val pages = ArrayList<String>(UpdateSources.GITHUB_RELEASE_LIST_PAGES)
            for (page in 1..UpdateSources.GITHUB_RELEASE_LIST_PAGES) {
                val body = httpGetString(UpdateSources.releasesListUrl(page))
                if (body == null) {
                    if (pages.isEmpty()) {
                        return UpdateUiState(
                            phase = UpdatePhase.Error,
                            message = "Could not reach GitHub Releases",
                            localVersionName = localName,
                            localVersionCode = localCode,
                            shelfBaseUrl = baseNorm
                        )
                    }
                    break
                }
                pages += body
                val remote = GithubRelease.select(listOf(body), windows, localName, localCode)
                if (remote != null) return githubVersionState(remote, localCode, localName, baseNorm)
                if (GithubRelease.pageEnded(body)) break
            }
            UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Latest GitHub release has no desktop package yet",
                localVersionName = localName,
                localVersionCode = localCode,
                shelfBaseUrl = baseNorm
            )
        } catch (t: Throwable) {
            UpdateUiState(
                phase = UpdatePhase.Error,
                message = "Check failed: ${t.message ?: t.javaClass.simpleName}",
                localVersionName = localName,
                localVersionCode = localCode,
                shelfBaseUrl = baseNorm
            )
        }
    }

    private fun githubVersionState(
        remote: RemoteVersion,
        localCode: Int,
        localName: String,
        baseNorm: String
    ): UpdateUiState {
        return if (remote.versionCode > localCode || UpdateSources.isNewerName(remote.versionName, localName)) {
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
                message = "Up to date — $localName ($localCode)",
                localVersionName = localName,
                localVersionCode = localCode,
                remote = remote,
                shelfBaseUrl = baseNorm
            )
        }
    }

    private fun windowsSwapScript(): String = """
        @echo off
        setlocal
        set "SRC=%~1"
        set "DEST=%~2"
        set "EXE=%~3"
        timeout /t 2 /nobreak >nul 2>&1
        for /l %%i in (1,1,10) do (
            2>nul ( >>"%DEST%\TotalIptvPro.exe" (call ) ) && goto :ready
            timeout /t 1 /nobreak >nul 2>&1
        )
        :ready
        robocopy "%SRC%" "%DEST%" /E /IS /IT /NP /NJH /NJS >nul
        start "" "%EXE%"
        rd /s /q "%SRC%" >nul 2>&1
        exit /b 0
    """.trimIndent()


    private fun isSupportedArchive(name: String): Boolean {
        val n = name.trim().substringAfterLast('/')
        return when {
            AppPaths.isWindows -> n.endsWith(".zip", ignoreCase = true)
            else -> n.endsWith(".tar.gz", ignoreCase = true) || n.endsWith(".tgz", ignoreCase = true)
        }
    }

    private fun httpExists(url: String): Boolean {
        return try {
            val head = Request.Builder().url(url).head().build()
            client.newCall(head).execute().use { resp ->
                if (resp.isSuccessful) return true
            }
            // Some shelves reject HEAD; try a ranged GET
            val get = Request.Builder().url(url).header("Range", "bytes=0-0").get().build()
            client.newCall(get).execute().use { resp -> resp.isSuccessful || resp.code == 206 }
        } catch (_: Exception) {
            false
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

    private fun extractZip(archive: Path, destDir: Path) {
        ZipInputStream(BufferedInputStream(Files.newInputStream(archive))).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val outPath = destDir.resolve(entry.name).normalize()
                if (!outPath.startsWith(destDir)) {
                    throw IllegalStateException("Zip path escapes dest: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(outPath)
                } else {
                    Files.createDirectories(outPath.parent)
                    Files.newOutputStream(outPath).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
            }
        }
    }

    private fun extractTarGz(archive: Path, destDir: Path) {
        val tar = listOf("/usr/bin/tar", "/bin/tar").firstOrNull { File(it).canExecute() }
        if (tar != null) {
            val pb = ProcessBuilder(
                tar, "-xzf", archive.toAbsolutePath().toString(),
                "-C", destDir.toAbsolutePath().toString()
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            if (code != 0) {
                throw IllegalStateException("tar extract failed ($code): ${out.take(400)}")
            }
            return
        }
        extractTarGzJvm(archive, destDir)
    }

    private fun extractTarGzJvm(archive: Path, destDir: Path) {
        BufferedInputStream(Files.newInputStream(archive)).use { fis ->
            GZIPInputStream(fis).use { gzis ->
                val buf = ByteArray(512)
                while (true) {
                    val header = ByteArray(512)
                    var read = 0
                    while (read < 512) {
                        val n = gzis.read(header, read, 512 - read)
                        if (n < 0) break
                        read += n
                    }
                    if (read == 0) break
                    if (read < 512) throw IllegalStateException("Corrupt tar header")
                    if (header.all { it.toInt() == 0 }) break
                    val nameBytes = header.copyOfRange(0, 100)
                    val name = nameBytes.takeWhile { it.toInt() != 0 }.toByteArray().toString(Charsets.US_ASCII)
                    val sizeOctal = header.copyOfRange(124, 136)
                        .takeWhile { it.toInt() != 0 && it.toInt() != 0x20 }
                        .toByteArray().toString(Charsets.US_ASCII).trim()
                    val size = if (sizeOctal.isBlank()) 0L else sizeOctal.toLong(8)
                    val typeFlag = header[156].toInt().toChar()
                    val outPath = destDir.resolve(name).normalize()
                    if (!outPath.startsWith(destDir)) {
                        throw IllegalStateException("Tar path escapes dest: $name")
                    }
                    when (typeFlag) {
                        '5', '/' -> Files.createDirectories(outPath)
                        '0', '\u0000' -> {
                            Files.createDirectories(outPath.parent)
                            Files.newOutputStream(outPath).use { out ->
                                var remaining = size
                                while (remaining > 0) {
                                    val chunk = minOf(buf.size.toLong(), remaining).toInt()
                                    val n = gzis.read(buf, 0, chunk)
                                    if (n < 0) throw IllegalStateException("Unexpected EOF in tar")
                                    out.write(buf, 0, n)
                                    remaining -= n
                                }
                            }
                            val pad = ((512 - (size % 512)) % 512).toInt()
                            var skip = pad.toLong()
                            while (skip > 0) {
                                val n = gzis.skip(skip)
                                if (n <= 0) {
                                    if (gzis.read() < 0) break
                                    skip--
                                } else {
                                    skip -= n
                                }
                            }
                        }
                        else -> {
                            var remaining = size
                            while (remaining > 0) {
                                val chunk = minOf(buf.size.toLong(), remaining).toInt()
                                val n = gzis.read(buf, 0, chunk)
                                if (n < 0) break
                                remaining -= n
                            }
                            val pad = ((512 - (size % 512)) % 512).toInt()
                            var skip = pad.toLong()
                            while (skip > 0) {
                                val n = gzis.skip(skip)
                                if (n <= 0) {
                                    if (gzis.read() < 0) break
                                    skip--
                                } else skip -= n
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findAppDir(staging: Path): Path? {
        val candidates = ArrayList<Path>()
        Files.walk(staging).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { file ->
                val name = file.fileName.toString()
                val isApp = name.equals("TotalIptvPro", ignoreCase = true) ||
                    name.equals("TotalIptvPro.exe", ignoreCase = true) ||
                    name.equals("total-iptv-pro", ignoreCase = true) ||
                    name.equals("TotalIptvPro.bat", ignoreCase = true)
                if (isApp) {
                    val parentName = file.parent?.fileName?.toString()
                    if (Files.isExecutable(file) || parentName == "bin" ||
                        name.endsWith(".exe", ignoreCase = true) ||
                        name.endsWith(".bat", ignoreCase = true)
                    ) {
                        candidates.add(file)
                    }
                }
            }
        }
        // Prefer .exe on Windows, then bin/ launcher
        val binary = when {
            AppPaths.isWindows ->
                candidates.firstOrNull { it.fileName.toString().endsWith(".exe", ignoreCase = true) }
                    ?: candidates.firstOrNull()
            else -> candidates.firstOrNull()
        } ?: return null
        val parent = binary.parent ?: return null
        return if (parent.fileName.toString().equals("bin", ignoreCase = true)) {
            parent.parent ?: parent
        } else {
            parent
        }
    }

    private fun findBinary(installRoot: Path): Path? {
        if (!Files.exists(installRoot)) return null
        if (AppPaths.isWindows) {
            val exe1 = installRoot.resolve("TotalIptvPro.exe")
            if (Files.isRegularFile(exe1)) return exe1
            val exe2 = installRoot.resolve("bin").resolve("TotalIptvPro.exe")
            if (Files.isRegularFile(exe2)) return exe2
            val bat = installRoot.resolve("TotalIptvPro.bat")
            if (Files.isRegularFile(bat)) return bat
            Files.walk(installRoot).use { stream ->
                return stream
                    .filter { Files.isRegularFile(it) }
                    .filter {
                        val n = it.fileName.toString()
                        n.equals("TotalIptvPro.exe", ignoreCase = true)
                    }
                    .findFirst()
                    .orElse(null)
            }
        }
        val bin1 = installRoot.resolve("bin").resolve("TotalIptvPro")
        if (Files.isRegularFile(bin1)) return bin1
        val bin2 = installRoot.resolve("TotalIptvPro")
        if (Files.isRegularFile(bin2)) return bin2
        Files.walk(installRoot).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter {
                    val n = it.fileName.toString()
                    n.equals("TotalIptvPro", ignoreCase = true) ||
                        n.equals("total-iptv-pro", ignoreCase = true)
                }
                .findFirst()
                .orElse(null)
        }
    }

    private fun updateDesktopEntry(binary: Path) {
        if (AppPaths.isWindows) return
        val desktop = AppUpdatePaths.desktopFile
        Files.createDirectories(desktop.parent)
        val icon = Path.of(
            System.getProperty("user.home"),
            ".local", "share", "icons", "total-iptv-pro-banner.png"
        )
        val iconLine = if (Files.exists(icon)) {
            "Icon=${icon.toAbsolutePath()}"
        } else {
            "Icon=total-iptv-pro"
        }
        val content = """
            |[Desktop Entry]
            |Version=1.1
            |Type=Application
            |Name=Total IPTV Pro
            |Comment=Total IPTV Pro Desktop
            |Exec=${binary.toAbsolutePath()}
            |Path=${binary.parent.toAbsolutePath()}
            |$iconLine
            |Terminal=false
            |Categories=AudioVideo;Player;TV;
            |StartupNotify=true
            |StartupWMClass=com-totaliptv-pro-desktop-MainKt
            |""".trimMargin()
        Files.writeString(desktop, content)
    }

    private fun copyTree(src: Path, dest: Path) {
        Files.walk(src).use { stream ->
            stream.forEach { p ->
                val rel = src.relativize(p)
                val target = dest.resolve(rel.toString())
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                    if (Files.isExecutable(p) && !AppPaths.isWindows) {
                        target.toFile().setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun deleteRecursive(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
