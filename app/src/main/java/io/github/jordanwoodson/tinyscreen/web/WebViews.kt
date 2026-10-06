package io.github.jordanwoodson.tinyscreen.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jordanwoodson.tinyscreen.adblock.AdBlocker
import io.github.jordanwoodson.tinyscreen.net.Http

const val DGG_ORIGIN = "https://www.destiny.gg"

/** WebView preconfigured for destiny.gg and embedded players, with request-level ad blocking. */
@SuppressLint("SetJavaScriptEnabled")
fun createWebView(
    context: Context,
    adblock: () -> Boolean,
    userAgent: String = Http.MOBILE_UA,
    onNavigate: ((Uri) -> Boolean)? = null,
    onPageFinished: ((WebView, String) -> Unit)? = null,
    chrome: WebChromeClient? = null,
): WebView = WebView(context).apply {
    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        // A WebView user agent ("; wv") is rejected by several OAuth providers
        userAgentString = userAgent
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            AdBlocker.intercept(request, adblock())

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return onNavigate?.invoke(request.url) ?: false
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            if (adblock()) view.evaluateJavascript(AdBlocker.cosmeticScript, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (adblock()) view.evaluateJavascript(AdBlocker.cosmeticScript, null)
            CookieManager.getInstance().flush()
            onPageFinished?.invoke(view, url)
        }
    }
    webChromeClient = chrome ?: WebChromeClient()
}

/**
 * Plays an arbitrary embed URL inside an iframe whose parent origin is destiny.gg — the same
 * way the bigscreen page hosts it, which satisfies Twitch's `parent=` and YouTube's referrer checks.
 */
@Composable
fun WebEmbedPlayer(embedUrl: String, adblock: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val adblockState = remember { booleanArrayOf(adblock) }
    adblockState[0] = adblock
    val fullscreenHost = remember { FullscreenChromeClient() }
    val webView = remember {
        createWebView(context, adblock = { adblockState[0] }, userAgent = Http.DESKTOP_UA, chrome = fullscreenHost)
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
    AndroidView(
        factory = {
            FrameLayout(it).apply {
                addView(webView)
                fullscreenHost.container = this
            }
        },
        update = {
            val html = iframeHtml(embedUrl)
            if (webView.tag != embedUrl) {
                webView.tag = embedUrl
                webView.loadDataWithBaseURL("$DGG_ORIGIN/bigscreen", html, "text/html", "utf-8", null)
            }
        },
        modifier = modifier,
    )
}

private fun iframeHtml(src: String): String {
    val safe = src.replace("\"", "%22").replace("<", "%3C").replace(">", "%3E")
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>
        </head><body><iframe src="$safe" allow="autoplay; fullscreen; encrypted-media; picture-in-picture; clipboard-write"
        allowfullscreen referrerpolicy="origin"></iframe></body></html>
    """.trimIndent()
}

/** Lets HTML5 players go fullscreen inside the embed area. */
class FullscreenChromeClient : WebChromeClient() {
    var container: FrameLayout? = null
    private var custom: View? = null
    private var callback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        val host = container ?: return callback.onCustomViewHidden()
        custom?.let { host.removeView(it) }
        custom = view
        this.callback = callback
        host.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onHideCustomView() {
        custom?.let { container?.removeView(it) }
        custom = null
        callback?.onCustomViewHidden()
        callback = null
    }
}
