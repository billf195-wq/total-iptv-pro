package com.totaliptv.pro.data

import com.totaliptv.pro.data.model.EpgProgram
import com.totaliptv.pro.data.model.MediaItem
import java.io.File
import java.net.URLEncoder

/**
 * One xmltv.php download, trimmed to the guide window and stored on disk so
 * reopening the guide does not call the provider again.
 */
object GuideBulkCache {
    const val FILE_NAME = "guide-epg-18h.tsv"
    const val TTL_MS = 30L * 60L * 1000L

    fun xmltvUrl(baseUrl: String, username: String, password: String): String {
        val base = baseUrl.trim().trimEnd('/')
        val user = URLEncoder.encode(username, "UTF-8")
        val pass = URLEncoder.encode(password, "UTF-8")
        return "$base/xmltv.php?username=$user&password=$pass"
    }

    fun isFresh(file: File, nowMs: Long, ttlMs: Long = TTL_MS): Boolean =
        file.isFile && file.length() > 0L && nowMs - file.lastModified() < ttlMs

    fun indexByStream(
        byXmlId: Map<String, List<EpgProgram>>,
        channels: List<MediaItem>
    ): Map<Int, List<EpgProgram>> {
        if (byXmlId.isEmpty() || channels.isEmpty()) return emptyMap()
        val out = HashMap<Int, List<EpgProgram>>()
        for (ch in channels) {
            val sid = ch.xtreamStreamId ?: continue
            val programs = byXmlId[ch.epgChannelId?.trim().orEmpty()]
                ?: byXmlId[sid.toString()]
                ?: byXmlId[ch.name.trim()]
                ?: continue
            if (programs.isNotEmpty()) out[sid] = programs
        }
        return out
    }

    fun write(file: File, byStream: Map<Int, List<EpgProgram>>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            out.append("# tip-guide-epg 1")
            out.newLine()
            for ((sid, programs) in byStream) {
                for (p in programs) {
                    val title = p.title.replace('\t', ' ').replace('\n', ' ')
                    out.append(sid.toString())
                    out.append('\t')
                    out.append(p.startMs.toString())
                    out.append('\t')
                    out.append(p.endMs.toString())
                    out.append('\t')
                    out.append(title)
                    out.newLine()
                }
            }
        }
    }

    fun read(file: File): Map<Int, List<EpgProgram>> {
        if (!file.isFile) return emptyMap()
        val out = HashMap<Int, MutableList<EpgProgram>>()
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val parts = line.split('\t', limit = 4)
                if (parts.size < 4) return@forEach
                val sid = parts[0].toIntOrNull() ?: return@forEach
                val start = parts[1].toLongOrNull() ?: return@forEach
                val end = parts[2].toLongOrNull() ?: return@forEach
                if (end <= start) return@forEach
                out.getOrPut(sid) { mutableListOf() }.add(
                    EpgProgram(title = parts[3], startMs = start, endMs = end, channelStreamId = sid)
                )
            }
        }
        return out
    }
}
