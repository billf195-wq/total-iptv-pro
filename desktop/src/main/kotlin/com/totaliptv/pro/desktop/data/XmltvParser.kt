package com.totaliptv.pro.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

object XmltvParser {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val xmltvDateFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyyMMddHHmmss")
        .optionalStart()
        .appendPattern(" Z")
        .optionalEnd()
        .optionalStart()
        .appendPattern("Z")
        .optionalEnd()
        .toFormatter()

    fun loadFromUrl(url: String): Map<String, List<EpgProgram>> {
        val req = Request.Builder()
            .url(url.trim())
            .header("User-Agent", "TotalIPTVPro-Desktop/1.0")
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
        val factory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }
        val reader: XMLStreamReader = factory.createXMLStreamReader(decodedStream)

        try {
            var inProgramme = false
            var currentChannelId: String? = null
            var currentStartMs = 0L
            var currentEndMs = 0L
            var currentTitle = ""
            var currentDesc: String? = null
            var currentTag = ""

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        currentTag = reader.localName
                        if (currentTag.equals("programme", ignoreCase = true)) {
                            inProgramme = true
                            currentChannelId = reader.getAttributeValue(null, "channel")
                            val startStr = reader.getAttributeValue(null, "start")
                            val stopStr = reader.getAttributeValue(null, "stop")
                            currentStartMs = parseXmltvTime(startStr)
                            currentEndMs = parseXmltvTime(stopStr)
                            currentTitle = ""
                            currentDesc = null
                        }
                    }
                    XMLStreamConstants.CHARACTERS -> {
                        if (inProgramme) {
                            val text = reader.text.trim()
                            if (text.isNotEmpty()) {
                                if (currentTag.equals("title", ignoreCase = true)) {
                                    currentTitle = if (currentTitle.isEmpty()) text else "$currentTitle $text"
                                } else if (currentTag.equals("desc", ignoreCase = true)) {
                                    currentDesc = if (currentDesc == null) text else "$currentDesc $text"
                                }
                            }
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        val endTag = reader.localName
                        if (endTag.equals("programme", ignoreCase = true)) {
                            if (inProgramme && !currentChannelId.isNullOrBlank() && currentStartMs > 0 && currentEndMs > currentStartMs) {
                                val prog = EpgProgram(
                                    id = "$currentChannelId-$currentStartMs",
                                    title = currentTitle.ifBlank { "Program" },
                                    description = currentDesc,
                                    startMs = currentStartMs,
                                    endMs = currentEndMs,
                                    channelStreamId = 0
                                )
                                programsByChannel.getOrPut(currentChannelId) { mutableListOf() }.add(prog)
                            }
                            inProgramme = false
                            currentChannelId = null
                        }
                        currentTag = ""
                    }
                }
            }
        } catch (_: Exception) {
            // Return whatever parsed before error/EOF
        } finally {
            runCatching { reader.close() }
            runCatching { decodedStream.close() }
        }

        programsByChannel.values.forEach { it.sortBy { p -> p.startMs } }
        return programsByChannel
    }

    private fun parseXmltvTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val clean = raw.trim()
        return try {
            val spaceIdx = clean.indexOf(' ')
            if (spaceIdx > 0 && clean.length >= 14) {
                val dateTimePart = clean.substring(0, 14)
                val tzPart = clean.substring(spaceIdx + 1).trim()
                val parsed = OffsetDateTime.parse("$dateTimePart $tzPart", xmltvDateFormatter)
                parsed.toInstant().toEpochMilli()
            } else if (clean.length >= 14) {
                val parsed = OffsetDateTime.parse(clean.take(14) + " +0000", xmltvDateFormatter)
                parsed.toInstant().toEpochMilli()
            } else {
                0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
