package com.oldtube

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.json.JSONObject

private const val HOME = "https://m.youtube.com/"

/**
 * A thin shell around m.youtube.com. YouTube itself does all the work — account,
 * subscriptions, recommendations, comments, playback — and we repaint it on the
 * way in with `assets/classic.css`.
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView

    // Set while a video is playing fullscreen.
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        web = WebView(this)
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            // Videos should start when YouTube says so, not on a second tap.
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Google refuses sign-in from any UA containing the "; wv" WebView
            // token. Dropping it is the standard workaround — see README.
            userAgentString = WebSettings.getDefaultUserAgent(this@MainActivity)
                .replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.webViewClient = SkinningWebViewClient()
        web.webChromeClient = FullscreenChromeClient()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> web.webChromeClient?.onHideCustomView()
                    web.canGoBack() -> web.goBack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) web.loadUrl(HOME) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    // ---- skin injection ----------------------------------------------------

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader().use { it.readText() }

    /**
     * Injected at both page start and page finish. Start catches the first paint
     * so modern YouTube doesn't flash before the skin lands; finish covers the
     * case where <head> didn't exist yet at start.
     */
    private fun injectSkin() {
        val css = readAsset("classic.css")
        val js = readAsset("inject.js").replace("__CSS__", JSONObject.quote(css))
        web.evaluateJavascript(js, null)
    }

    private inner class SkinningWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            injectSkin()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            injectSkin()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?,
        ): Boolean {
            val host = request?.url?.host ?: return false
            // Keep YouTube and the Google sign-in flow in-app; everything else
            // (links in descriptions, ads) goes to the real browser.
            val internal = host.endsWith("youtube.com") ||
                host.endsWith("youtu.be") ||
                host.endsWith("google.com") ||
                host.endsWith("googleusercontent.com") ||
                host.endsWith("ggpht.com")
            if (internal) return false

            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(request.url.toString())))
            return true
        }
    }

    // ---- fullscreen video --------------------------------------------------

    private inner class FullscreenChromeClient : WebChromeClient() {
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (fullscreenView != null) {
                callback?.onCustomViewHidden()
                return
            }
            fullscreenView = view
            fullscreenCallback = callback
            web.visibility = View.GONE
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() {
            val view = fullscreenView ?: return
            root.removeView(view)
            web.visibility = View.VISIBLE
            fullscreenView = null
            fullscreenCallback?.onCustomViewHidden()
            fullscreenCallback = null
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
