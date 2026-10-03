package com.totaliptv.pro.ui.onboarding

import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.totaliptv.pro.data.local.SavedLoginStore
import com.totaliptv.pro.data.repo.CatalogRepository
import com.totaliptv.pro.ui.splash.LogoBannerSplash
import com.totaliptv.pro.util.SensitiveText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TotalIPTV"

/** Pure rules for the sign-in form (unit-tested). */
object SignInForm {
    /**
     * True while a sign-in is saving + loading the playlist. Roots keep the sign-in screen
     * (and its splash) on screen until this clears, so adding the source does not yank the
     * user to an empty Home and cancel the catalog load.
     */
    var active by mutableStateOf(false)

    /** D-pad Up/Down through the ordered form items; stays put at either end. */
    fun move(index: Int, size: Int, down: Boolean): Int =
        if (down) (index + 1).coerceAtMost(size - 1) else (index - 1).coerceAtLeast(0)

    /** First run (no saved source): skip the animated splash and open sign-in at once. */
    fun skipStartupSplash(hasSources: Boolean): Boolean = !hasSources
}

@Composable
fun OnboardingScreen(
    repository: CatalogRepository,
    onDone: () -> Unit
) {
    val scope = rememberCoroutineScope()
    // Sign-in in progress: animated splash (Loading EPG / VOD / Series) covers the form,
    // then Home once the playlist has loaded and the logo animation has finished.
    var signingIn by remember { mutableStateOf(false) }
    var loadDone by remember { mutableStateOf(false) }
    var splashMinDone by remember { mutableStateOf(false) }
    LaunchedEffect(signingIn, loadDone, splashMinDone) {
        if (signingIn && loadDone && splashMinDone) {
            SignInForm.active = false
            onDone()
        }
    }
    DisposableEffect(Unit) { onDispose { SignInForm.active = false } }

    Box(modifier = Modifier.fillMaxSize()) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            val gap = (12 * context.resources.displayMetrics.density).toInt()

            fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

            val root = ScrollView(context).apply {
                setBackgroundColor(AndroidColor.parseColor("#000000"))
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

            val gold = AndroidColor.parseColor("#FFD54F")
            fun paint(v: View, color: Int, focused: Boolean) {
                v.background = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(6).toFloat()
                    if (focused) setStroke(dp(3), gold)
                }
            }

            fun field(hint: String, password: Boolean = false) = EditText(context).apply {
                this.hint = hint
                setHintTextColor(AndroidColor.parseColor("#607D8B"))
                setTextColor(AndroidColor.parseColor("#FFFFFF"))
                tag = AndroidColor.parseColor("#1B2738")
                paint(this, tag as Int, false)
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
            // Last sign-in on this device stays filled in after sign-out or a failed try,
            // until the user taps Forget. Never logged.
            val savedLogins = SavedLoginStore(context)
            savedLogins.load()?.let { saved ->
                base.setText(saved.baseUrl)
                if (saved.username.isNotBlank()) user.setText(saved.username)
                if (saved.password.isNotBlank()) pass.setText(saved.password)
            }

            fun makeButton(text: String, bg: String) = Button(context).apply {
                this.text = text
                setTextColor(AndroidColor.WHITE)
                tag = AndroidColor.parseColor(bg)
                paint(this, tag as Int, false)
                isFocusable = true
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
            val forgetLogin = makeButton("Forget saved sign-in", "#37474F")
            val imm = context.getSystemService(InputMethodManager::class.java)
            fun hideIme() {
                root.findFocus()?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }
            }
            fun lockForm(locked: Boolean) {
                if (locked) {
                    hideIme()
                    root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                } else {
                    root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    saveXtream.requestFocus()
                }
            }
            forgetLogin.setOnClickListener {
                savedLogins.clear()
                base.setText("")
                user.setText("")
                pass.setText("")
                status.setTextColor(AndroidColor.parseColor("#80CBC4"))
                status.text = "Saved sign-in deleted from this device."
            }

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
                lockForm(true)
                loadDone = false
                splashMinDone = false
                SignInForm.active = true
                signingIn = true
                // Keep the server and username even if this try fails. Password only after success.
                savedLogins.save(b, u, null)
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
                            savedLogins.save(b, u, p)
                        } catch (load: Throwable) {
                            Log.e(TAG, "catalog load failed: ${SensitiveText.redact(load.message)}")
                            status.setTextColor(AndroidColor.parseColor("#FFCC80"))
                            status.text = "Saved, but load failed: ${SensitiveText.forUser(load)}. Opening home."
                        }
                        loadDone = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "save xtream failed: ${SensitiveText.redact(t.message)}")
                        status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                        status.text = SensitiveText.forUser(t)
                        saveXtream.isEnabled = true
                        saveM3u.isEnabled = true
                        signingIn = false
                        SignInForm.active = false
                        lockForm(false)
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
                lockForm(true)
                loadDone = false
                splashMinDone = false
                SignInForm.active = true
                signingIn = true
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
                            Log.e(TAG, "catalog load failed: ${SensitiveText.redact(load.message)}")
                            status.setTextColor(AndroidColor.parseColor("#FFCC80"))
                            status.text = "Saved, but load failed: ${SensitiveText.forUser(load)}. Opening home."
                        }
                        loadDone = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "save m3u failed: ${SensitiveText.redact(t.message)}")
                        status.setTextColor(AndroidColor.parseColor("#FF8A80"))
                        status.text = SensitiveText.forUser(t)
                        saveXtream.isEnabled = true
                        saveM3u.isEnabled = true
                        signingIn = false
                        SignInForm.active = false
                        lockForm(false)
                    }
                }
            }

            // Keyboard Next / Done: Display name -> URL -> Username -> Password -> sign in.
            fun onIme(field: EditText, action: Int, run: () -> Unit) {
                field.imeOptions = action or EditorInfo.IME_FLAG_NO_FULLSCREEN
                field.setOnEditorActionListener { _, actionId, ev ->
                    val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
                    if (enter && ev!!.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
                    if (enter || actionId == EditorInfo.IME_ACTION_NEXT ||
                        actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO
                    ) {
                        run()
                        true
                    } else {
                        false
                    }
                }
            }
            onIme(name, EditorInfo.IME_ACTION_NEXT) { base.requestFocus() }
            onIme(base, EditorInfo.IME_ACTION_NEXT) { user.requestFocus() }
            onIme(user, EditorInfo.IME_ACTION_NEXT) { pass.requestFocus() }
            onIme(pass, EditorInfo.IME_ACTION_DONE) {
                hideIme()
                saveXtream.performClick()
            }
            onIme(m3u, EditorInfo.IME_ACTION_DONE) {
                hideIme()
                saveM3u.performClick()
            }

            // D-pad Up/Down reaches every field and button; focused item scrolls into view.
            val order = listOf<View>(name, base, user, pass, saveXtream, forgetLogin, m3u, saveM3u)
            fun scrollIntoView(v: View, index: Int) {
                if (index == 0) {
                    root.smoothScrollTo(0, 0)
                    return
                }
                val r = Rect()
                v.getDrawingRect(r)
                root.offsetDescendantRectToMyCoords(v, r)
                val margin = dp(96)
                val y = root.scrollY
                val top = r.top - margin
                val bottom = r.bottom + margin - root.height
                if (top < y) root.smoothScrollTo(0, top.coerceAtLeast(0))
                else if (bottom > y) root.smoothScrollTo(0, bottom)
            }
            order.forEachIndexed { index, v ->
                v.setOnFocusChangeListener { view, has ->
                    paint(view, view.tag as Int, has)
                    if (has) root.post { scrollIntoView(view, index) }
                }
                v.setOnKeyListener { _, keyCode, ev ->
                    val down = when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> true
                        KeyEvent.KEYCODE_DPAD_UP -> false
                        else -> return@setOnKeyListener false
                    }
                    if (ev.action == KeyEvent.ACTION_DOWN) {
                        val next = SignInForm.move(index, order.size, down)
                        if (next != index) order[next].requestFocus() else scrollIntoView(v, index)
                    }
                    true
                }
            }
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                root.post {
                    val first = when {
                        base.text.isNullOrBlank() -> base
                        user.text.isNullOrBlank() -> user
                        pass.text.isNullOrBlank() -> pass
                        else -> saveXtream
                    }
                    first.requestFocus()
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
            column.addView(forgetLogin)
            column.addView(label("Or use M3U instead"))
            column.addView(m3u)
            column.addView(saveM3u)

            root
        }
    )
    if (signingIn) {
        LogoBannerSplash(
            ready = loadDone,
            statusMessage = if (loadDone) null else "Updating Live / Movies / Series...",
            onFinished = { splashMinDone = true }
        )
    }
    }
}
