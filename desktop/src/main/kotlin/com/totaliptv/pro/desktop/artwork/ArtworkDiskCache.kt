package com.totaliptv.pro.desktop.artwork

import com.totaliptv.pro.desktop.util.AppPaths
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Encoded-byte cache so a second visit does not download the same poster again. */
class ArtworkDiskCache(
    private val root: Path,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    fun read(url: String): ByteArray? {
        val file = fileFor(url)
        if (!Files.isRegularFile(file)) return null
        return runCatching {
            val bytes = Files.readAllBytes(file)
            if (bytes.isEmpty()) null else bytes
        }.getOrNull()?.also {
            runCatching { Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
        }
    }

    fun write(url: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        runCatching {
            Files.createDirectories(root)
            val file = fileFor(url)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.write(tmp, bytes)
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            evict()
        }
    }

    fun fileFor(url: String): Path = root.resolve(sha256(url) + ".img")

    private fun evict() {
        val files = Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".img") }
                .toList()
        }
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        if (total <= maxBytes) return
        val oldest = files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
        for (file in oldest) {
            if (total <= maxBytes) break
            val size = runCatching { Files.size(file) }.getOrDefault(0L)
            if (runCatching { Files.deleteIfExists(file) }.getOrDefault(false)) {
                total -= size
            }
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L * 1024L

        fun shared(): ArtworkDiskCache = ArtworkDiskCache(AppPaths.configDir.resolve("artwork-cache"))

        fun sha256(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
