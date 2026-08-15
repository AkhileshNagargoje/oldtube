package com.oldtube

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
private const val TAG = "OldTube"

/**
 * Hosts that stay inside the app: YouTube itself, its asset CDNs, and the whole
 * Google sign-in flow.
 *
 * `google\.[a-z.]+` rather than `google.com` on purpose — sign-in redirects
 * through country domains (accounts.google.co.in, .co.uk, .de). Matching only
 * .com sends those to the real browser mid-login, which reads as "it opened
 * Chrome and forgot who I am".
 */
private val INTERNAL_HOST = Regex(
    """(^|\.)(google\.[a-z]{2,}(\.[a-z]{2,})?|youtube\.com|youtube-nocookie\.com|youtu\.be|""" +
        """ytimg\.com|ggpht\.com|googleusercontent\.com|gstatic\.com|googleapis\.com)$""",
)

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

        // Debug builds only: lets `chrome://inspect` attach to the WebView, so
        // the page can be inspected with real devtools instead of screenshots.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

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

        applySystemBars()

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
     * "dark" when the phone is in dark mode, otherwise "light". The stylesheet
     * keys its dark palette off this; there is no in-app switch, because the
     * system toggle already is one.
     */
    private fun currentTheme(): String {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) "dark" else "light"
    }

    /**
     * Injected at both page start and page finish. Start catches the first paint
     * so modern YouTube doesn't flash before the skin lands; finish covers the
     * case where <head> didn't exist yet at start.
     */
    private fun injectSkin() {
        val css = readAsset("classic.css")
        val js = readAsset("inject.js")
            .replace("__CSS__", JSONObject.quote(css))
            .replace("__THEME__", JSONObject.quote(currentTheme()))
        web.evaluateJavascript(js, null)
    }

    /**
     * The manifest lists `uiMode` in configChanges, so switching the phone's
     * dark mode never recreates this activity — the theme has to be re-applied
     * by hand or the page keeps the palette it started with.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        injectSkin()
    }

    /**
     * Status bar follows the app bar: 2016 red in light, #212121 in dark.
     *
     * `statusBarColor` is deprecated as of API 35, which draws edge-to-edge and
     * ignores it. There is no replacement that paints a solid bar, and this app
     * deliberately isn't edge-to-edge — a translucent status bar over a red app
     * bar is not the 2016 look. On anything below 35 it still applies.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBars() {
        // Same in both themes: the app bar stays red in dark mode, so the
        // status bar above it does too.
        window.statusBarColor = 0xFFCC181E.toInt()
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
            val url = request?.url ?: return false
            val host = url.host ?: return false

            // Anything that isn't plain web navigation (intent://, market://,
            // tel:) is not ours to render — but it isn't a browser link either.
            val scheme = url.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                Log.i(TAG, "nav: $scheme -> ignored")
                return true
            }

            val internal = INTERNAL_HOST.containsMatchIn(host)
            Log.i(TAG, "nav: $host -> ${if (internal) "in-app" else "EXTERNAL"}  ($url)")
            if (internal) return false

            // Descriptions and ads link out; hand those to the real browser.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (e: android.content.ActivityNotFoundException) {
                Log.w(TAG, "no browser for $url", e)
                return false
            }
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
