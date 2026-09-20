package com.totaliptv.pro.ui.trailer

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * In-app YouTube trailer fallback for devices without YouTube / real browser
 * (e.g. Android TV emulator BrowserStub). Loads the embed player.
 */
class TrailerWebActivity : ComponentActivity() {
    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_VIDEO_ID)?.trim().orEmpty()
        if (id.isEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
        }
        val progressLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )

        val hint = TextView(this).apply {
            text = "Trailer  ·  Back to close"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(24, 16, 24, 16)
        }
        val hintLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )

        val wv = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    progress.visibility = android.view.View.GONE
                }
            }
            webChromeClient = WebChromeClient()
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            // Prefer embed — works in WebView without full YouTube app
            val embed =
                "https://www.youtube.com/embed/$id?autoplay=1&playsinline=1&rel=0&fs=1"
            loadUrl(embed)
        }
        webView = wv

        root.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(progress, progressLp)
        root.addView(hint, hintLp)
        setContentView(root)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
