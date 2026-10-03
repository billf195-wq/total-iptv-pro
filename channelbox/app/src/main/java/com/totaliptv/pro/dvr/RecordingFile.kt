package com.totaliptv.pro.dvr

import java.io.File
import java.io.RandomAccessFile

/**
 * An interrupted MPEG-TS capture often ends mid-packet. ExoPlayer then fails
 * with "read position out of range" when it walks that last fragment.
 */
object RecordingFile {
    const val TS_PACKET = 188

    fun isTransportStream(path: String): Boolean {
        val name = path.substringAfterLast('/').substringBefore('?').lowercase()
        return name.endsWith(".ts") || name.endsWith(".mpg") || name.endsWith(".mpeg")
    }

    fun isLocalPath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed.startsWith("/") || trimmed.startsWith("file:")
    }

    /** Shorten [file] to a whole number of 188-byte packets. Returns the kept length. */
    fun trimToWholePackets(file: File): Long {
        if (!file.isFile) return 0L
        val len = file.length()
        if (len <= 0L || !isTransportStream(file.name)) return len
        val keep = (len / TS_PACKET) * TS_PACKET
        if (keep in 1 until len) {
            RandomAccessFile(file, "rw").use { it.setLength(keep) }
        }
        return if (keep > 0L) keep else len
    }
}
