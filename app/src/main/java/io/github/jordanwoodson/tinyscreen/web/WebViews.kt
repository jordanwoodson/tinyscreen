package io.github.jordanwoodson.tinyscreen.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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
import io.github.jordanwoodson.tinyscreen.player.PlaybackCheckpoint
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
    interceptRequest: ((WebResourceRequest) -> WebResourceResponse?)? = null,
    chrome: WebChromeClient? = null,
    /**
     * The renderer crashed or Android killed it for memory. The dead WebView has already been removed
     * from [parent] and destroyed; put a fresh one there to recover. Without this, the app would crash.
     */
    onRenderProcessGone: ((gone: WebView, parent: ViewGroup?) -> Unit)? = null,
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
            interceptRequest?.invoke(request) ?: AdBlocker.intercept(request, adblock())

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

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // Every WebView in the app shares one renderer, so they all get this together
            Log.w("WebViews", "renderer gone (crashed: ${detail.didCrash()})")
            val parent = view.parent as? ViewGroup
            parent?.removeView(view)
            view.destroy()
            onRenderProcessGone?.invoke(view, parent)
            return true
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
private fun mediaScript(generation: Long) = """
    (function(){
      var bridge = window.TinyscreenMedia;
      if (!bridge || window.__tinyscreenMedia) return;
      window.__tinyscreenMedia = true;
      var last = '', current = null, holdPause = false;
      function send(data){ bridge.postMessage('$generation|' + data); }
      function media(){ return document.querySelectorAll('video,audio'); }
      function report(e){
        if (e && e.target instanceof HTMLMediaElement) current = e.target;
        if (!current) return;
        if (holdPause && !current.paused) current.pause();
        var pos = Math.floor(current.currentTime * 1000);
        var live = !Number.isFinite(current.duration);
        var yt = document.getElementById('movie_player');
        if (yt && typeof yt.getVideoData === 'function' && yt.getVideoData()?.isLive) live = true;
        var state = current.ended ? 'ended' : current.paused ? 'paused' : 'playing';
        var title = (yt && typeof yt.getVideoData === 'function' && yt.getVideoData()?.title) || document.title || '';
        title = title.trim().slice(0, 256);
        var bucket = state + ':' + Math.floor(pos / 1000) + ':' + live + ':' + title;
        if (bucket !== last) { last = bucket; send(state + '|' + (live ? -1 : pos) + '|' + title); }
        if (e && e.type === 'loadedmetadata') send('ready');
      }
      ['play','playing','pause','ended','loadedmetadata','seeked','timeupdate'].forEach(function(t){ document.addEventListener(t,report,true); });
      ['pointerdown','keydown'].forEach(function(t){ document.addEventListener(t,function(){ holdPause = false; },true); });
      window.addEventListener('pagehide',function(){ send('gone'); });
      bridge.onmessage = function(e){
        if (e.data === 'pause') media().forEach(function(m){ m.pause(); });
        else if (e.data === 'play' && current) { holdPause = false; current.play(); }
        else if (e.data.startsWith('restore|') && current) {
          var parts = e.data.split('|'), pos = Number(parts[1]);
          if (pos >= 0 && Number.isFinite(current.duration)) current.currentTime = pos / 1000;
          holdPause = parts[2] === 'false';
          if (holdPause) current.pause(); else current.play().catch(function(){});
        }
      };
    })();
""".trimIndent()

data class WebMediaState(val generation: Long, val positionMs: Long?, val playing: Boolean, val ended: Boolean, val title: String?)

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
    private var frame: FrameLayout? = null
    private var inBackground = false
    /** An embed that lost its renderer while the app was in the background; reloaded on return. */
    private var reloadOnForeground: String? = null

    /** Whether the player frames report their playback state; without it, a loaded player counts as playing. */
    private var reportsMedia = false
    /** Ad hiding inside the player iframes, which [createWebView]'s main-frame script can't reach. */
    private var cosmeticScript: ScriptHandler? = null
    private var mediaScriptHandle: ScriptHandler? = null
    private val frames = mutableMapOf<JavaScriptReplyProxy, Boolean>()

    private val _mediaState = MutableStateFlow<WebMediaState?>(null)
    val mediaState = _mediaState.asStateFlow()
    private var pendingRestore: PlaybackCheckpoint? = null

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
        interceptRequest = playerRequestInterceptor(getApplication()),
        onRenderProcessGone = { gone, _ -> if (gone === webView) recoverRenderer() },
    ).also { view ->
        webView = view
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        ) {
            WebViewCompat.addWebMessageListener(view, "TinyscreenMedia", PLAYER_ORIGINS) { _, message, _, _, frame ->
                val payload = message.data ?: return@addWebMessageListener
                if (payload.substringBefore('|').toLongOrNull() != lastGeneration) return@addWebMessageListener
                val data = payload.substringAfter('|')
                when (data) {
                    "ready" -> pendingRestore?.let {
                        frame.postMessage("restore|${it.positionMs ?: -1}|${it.playWhenReady && !inBackground}")
                        pendingRestore = null
                    }
                    "gone" -> frames.remove(frame)
                    else -> {
                        val state = data.substringBefore('|')
                        if (state !in setOf("playing", "paused", "ended")) return@addWebMessageListener
                        val media = data.substringAfter('|')
                        val pos = media.substringBefore('|').toLongOrNull()?.takeIf { it >= 0 }
                        val title = media.substringAfter('|', "").take(256).takeIf { it.isNotBlank() }
                        frames[frame] = state == "playing"
                        _mediaState.value = WebMediaState(lastGeneration, pos, state == "playing", state == "ended", title)
                    }
                }
                _playing.value = frames.values.any { it }
            }
            reportsMedia = true
        }
    }

    private var lastGeneration = -1L

    /**
     * Plays [embedUrl] inside an iframe whose parent origin is destiny.gg — the same way the bigscreen
     * page hosts it, which satisfies Twitch's `parent=` and YouTube's referrer checks. Null blanks the
     * player. A new generation reloads the page even when the URL matches.
     */
    fun show(embedUrl: String?, generation: Long = lastGeneration, restore: PlaybackCheckpoint? = null) {
        val sameRequest = generation == lastGeneration
        lastGeneration = generation
        if (sameRequest && embedUrl == loadedUrl) return
        reloadOnForeground = null
        pendingRestore = restore
        _mediaState.value = null
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
        if (reportsMedia && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            mediaScriptHandle?.remove()
            mediaScriptHandle = WebViewCompat.addDocumentStartJavaScript(view, mediaScript(generation), PLAYER_ORIGINS)
        }
        _playing.value = !reportsMedia
        syncCosmeticScript(view)
        view.loadDataWithBaseURL("$DGG_ORIGIN/bigscreen", iframeHtml(embedUrl), "text/html", "utf-8", null)
    }

    private fun recoverRenderer() {
        val url = loadedUrl
        webView = null
        cosmeticScript = null
        mediaScriptHandle = null
        val checkpoint = _mediaState.value?.let { PlaybackCheckpoint(it.positionMs, it.playing) } ?: pendingRestore
        reportsMedia = false
        loadedUrl = null
        exitFullscreen()
        frame?.let { attachTo(it) }
        // Reloading in the background would start the embed playing out of sight
        if (inBackground) {
            reloadOnForeground = url
            pendingRestore = checkpoint
            frames.clear()
            _playing.value = false
        } else {
            show(url, restore = checkpoint)
        }
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
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            frames.keys.forEach { it.postMessage("pause") }
        }
    }

    /** Resumes the media that last played, or pauses it if it's playing. */
    fun togglePlayback() {
        val message = if (_playing.value) "pause" else "play"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            frames.keys.forEach { it.postMessage(message) }
        }
    }

    /** The app went to the background (not into picture-in-picture): a web player can't keep playing there. */
    fun onBackground() {
        inBackground = true
        pause()
        webView?.onPause()
    }

    fun onForeground() {
        inBackground = false
        webView?.onResume()
        reloadOnForeground?.let { url ->
            reloadOnForeground = null
            if (loadedUrl == null) show(url, restore = pendingRestore)
        }
    }

    fun exitFullscreen() = chrome.exit()

    internal fun attachTo(frame: FrameLayout) {
        this.frame = frame
        val view = view()
        (view.parent as? ViewGroup)?.removeView(view)
        frame.addView(view)
    }

    internal fun detachFrom(frame: FrameLayout) {
        if (this.frame === frame) this.frame = null
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

/**
 * The page the web player loads: [src] in a full-size iframe. [src] is escaped for the attribute it
 * sits in, and anything but an https player is replaced with a blank page.
 */
internal fun iframeHtml(src: String): String {
    val safe = if (src.startsWith("https://", ignoreCase = true)) escapeAttribute(src) else "about:blank"
    return """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style>
        </head><body><iframe src="$safe" allow="autoplay; fullscreen; encrypted-media; picture-in-picture; clipboard-write"
        allowfullscreen referrerpolicy="origin"></iframe></body></html>
    """.trimIndent()
}

private fun escapeAttribute(value: String): String = buildString(value.length) {
    for (c in value) {
        when (c) {
            '&' -> append("&amp;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(c)
        }
    }
}
