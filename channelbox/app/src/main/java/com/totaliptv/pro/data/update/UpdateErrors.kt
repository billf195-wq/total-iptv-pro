package com.totaliptv.pro.data.update

import com.totaliptv.pro.util.SensitiveText
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** 1.4.108: an HTTP answer that was not 2xx. [site] is a plain name ("Google Drive"), never a URL. */
class HttpStatusException(val code: Int, val site: String, val rateLimited: Boolean = false) :
    java.io.IOException("$site answered HTTP $code")

/** A web page (sign-in, quota, filter or "can't scan" page) came back instead of JSON or an APK. */
class WebPageException(val site: String) : java.io.IOException("$site sent a web page")

/** The body ended before the expected size. Resumable. */
class IncompleteDownloadException(val got: Long, val want: Long) :
    java.io.IOException("Download stopped at $got of $want bytes")

/** Size or SHA-256 did not match after a full download. Not resumable. */
class DamagedDownloadException(message: String) : java.io.IOException(message)

/** Not enough free storage for the download. */
class InsufficientSpaceException(val neededBytes: Long) :
    java.io.IOException("No space: need $neededBytes more bytes")

/** The downloaded APK cannot replace the installed app (other package, other signing key, not newer). */
class InstallBlockedException(val reason: String) : Exception(reason)

/** Every download source failed. [cause] is the last error; [tried] are plain source names. */
class UpdateDownloadException(val tried: List<String>, cause: Throwable) :
    java.io.IOException(cause.message, cause)

/** The saved home shelf is a private address on a different network than this device. */
class ShelfSkippedException : Exception("Home shelf skipped")

/** Plain-words reasons for update failures. Never includes a URL or private address. */
object UpdateErrors {
    const val NO_INTERNET =
        "No internet connection, or the site's name could not be looked up (DNS)."
    const val CANT_CONNECT = "Could not connect. Check the internet connection."
    const val TIMED_OUT = "The connection timed out."
    const val SECURE_FAILED =
        "A secure connection could not be made. Check the date and time on this device."
    const val WEB_PAGE =
        "Got a web page instead of the update (a sign-in, quota or network filter page)."
    const val STOPPED = "The download stopped before it finished."
    const val DAMAGED = "The download was incomplete or blocked (size or checksum did not match)."
    const val BAD_INFO = "The update info could not be read."
    const val SHELF_SKIPPED = "skipped (this device is not on that home network)."
    const val PROXY_BLOCKED = "A proxy or network filter blocked the secure connection."
    const val GITHUB_LIMIT = "GitHub's hourly limit for this network was reached. Try again in an hour."

    fun reason(t: Throwable): String {
        val chain = generateSequence(t) { it.cause }.take(8).toList()
        fun <T : Throwable> find(type: Class<T>): T? = chain.firstOrNull { type.isInstance(it) }?.let { type.cast(it) }
        find(InstallBlockedException::class.java)?.let { return it.reason }
        find(InsufficientSpaceException::class.java)?.let {
            val mb = (it.neededBytes + 1_048_575) / 1_048_576
            return "Not enough free space on this device (about $mb MB more needed)."
        }
        find(ShelfSkippedException::class.java)?.let { return SHELF_SKIPPED }
        find(WebPageException::class.java)?.let { return WEB_PAGE }
        find(HttpStatusException::class.java)?.let { return httpReason(it).replaceFirstChar { c -> c.uppercaseChar() } }
        find(DamagedDownloadException::class.java)?.let { return DAMAGED }
        find(IncompleteDownloadException::class.java)?.let { return STOPPED }
        if (chain.any { it is UnknownHostException }) return NO_INTERNET
        if (chain.any { it.message.orEmpty().contains("response code for CONNECT", true) }) return PROXY_BLOCKED
        if (chain.any { it is ConnectException || it is NoRouteToHostException }) return CANT_CONNECT
        if (chain.any { it is SSLException || it is CertificateException }) return SECURE_FAILED
        if (chain.any { it is SocketTimeoutException }) return TIMED_OUT
        if (chain.any { it is EOFException || (it is ProtocolException && it.message.orEmpty().contains("end of stream", true)) }) return STOPPED
        if (chain.any { it is InterruptedIOException }) return TIMED_OUT
        if (chain.any { it is OutOfMemoryError }) return "The device ran out of memory."
        if (chain.any { SensitiveText.isStorageFailure(it) }) return "The device storage is full."
        if (chain.any { it.javaClass.name.startsWith("kotlinx.serialization") }) return BAD_INFO
        val msg = chain.joinToString(" ") { it.message.orEmpty() }
        if (msg.contains("mismatch", true) || msg.contains("too small", true) || msg.contains("not an app file", true)) return DAMAGED
        return SensitiveText.forUser(t)
    }

    private fun httpReason(e: HttpStatusException): String = when {
        e.rateLimited -> GITHUB_LIMIT
        e.code == 403 || e.code == 429 ->
            "${e.site} is limiting downloads right now (HTTP ${e.code}). Try again later."
        e.code == 404 || e.code == 410 -> "The update file was not found on ${e.site} (HTTP ${e.code})."
        e.code >= 500 -> "${e.site} had a problem (HTTP ${e.code}). Try again later."
        else -> "${e.site} answered HTTP ${e.code}."
    }

    /** One message for a failed check. Same reason everywhere -> said once, with the sources tried. */
    private fun sentence(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return t
        val cap = t.replaceFirstChar { it.uppercaseChar() }
        return if (cap.last() in ".!?") cap else "$cap."
    }

    fun checkFailed(failures: List<Pair<String, String>>): String {
        if (failures.isEmpty()) return UpdateSources.UPDATE_UNAVAILABLE
        val skipped = failures.any { it.second == SHELF_SKIPPED }
        val real = failures.filter { it.second != SHELF_SKIPPED }
        if (real.isEmpty()) return "Home shelf $SHELF_SKIPPED"
        val skipNote = if (skipped) " Home shelf skipped (not on that network)." else ""
        val reasons = real.map { it.second }.distinct()
        if (reasons.size == 1) {
            return reasons[0] + " Tried " + joinNames(real.map { it.first }) + "." + skipNote
        }
        // Same reason from several sources is said once: "Google Drive and GitHub: <why>."
        return reasons.joinToString(" ") { why ->
            val names = real.filter { it.second == why }.map { it.first }
            sentence(
                if (names.size == 1 && why.startsWith(names[0], ignoreCase = true)) why
                else joinNames(names) + ": " + why
            )
        } + skipNote
    }

    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}
