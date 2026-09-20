package com.totaliptv.pro.ui.onboarding

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.totaliptv.pro.data.repo.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TotalIPTV"

@Composable
fun OnboardingScreen(
    repository: CatalogRepository,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            val gap = (12 * context.resources.displayMetrics.density).toInt()

            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

            val root = ScrollView(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#0B1220"))
                isFillViewport = true
            }
            val column = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
            root.addView(
                column,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            fun label(text: String) = TextView(context).apply {
                this.text = text
                setTextColor(AndroidColor.parseColor("#B0BEC5"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, gap, 0, dp(4))
            }

            fun field(hint: String, password: Boolean = false) = EditText(context).apply {
                this.hint = hint
                setHintTextColor(AndroidColor.parseColor("#607D8B"))
                setTextColor(AndroidColor.parseColor("#FFFFFF"))
                setBackgroundColor(AndroidColor.parseColor("#1B2738"))
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setSingleLine(true)
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = gap }
            }

            val title = TextView(context).apply {
                text = "Total IPTV Pro"
                setTextColor(AndroidColor.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                typeface = Typeface.DEFAULT_BOLD
            }
            val subtitle = TextView(context).apply {
                text = "Enter Xtream details, then tap Save. Mouse clicks work on this form."
                setTextColor(AndroidColor.parseColor("#90A4AE"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(8), 0, gap)
            }

            val status = TextView(context).apply {
                text = ""
                setTextColor(AndroidColor.parseColor("#FF8A80"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, gap, 0, gap)
            }

            val name = field("Display name")
            name.setText("My Xtream")
            val base = field("Base URL (http://host:port)")
            val user = field("Username")
            val pass = field("Password", password = true)
            val m3u = field("Or M3U URL (optional if using Xtream)")

            fun makeButton(text: String, bg: String) = Button(context).apply {
                this.text = text
                setTextColor(AndroidColor.WHITE)
                setBackgroundColor(AndroidColor.parseColor(bg))
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setPadding(dp(18), dp(16), dp(18), dp(16))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = gap }
            }

            val saveXtream = makeButton("Save Xtream & continue", "#00897B")
            val saveM3u = makeButton("Save M3U & continue", "#1565C0")

            fun normalizeBase(raw: String): String {
                val t = raw.trim().trimEnd('/')
                return when {
                    t.startsWith("http://", true) || t.startsWith("https://", true) -> t
                    t.isBlank() -> t
                    else -> "http://$t"
                }
            }

            saveXtream.setOnClickListener {
                val b = normalizeBase(base.text.toString())
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                val n = name.text.toString().ifBlank { "Xtream" }
                if (b.isBlank() || u.isBlank() || p.isBlank()) {
                    status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                    status.text = "Fill Base URL, Username, and Password"
                    return@setOnClickListener
                }
                saveXtream.isEnabled = false
                saveM3u.isEnabled = false
                status.setTextColor(AndroidColor.parseColor("#80CBC4"))
                status.text = "Saving Xtream…"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.addXtreamSource(n, b, u, p)
                        }
                        status.text = "Saved. Loading channels…"
                        try {
                            withContext(Dispatchers.IO) {
                                repository.ensureCatalogLoaded(force = true)
                            }
                        } catch (load: Throwable) {
                            Log.e(TAG, "catalog load failed", load)
                            status.setTextColor(AndroidColor.parseColor("#FFCC80"))
                            status.text = "Saved, but load failed: ${load.message}. Opening home."
                        }
                        onDone()
                    } catch (t: Throwable) {
                        Log.e(TAG, "save xtream failed", t)
                        status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                        status.text = t.message ?: "Save failed"
                        saveXtream.isEnabled = true
                        saveM3u.isEnabled = true
                    }
                }
            }

            saveM3u.setOnClickListener {
                val url = m3u.text.toString().trim()
                val n = name.text.toString().ifBlank { "M3U Playlist" }
                if (url.isBlank()) {
                    status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                    status.text = "Paste an M3U URL first"
                    return@setOnClickListener
                }
                saveXtream.isEnabled = false
                saveM3u.isEnabled = false
                status.setTextColor(AndroidColor.parseColor("#80CBC4"))
                status.text = "Saving M3U…"
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            repository.addM3uSource(n, url)
                        }
                        status.text = "Saved. Loading channels…"
                        try {
                            withContext(Dispatchers.IO) {
                                repository.ensureCatalogLoaded(force = true)
                            }
                        } catch (load: Throwable) {
                            Log.e(TAG, "catalog load failed", load)
                            status.setTextColor(AndroidColor.parseColor("#FFCC80"))
                            status.text = "Saved, but load failed: ${load.message}. Opening home."
                        }
                        onDone()
                    } catch (t: Throwable) {
                        Log.e(TAG, "save m3u failed", t)
                        status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                        status.text = t.message ?: "Save failed"
                        saveXtream.isEnabled = true
                        saveM3u.isEnabled = true
                    }
                }
            }

            column.addView(title)
            column.addView(subtitle)
            column.addView(status)
            column.addView(label("Display name"))
            column.addView(name)
            column.addView(label("Xtream Base URL"))
            column.addView(base)
            column.addView(label("Username"))
            column.addView(user)
            column.addView(label("Password"))
            column.addView(pass)
            column.addView(saveXtream)
            column.addView(label("Or use M3U instead"))
            column.addView(m3u)
            column.addView(saveM3u)

            root
        }
    )
}
