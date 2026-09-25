package com.totaliptv.pro.data.m3u

import android.util.Xml
import com.totaliptv.pro.data.model.EpgProgram
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

object XmltvParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun loadFromUrl(url: String): Map<String, List<EpgProgram>> {
        val req = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "TotalIPTVPro/1.4")
            .get()
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val stream = resp.body?.byteStream() ?: return emptyMap()
            parseStream(stream, url.endsWith(".gz", ignoreCase = true))
        }
    }

    fun parseStream(rawStream: InputStream, isGzipHint: Boolean = false): Map<String, List<EpgProgram>> {
        val buffered = BufferedInputStream(rawStream)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        val isGzip = isGzipHint || (b1 == 0x1f && b2 == 0x8b)
        val decodedStream = if (isGzip) GZIPInputStream(buffered) else buffered

        val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(decodedStream, "UTF-8")
        }

        try {
            var eventType = parser.eventType
            var inProgramme = false
            var currentChannelId: String? = null
            var currentStartMs = 0L
            var currentEndMs = 0L
            var currentTitle = ""
            var currentDesc: String? = null
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase(Locale.ROOT)
                        if (currentTag == "programme") {
                            inProgramme = true
                            currentChannelId = parser.getAttributeValue(null, "channel")
                            val startStr = parser.getAttributeValue(null, "start")
                            val stopStr = parser.getAttributeValue(null, "stop")
                            currentStartMs = parseXmltvDate(startStr)
                            currentEndMs = parseXmltvDate(stopStr)
                            currentTitle = ""
                            currentDesc = null
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inProgramme) {
                            val text = parser.text.trim()
                            if (text.isNotEmpty()) {
                                when (currentTag) {
                                    "title" -> currentTitle = if (currentTitle.isEmpty()) text else "$currentTitle $text"
                                    "desc" -> currentDesc = if (currentDesc == null) text else "$currentDesc $text"
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val endTag = parser.name.lowercase(Locale.ROOT)
                        if (endTag == "programme") {
                            if (inProgramme && !currentChannelId.isNullOrBlank() && currentStartMs > 0 && currentEndMs > currentStartMs) {
                                val prog = EpgProgram(
                                    title = currentTitle.ifBlank { "Program" },
                                    description = currentDesc,
                                    startMs = currentStartMs,
                                    endMs = currentEndMs
                                )
                                programsByChannel.getOrPut(currentChannelId) { mutableListOf() }.add(prog)
                            }
                            inProgramme = false
                            currentChannelId = null
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Return whatever parsed successfully before error
        } finally {
            runCatching { decodedStream.close() }
        }

        programsByChannel.values.forEach { it.sortBy { p -> p.startMs } }
        return programsByChannel
    }

    private fun parseXmltvDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim()
        val digits = clean.take(14)
        if (digits.length < 14) return 0L

        return try {
            val spaceIdx = clean.indexOf(' ')
            val tz = if (spaceIdx > 0 && clean.length > spaceIdx + 1) {
                clean.substring(spaceIdx + 1).trim()
            } else {
                "+0000"
            }
            val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT$tz")
            }
            fmt.parse("$digits $tz")?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
