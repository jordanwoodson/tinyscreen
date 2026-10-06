package io.github.jordanwoodson.tinyscreen.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.adblock.AdBlocker
import io.github.jordanwoodson.tinyscreen.net.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
 * Origins of the players the web player embeds (see [io.github.jordanwoodson.tinyscreen.stream.Embed.webEmbedUrl]).
 * Scripts that must reach inside the player iframes are scoped to these.
 */
internal val PLAYER_ORIGINS = setOf(
    "https://player.twitch.tv", "https://clips.twitch.tv",
    "https://www.youtube.com", "https://www.youtube-nocookie.com",
    "https://player.kick.com", "https://kick.com",
    "https://www.facebook.com",
    "https://rumble.com",
    "https://player.vimeo.com",
    "https://player.angelthump.com",
)

/**
 * Runs in each player frame: tells the app whether its video or audio is playing, and pauses or
 * resumes it when asked, through the `TinyscreenMedia` object that [WebEmbedHolder] injects.
 */
private val MEDIA_SCRIPT = """
    (function(){
      var bridge = window.TinyscreenMedia;
      if (!bridge || window.__tinyscreenMedia) return;
      window.__tinyscreenMedia = true;
      var last = null, current = null;
      function media(){ return document.querySelectorAll('video,audio'); }
      function report(e){
        if (e && e.target instanceof HTMLMediaElement) current = e.target;
        var playing = false;
        media().forEach(function(m){ if (!m.paused && !m.ended) playing = true; });
        var state = playing ? 'playing' : 'paused';
        if (state !== last) { last = state; bridge.postMessage(state); }
      }
      ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function(t){ document.addEventListener(t, report, true); });
      window.addEventListener('pagehide', function(){ last = 'paused'; bridge.postMessage('paused'); });
      bridge.onmessage = function(e){
        if (e.data === 'pause') media().forEach(function(m){ m.pause(); });
        else if (e.data === 'play' && current) current.play();
      };
    })();
""".trimIndent()

/**
 * Owns the web player's WebView outside composition, so rotating, picture-in-picture, Settings and
 * activity recreation (font size, language) move the player around instead of reloading the embed.
 * It lives in the activity's ViewModelStore, so it goes away when the activity finishes for good.
 */
class WebEmbedHolder(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TinyscreenApp).graph

    // A WebView keeps the context it was built with; point it at whichever activity is current
    private val context = MutableContextWrapper(app)
    private val chrome = FullscreenChromeClient()
    private var webView: WebView? = null
    private var loadedUrl: String? = null
    private var onOpenUrl: ((String) -> Unit)? = null
    private var onFullscreen: ((View?) -> Unit)? = null

    /** Whether the player frames report their playback state; without it, a loaded player counts as playing. */
    private var reportsMedia = false
    /** Ad hiding inside the player iframes, which [createWebView]'s main-frame script can't reach. */
    private var cosmeticScript: ScriptHandler? = null
    private val frames = mutableMapOf<JavaScriptReplyProxy, Boolean>()

    private val _active = MutableStateFlow(false)
    /** Whether an embed is loaded. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _playing = MutableStateFlow(false)
    /** Whether the embed is playing video or audio, as far as the player frames report it. */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    /**
     * Connects the player to [activity]. Links clicked inside a player go to [openUrl]; HTML5
     * fullscreen hands its view to [showFullscreen] to cover the window (null when it ends).
     */
    fun attach(activity: Activity, openUrl: (String) -> Unit, showFullscreen: (View?) -> Unit) {
        context.baseContext = activity
        onOpenUrl = openUrl
        onFullscreen = showFullscreen
    }

    fun detach(activity: Activity) {
        if (context.baseContext !== activity) return
        exitFullscreen()
        context.baseContext = getApplication()
        onOpenUrl = null
        onFullscreen = null
    }

    private fun view(): WebView = webView ?: createWebView(
        context,
        adblock = { graph.settings.settings.value.adblock },
        userAgent = Http.DESKTOP_UA,
        // The page itself is ours; any top-level navigation is a link clicked in the player
        onNavigate = { uri ->
            onOpenUrl?.invoke(uri.toString())
            true
        },
        chrome = chrome,
    ).also { view ->
        webView = view
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addWebMessageListener(view, "TinyscreenMedia", PLAYER_ORIGINS) { _, message, _, _, frame ->
                frames[frame] = message.data == "playing"
                _playing.value = frames.values.any { it }
            }
            WebViewCompat.addDocumentStartJavaScript(view, MEDIA_SCRIPT, PLAYER_ORIGINS)
            reportsMedia = true
        }
    }

    /**
     * Plays [embedUrl] inside an iframe whose parent origin is destiny.gg — the same way the bigscreen
     * page hosts it, which satisfies Twitch's `parent=` and YouTube's referrer checks. Null blanks the
     * player. Loading the URL that's already playing does nothing.
     */
    fun show(embedUrl: String?) {
        if (embedUrl == loadedUrl) return
        loadedUrl = embedUrl
        frames.clear()
        _active.value = embedUrl != null
        if (embedUrl == null) {
            _playing.value = false
            exitFullscreen()
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
            }
            return
        }
        val view = view()
        _playing.value = !reportsMedia
        syncCosmeticScript(view)
        view.loadDataWithBaseURL("$DGG_ORIGIN/bigscreen", iframeHtml(embedUrl), "text/html", "utf-8", null)
    }

    /** Follows the Block ads setting; a change applies from the next embed loaded. */
    private fun syncCosmeticScript(view: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val wanted = graph.settings.settings.value.adblock
        if (wanted && cosmeticScript == null) {
            cosmeticScript = WebViewCompat.addDocumentStartJavaScript(view, AdBlocker.cosmeticScript, PLAYER_ORIGINS)
        } else if (!wanted) {
            cosmeticScript?.remove()
            cosmeticScript = null
        }
    }

    /** Pauses whatever the player frames are playing. */
    fun pause() {
        frames.keys.forEach { it.postMessage("pause") }
    }

    /** Resumes the media that last played, or pauses it if it's playing. */
    fun togglePlayback() {
        val message = if (_playing.value) "pause" else "play"
        frames.keys.forEach { it.postMessage(message) }
    }

    /** The app went to the background (not into picture-in-picture): a web player can't keep playing there. */
    fun onBackground() {
        pause()
        webView?.onPause()
    }

    fun onForeground() {
        webView?.onResume()
    }

    fun exitFullscreen() = chrome.exit()

    internal fun attachTo(frame: FrameLayout) {
        val view = view()
        (view.parent as? ViewGroup)?.removeView(view)
        frame.addView(view)
    }

    internal fun detachFrom(frame: FrameLayout) {
        webView?.let { if (it.parent === frame) frame.removeView(it) }
    }

    override fun onCleared() {
        // The activity's ViewModels can be cleared before its views are torn down
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
    }

    /** Hands an HTML5 player's fullscreen view to the activity, which shows it over the whole window. */
    private inner class FullscreenChromeClient : WebChromeClient() {
        private var callback: CustomViewCallback? = null

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val show = onFullscreen ?: return callback.onCustomViewHidden()
            this.callback?.onCustomViewHidden()
            this.callback = callback
            show(view)
        }

        override fun onHideCustomView() {
            if (callback == null) return
            callback = null
            onFullscreen?.invoke(null)
        }

        fun exit() {
            val current = callback ?: return
            current.onCustomViewHidden()
            onHideCustomView()
        }
    }
}

/** Shows the [WebEmbedHolder]'s player; what it plays is set with [WebEmbedHolder.show]. */
@Composable
fun WebEmbedPlayer(holder: WebEmbedHolder, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { FrameLayout(it).apply { holder.attachTo(this) } },
        onRelease = { holder.detachFrom(it) },
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
