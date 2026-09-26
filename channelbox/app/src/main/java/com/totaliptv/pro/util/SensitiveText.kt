package com.totaliptv.pro.util

/**
 * Xtream playback URLs embed the username and password in the path.
 * Logs and on-screen errors must not repeat them, and an update failure
 * must not name a private LAN address.
 */
object SensitiveText {
    private val querySecret = Regex("""(?i)([?&](?:username|password|user|pass)=)[^&\s#]+""")
    private val pathSecret = Regex("""(?i)(/(?:live|movie|series)/)[^/\s]+/[^/\s]+/""")
    private val privateIp = Regex(
        """\b(?:192\.168(?:\.\d{1,3}){2}|10(?:\.\d{1,3}){3}|172\.(?:1[6-9]|2\d|3[0-1])(?:\.\d{1,3}){2})\b"""
    )

    fun redact(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return pathSecret.replace(querySecret.replace(raw, "$1***"), "$1***/***/")
    }

    /** Logcat line: exception type plus a redacted message, never the raw stack. */
    fun safeLog(error: Throwable?): String {
        val name = error?.javaClass?.simpleName ?: "Error"
        val msg = redact(error?.message)
        return if (msg.isBlank()) name else "$name: $msg"
    }

    fun forUser(error: Throwable?): String = forUser(error?.message)

    fun forUser(raw: String?): String {
        val cleaned = privateIp.replace(redact(raw), "a local network")
            .replace(Regex("""https?://\S+"""), "the server")
            .trim()
        if (cleaned.isBlank()) {
            return "Something went wrong. Check the server address and try again."
        }
        if (cleaned.contains("***") || cleaned.contains("player_api", ignoreCase = true)) {
            return "Could not reach the server. Check the address, username, and password."
        }
        return cleaned.take(160)
    }

    fun isStorageFailure(error: Throwable): Boolean {
        val msg = generateSequence(error) { it.cause }
            .joinToString(" ") { it.message.orEmpty() }
            .lowercase()
        return msg.contains("enospc") ||
            msg.contains("no space") ||
            msg.contains("disk full") ||
            msg.contains("not enough space")
    }
}
