package com.totaliptv.pro.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.totaliptv.pro.ui.trailer.TrailerWebActivity

object YoutubePreview {
    private const val TAG = "TotalIPTV.Preview"
    private val ID_RE = Regex("""^[A-Za-z0-9_-]{11}$""")

    private val YT_PACKAGES = listOf(
        "com.google.android.youtube",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv",
        "com.google.android.youtube.tvunplugged"
    )

    /** TV emulator / framework stubs that "handle" https but do not play video. */
    private val STUB_PACKAGES = setOf(
        "com.android.tv.frameworkpackagestubs",
        "com.android.stub",
        "com.google.android.tv.frameworkpackagestubs"
    )

    /** Extract an 11-char YouTube video id from a bare id or common URL forms. */
    fun extractVideoId(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (ID_RE.matches(t)) return t
        val s = t.replace(" ", "")
        val patterns = listOf(
            Regex("""[?&]v=([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/v/([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/watch/([A-Za-z0-9_-]{11})"""),
            Regex("""vnd\.youtube:([A-Za-z0-9_-]{11})"""),
            Regex("""youtube\.com/live/([A-Za-z0-9_-]{11})""")
        )
        for (p in patterns) {
            p.find(s)?.groupValues?.getOrNull(1)?.let { return it }
        }
        Regex("""[A-Za-z0-9_-]{11}""").find(s)?.value?.let { if (ID_RE.matches(it)) return it }
        return null
    }

    private fun resolvePackage(pm: PackageManager, intent: Intent): String? {
        return try {
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            ri?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }

    private fun isStub(pkg: String?): Boolean =
        pkg != null && (pkg in STUB_PACKAGES || pkg.contains("frameworkpackagestubs", true))

    private fun tryStart(context: Context, intent: Intent, label: String): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            val pm = context.packageManager
            val resolved = resolvePackage(pm, intent)
            if (intent.`package` != null && resolved == null) {
                Log.i(TAG, "Skip $label — package not resolvable")
                return false
            }
            if (isStub(resolved)) {
                Log.w(TAG, "Skip $label — stub handler $resolved")
                return false
            }
            // If no package set and nothing useful resolves, don't pretend success
            if (intent.`package` == null && resolved == null) {
                Log.i(TAG, "Skip $label — no activity")
                return false
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened trailer via $label uri=${intent.data} -> $resolved")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ActivityNotFound for $label uri=${intent.data}")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed $label uri=${intent.data}: ${e.message}")
            false
        }
    }

    private fun openInAppWebView(context: Context, id: String): Boolean {
        return try {
            val intent = Intent(context, TrailerWebActivity::class.java).apply {
                putExtra(TrailerWebActivity.EXTRA_VIDEO_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened trailer in-app WebView id=$id")
            true
        } catch (e: Exception) {
            Log.e(TAG, "In-app WebView failed", e)
            false
        }
    }

    /**
     * Open YouTube for [trailer] (id or URL).
     * Prefer: YouTube app → YouTube TV → real browser watch/youtu.be → in-app WebView.
     * Skips Android TV BrowserStub so Preview actually plays on emulator.
     */
    fun openTrailer(context: Context, trailer: String?) {
        val raw = trailer?.trim()?.takeIf { it.isNotBlank() }
        if (raw == null) {
            Toast.makeText(context, "No trailer available for this title", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "openTrailer: empty trailer field")
            return
        }
        val id = extractVideoId(raw)
        if (id == null) {
            Toast.makeText(context, "Trailer link could not be parsed", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "openTrailer: could not parse id from raw=$raw")
            return
        }
        Log.i(TAG, "openTrailer id=$id raw=$raw")

        val watch = Uri.parse("https://www.youtube.com/watch?v=$id")
        val short = Uri.parse("https://youtu.be/$id")
        val vnd = Uri.parse("vnd.youtube:$id")

        for (pkg in YT_PACKAGES) {
            if (tryStart(context, Intent(Intent.ACTION_VIEW, vnd).apply { setPackage(pkg) }, "vnd+$pkg")) return
        }
        for (pkg in YT_PACKAGES) {
            if (tryStart(context, Intent(Intent.ACTION_VIEW, watch).apply { setPackage(pkg) }, "watch+$pkg")) return
        }
        if (tryStart(context, Intent(Intent.ACTION_VIEW, vnd), "vnd.any")) return
        if (tryStart(context, Intent(Intent.ACTION_VIEW, watch), "browser.watch")) return
        if (tryStart(context, Intent(Intent.ACTION_VIEW, short), "browser.youtu.be")) return

        if (openInAppWebView(context, id)) return

        Toast.makeText(
            context,
            "Can't open YouTube — install YouTube or a browser",
            Toast.LENGTH_LONG
        ).show()
        Log.e(TAG, "All trailer intents failed for id=$id")
    }
}
