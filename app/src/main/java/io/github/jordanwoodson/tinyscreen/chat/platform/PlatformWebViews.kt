package io.github.jordanwoodson.tinyscreen.chat.platform

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.stream.resolvers.get
import io.github.jordanwoodson.tinyscreen.stream.resolvers.long
import io.github.jordanwoodson.tinyscreen.stream.resolvers.string
import io.github.jordanwoodson.tinyscreen.web.createWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A sign-in page to show full screen, for the platform whose chats reload afterwards. */
data class SignInRequest(val platform: String, val url: String, /** The stream, for "Open in app" instead. */ val appUrl: String)

/** Owns the platform chat WebViews and the lookups that decide which tabs exist; lives as long as the activity. */
class PlatformChatViewModel(app: Application) : AndroidViewModel(app) {
    val webViews = PlatformWebViews(app)

    private val _found = MutableStateFlow<Map<ChatLookup, ChatLookupResult>>(emptyMap())
    val found: StateFlow<Map<ChatLookup, ChatLookupResult>> = _found.asStateFlow()
    private val inFlight = mutableSetOf<ChatLookup>()

    /** The tab last shown, so the pager comes back to it after rotating or hiding chat. */
    var selectedKey: String = ChatTab.Dgg.key

    /** The user is on the chat of the stream bigscreen shows, so a new stream's chat takes over that tab. */
    var followOtherStream = false

    /** "Use the YouTube app" hint closed for this session. */
    var youTubeHintDismissed by mutableStateOf(false)

    private val _links = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Links tapped inside a platform chat, to open as an embed or externally. */
    val links: SharedFlow<String> = _links.asSharedFlow()

    private val _signIn = MutableStateFlow<SignInRequest?>(null)
    val signIn: StateFlow<SignInRequest?> = _signIn.asStateFlow()

    init {
        webViews.onNavigation = { tab, nav ->
            when (nav) {
                is ChatUrls.Navigation.Open -> _links.tryEmit(nav.url)
                is ChatUrls.Navigation.SignIn -> startSignIn(tab, nav.url)
                ChatUrls.Navigation.Stay, ChatUrls.Navigation.Ignore -> Unit
            }
        }
    }

    fun lookUp(lookups: Set<ChatLookup>) {
        lookups.filter { it !in _found.value && inFlight.add(it) }.forEach { lookup ->
            viewModelScope.launch {
                val result = ChatLookups.lookUpWithRetry(lookup)
                inFlight -= lookup
                _found.update { it + (lookup to result) }
            }
        }
    }

    fun startSignIn(tab: ChatTab.Platform, url: String? = null) {
        val start = url ?: ChatUrls.signIn(tab.platform) ?: return
        _signIn.value = SignInRequest(tab.platform, start, tab.watchUrl)
    }

    /** Back from the sign-in page: that platform's chats reload to pick up the new session. */
    fun finishSignIn() {
        val request = _signIn.value ?: return
        _signIn.value = null
        webViews.releaseSignIn()
        CookieManager.getInstance().flush()
        webViews.reloadPlatform(request.platform)
    }

    override fun onCleared() {
        webViews.destroyAll()
    }
}

/**
 * Platform chat WebViews, kept outside composition so switching tabs, rotating or hiding chat doesn't
 * reload them. Each sits in a [MutableContextWrapper] pointed at the activity while it's on screen and
 * at the application otherwise, so a kept WebView never holds on to a destroyed activity.
 */
class PlatformWebViews(private val app: Context) {
    private class Kept(val tab: ChatTab.Platform, val view: WebView, val context: MutableContextWrapper)
    private class SignInView(val request: SignInRequest, val view: WebView, val context: MutableContextWrapper)

    private val kept = mutableMapOf<String, Kept>()
    private var signIn: SignInView? = null

    private val _signInProgress = MutableStateFlow(0)
    /** Loading progress of the sign-in page, 0–100. */
    val signInProgress: StateFlow<Int> = _signInProgress.asStateFlow()

    /** Where every chat WebView sends links, sign-in pages and new windows. */
    var onNavigation: (ChatTab.Platform, ChatUrls.Navigation) -> Unit = { _, _ -> }

    @Volatile var adblock = true

    fun has(key: String): Boolean = key in kept

    /** The tab's WebView, created and loaded the first time it's asked for. */
    fun obtain(tab: ChatTab.Platform, activity: Context): WebView {
        val k = kept.getOrPut(tab.key) { create(tab) }
        k.context.baseContext = activity
        detach(k.view)
        return k.view
    }

    /** The tab left the screen; keep its WebView but let go of the activity. */
    fun release(key: String) {
        kept[key]?.context?.baseContext = app
    }

    /** Destroys the WebViews of tabs that no longer exist. */
    fun retain(keys: Set<String>) {
        (kept.keys - keys).toList().forEach { key -> kept.remove(key)?.let { destroy(it.view, it.context) } }
    }

    fun reload(key: String) {
        kept[key]?.let { it.view.loadUrl(it.tab.chatUrl) }
    }

    fun reloadPlatform(platform: String) {
        kept.values.filter { it.tab.platform == platform }.forEach { it.view.loadUrl(it.tab.chatUrl) }
    }

    /** Resumed while the tab is on screen, paused otherwise so hidden chats don't keep rendering. */
    fun setActive(key: String, active: Boolean) {
        val view = kept[key]?.view ?: return
        if (active) view.onResume() else view.onPause()
    }

    fun signInView(request: SignInRequest, activity: Context): WebView {
        val current = signIn?.takeIf { it.request == request } ?: run {
            releaseSignIn()
            _signInProgress.value = 0
            val context = MutableContextWrapper(app)
            val view = createWebView(
                context,
                adblock = { adblock },
                userAgent = Http.MOBILE_UA,
                // Pages that hand off to an app (intent://, market://) can't load here
                onNavigate = { uri -> uri.scheme != "http" && uri.scheme != "https" },
                onPageFinished = { v, url ->
                    val uri = url.toUri()
                    if (uri.host?.endsWith("kick.com") == true && uri.path == "/login") v.evaluateJavascript(KICK_OPEN_LOGIN, null)
                },
                chrome = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                        _signInProgress.value = newProgress
                    }
                },
            ).apply {
                disguiseAsBrowser(this)
                loadUrl(request.url)
            }
            SignInView(request, view, context).also { signIn = it }
        }
        current.context.baseContext = activity
        detach(current.view)
        return current.view
    }

    /** Goes back a page in the sign-in WebView; false when there's nothing to go back to. */
    fun signInBack(): Boolean {
        val view = signIn?.view?.takeIf { it.canGoBack() } ?: return false
        view.goBack()
        return true
    }

    fun releaseSignIn() {
        signIn?.let { destroy(it.view, it.context) }
        signIn = null
    }

    fun destroyAll() {
        kept.values.forEach { destroy(it.view, it.context) }
        kept.clear()
        releaseSignIn()
    }

    private fun create(tab: ChatTab.Platform): Kept {
        val context = MutableContextWrapper(app)
        val view = createWebView(
            context,
            adblock = { adblock },
            userAgent = Http.MOBILE_UA,
            onNavigate = { uri -> route(tab, uri.toString(), newWindow = false) },
            onRenderProcessGone = { gone, parent -> recoverRenderer(tab, gone, parent) },
            chrome = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    // target=_blank links: read the URL from a throwaway WebView, then route it
                    val probe = WebView(view.context)
                    probe.webViewClient = NewWindowProbe { url -> route(tab, url, newWindow = true) }
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            },
        ).apply {
            // The chat pages never need to play anything, and a second copy of the stream would be worse
            settings.mediaPlaybackRequiresUserGesture = true
            // YouTube's live chat has no viewport tag and would otherwise be laid out at desktop width
            settings.useWideViewPort = false
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            disguiseAsBrowser(this)
            loadUrl(tab.chatUrl)
        }
        return Kept(tab, view, context)
    }

    /** A fresh WebView for the tab, in the container the dead one sat in. */
    private fun recoverRenderer(tab: ChatTab.Platform, gone: WebView, parent: ViewGroup?) {
        val old = kept[tab.key]?.takeIf { it.view === gone } ?: return
        val fresh = create(tab)
        fresh.context.baseContext = old.context.baseContext
        old.context.baseContext = app
        kept[tab.key] = fresh
        parent?.addView(fresh.view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** True when the navigation was handled outside the chat WebView. */
    private fun route(tab: ChatTab.Platform, url: String, newWindow: Boolean): Boolean {
        val nav = ChatUrls.navigation(tab.chatUrl, url)
        if (nav == ChatUrls.Navigation.Stay && !newWindow) return false
        onNavigation(tab, if (nav == ChatUrls.Navigation.Stay) ChatUrls.Navigation.Open(url) else nav)
        return true
    }

    private fun detach(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun destroy(view: WebView, context: MutableContextWrapper) {
        detach(view)
        view.stopLoading()
        view.destroy()
        context.baseContext = app
    }
}

/** Catches the URL a new window (target=_blank, window.open) would load, then throws the window away. */
private class NewWindowProbe(private val onUrl: (String) -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        onUrl(request.url.toString())
        view.destroy()
        return true
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

/** kick.com/login is the home page; the login form is a dialog behind the account button. */
private const val KICK_OPEN_LOGIN = """
(function(){var n=0;var t=setInterval(function(){
  var open=[].some.call(document.querySelectorAll('input[type=password]'),function(e){return e.offsetWidth>0});
  var b=document.querySelector('[data-testid=login]');
  if(open||++n>20){clearInterval(t);return}
  if(b){b.click();clearInterval(t)}
},250)})();
"""

/**
 * Google refuses to sign in inside apps that announce themselves as a WebView. The user agent already
 * leaves out "; wv"; this makes the client hints claim Chrome instead of "Android WebView" too. The
 * X-Requested-With header with the package name can't be dropped any more (the allow-list API is a
 * no-op since the origin trial ended), so YouTube's "Open in app" fallback stays prominent.
 */
internal fun disguiseAsBrowser(view: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
    val settings = view.settings
    val chrome = Http.MOBILE_UA.substringAfter("Chrome/").substringBefore(' ')
    val major = chrome.substringBefore('.')
    fun brand(name: String, version: String, full: String) =
        UserAgentMetadata.BrandVersion.Builder().setBrand(name).setMajorVersion(version).setFullVersion(full).build()
    val metadata = UserAgentMetadata.Builder(WebSettingsCompat.getUserAgentMetadata(settings))
        .setBrandVersionList(listOf(brand("Google Chrome", major, chrome), brand("Chromium", major, chrome), brand("Not_A Brand", "24", "24.0.0.0")))
        .setFullVersion(chrome)
        .build()
    WebSettingsCompat.setUserAgentMetadata(settings, metadata)
}

/** Opens [url] in the platform's app when one handles it, otherwise through [fallback] (a Custom Tab). */
internal fun openInApp(context: Context, url: String, fallback: (String) -> Unit) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        fallback(url)
    }
}

/** Whether the shared cookie store holds a session for the platform; null when we can't tell. */
internal fun signedIn(platform: String): Boolean? = when (platform) {
    "youtube" -> CookieManager.getInstance().getCookie("https://www.youtube.com")
        ?.let { "SAPISID=" in it || "LOGIN_INFO=" in it } ?: false
    else -> null
}

/** The requests behind [ChatLookup]s. */
object ChatLookups {
    private const val TAG = "PlatformChat"

    suspend fun lookUpWithRetry(lookup: ChatLookup): ChatLookupResult {
        repeat(3) { attempt ->
            runCatching { return lookUp(lookup) }.onFailure { Log.w(TAG, "chat lookup failed for $lookup", it) }
            delay(2_000L shl attempt)
        }
        return ChatLookupResult(null)
    }

    suspend fun lookUp(lookup: ChatLookup): ChatLookupResult = when (lookup.platform) {
        "youtube" -> youtube(lookup.id)
        "rumble" -> rumble(lookup.id)
        else -> ChatLookupResult(null)
    }

    /** The live chat page only carries a chat renderer while the video is live (or about to be). */
    private suspend fun youtube(videoId: String): ChatLookupResult {
        // SOCS skips the EU cookie consent interstitial, which would hide the chat
        val body = Http.getString(ChatUrls.youtube(videoId), mapOf("Accept-Language" to "en-US", "Cookie" to "SOCS=CAI"))
        if ("liveChatRenderer" !in body) return ChatLookupResult(null)
        // Just for the tab label
        val channel = runCatching {
            val oembed = Http.getString("https://www.youtube.com/oembed?format=json&url=" + Uri.encode(ChatUrls.youtubeWatch(videoId)))
            Http.json.parseToJsonElement(oembed)["author_name"].string
        }.getOrNull()
        return ChatLookupResult(ChatUrls.youtube(videoId), channel = channel)
    }

    /** Rumble's popout chat is keyed by the numeric video id, which the embed API knows. */
    private suspend fun rumble(embedId: String): ChatLookupResult {
        val body = Http.getString(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=${embedId.take(16)}",
            // A browser User-Agent trips Cloudflare here
            mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 16)", "Accept" to "application/json"),
        )
        val json = Http.json.parseToJsonElement(body)
        val live = json["live"].long == 2L
        val vid = json["vid"].long
        return ChatLookupResult(
            chatUrl = vid?.takeIf { live }?.let { ChatUrls.rumble(it.toString()) },
            watchUrl = json["l"].string?.let { "https://rumble.com$it" },
            channel = json["author"]["name"].string,
        )
    }
}
