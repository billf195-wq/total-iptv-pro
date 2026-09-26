package com.totaliptv.pro.desktop.player

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object VlcControl {

    fun sendCommand(port: Int, command: String): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 600)
                socket.soTimeout = 800
                socket.getOutputStream().write("$command\n".toByteArray(StandardCharsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun setVolume(port: Int, volume: Int): Boolean {
        // volume 0 = mute, volume 256 = 100% standard volume in VLC
        return sendCommand(port, "volume $volume")
    }

    fun stopPlayer(port: Int): Boolean {
        return sendCommand(port, "stop")
    }

    data class PlaybackProgress(
        val currentTimeSeconds: Long,
        val totalLengthSeconds: Long,
        val percent: Int
    )

    fun queryProgress(port: Int = 4214): PlaybackProgress? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 600
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                out.write("get_time\nget_length\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()

                val sb = StringBuilder()
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val read = inp.read(buffer)
                        if (read <= 0) break
                        sb.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                        val numbers = sb.lines().map { it.trim() }.mapNotNull { it.toLongOrNull() }
                        if (numbers.size >= 2) {
                            val timeSec = numbers[0]
                            val lengthSec = numbers[1]
                            if (lengthSec > 0) {
                                val pct = ((timeSec.toDouble() / lengthSec.toDouble()) * 100).toInt().coerceIn(0, 100)
                                return PlaybackProgress(timeSec, lengthSec, pct)
                            } else if (timeSec > 0) {
                                return PlaybackProgress(timeSec, 0, 0)
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** VLC RC `status` reports `( state ended )` when the input reaches EOF. */
    fun queryEnded(port: Int = 4214): Boolean {
        val text = readCommand(port, "status") ?: return false
        return Regex("""\(\s*state\s+ended\s*\)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }

    private fun readCommand(port: Int, command: String): String? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 500)
                socket.soTimeout = 600
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()
                out.write("$command\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
                val sb = StringBuilder()
                val buffer = ByteArray(1024)
                val deadline = System.currentTimeMillis() + 600
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val read = inp.read(buffer)
                        if (read <= 0) break
                        sb.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                        if (sb.contains("state")) break
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                }
                sb.toString().ifBlank { null }
            }
        } catch (_: Exception) {
            null
        }
    }
}
