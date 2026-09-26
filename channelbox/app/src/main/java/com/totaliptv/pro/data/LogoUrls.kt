package com.totaliptv.pro.data

/**
 * Xtream `stream_icon` is often a path (`/logo/x.png`) or a protocol-relative
 * URL. Coil cannot load those, so the row stays on the letter placeholder.
 */
object LogoUrls {
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) TotalIPTVPro/1.4"

    fun absolute(baseUrl: String?, raw: String?): String? {
        val icon = raw?.trim().orEmpty()
        if (icon.isEmpty() || icon.equals("null", ignoreCase = true)) return null
        if (icon.startsWith("https://") || icon.startsWith("http://")) return icon
        if (icon.startsWith("//")) return "https:$icon"
        val base = baseUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val host = if (base.startsWith("http://") || base.startsWith("https://")) base else "http://$base"
        return if (icon.startsWith("/")) host + icon else "$host/$icon"
    }

    /** Origin of a playback URL, used when a saved catalog still has a relative icon. */
    fun origin(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return null
        val slash = trimmed.indexOf('/', schemeEnd + 3)
        val host = if (slash < 0) trimmed else trimmed.substring(0, slash)
        return host.takeIf { it.length > schemeEnd + 3 }
    }

    fun forPlayback(playbackUrl: String?, raw: String?): String? = absolute(origin(playbackUrl), raw)
}
