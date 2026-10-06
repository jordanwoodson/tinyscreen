package io.github.jordanwoodson.tinyscreen.chat.ui

import android.net.Uri
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.net.isDestinyHost
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import io.github.jordanwoodson.tinyscreen.web.createWebView

/** Native chat by default; destiny.gg's own chat page when "Use web chat" is on. */
@Composable
fun ChatHost(
    settings: AppSettings,
    onOpenEmbed: (Embed) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLogin: () -> Unit,
    onWhispers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = (LocalContext.current.applicationContext as TinyscreenApp).graph
    if (settings.webChat) {
        LaunchedEffect(Unit) { graph.chat.stop() }
        WebChat(onOpenEmbed = onOpenEmbed, onOpenUrl = onOpenUrl, modifier = modifier)
    } else {
        NativeChat(settings, onOpenEmbed, onOpenUrl, onLogin, onWhispers, modifier)
    }
}

/** Origins allowed to talk to the app through the web chat's `Tinyscreen` bridge. */
private val CHAT_ORIGINS = setOf("https://www.destiny.gg", "https://destiny.gg")

@Composable
private fun WebChat(onOpenEmbed: (Embed) -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TinyscreenApp).graph
    val route: (Uri) -> Boolean = route@{ uri ->
        val onDgg = isDestinyHost(uri.host)
        if (onDgg && uri.path?.startsWith("/embed/chat") == true) return@route false
        val embed = if (onDgg && uri.path?.startsWith("/bigscreen") == true) uri.fragment?.let { Embed.fromHash("#$it") } else null
        when {
            embed != null -> onOpenEmbed(embed)
            else -> LinkParser.fromUrl(uri.toString())?.let(onOpenEmbed) ?: onOpenUrl(uri.toString())
        }
        true
    }
    val webView = remember {
        createWebView(
            context,
            adblock = { graph.settings.settings.value.adblock },
            onNavigate = route,
            onPageFinished = { view, _ ->
                // "/embed <link>" changes the chat page's own hash when it isn't inside bigscreen
                view.evaluateJavascript(
                    "window.addEventListener('hashchange',function(){if(window.Tinyscreen)Tinyscreen.postMessage(location.hash)});",
                    null,
                )
            },
            chrome = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    // target=_blank links: read the URL from a throwaway WebView, then route it
                    val probe = WebView(view.context)
                    probe.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView, request: android.webkit.WebResourceRequest): Boolean {
                            route(request.url)
                            v.destroy()
                            return true
                        }
                    }
                    (resultMsg.obj as WebView.WebViewTransport).webView = probe
                    resultMsg.sendToTarget()
                    return true
                }
            },
        ).apply {
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            // Only destiny.gg's chat page itself gets the bridge: not other sites, and not frames inside it
            // (embeds, link previews), which could otherwise change what's playing
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                WebViewCompat.addWebMessageListener(this, "Tinyscreen", CHAT_ORIGINS) { _, message, origin, isMainFrame, _ ->
                    if (isMainFrame && isDestinyHost(origin.host)) {
                        message.data?.let(Embed::fromHash)?.let(onOpenEmbed)
                    }
                }
            }
            loadUrl("https://www.destiny.gg/embed/chat?follow=%2Fbigscreen")
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier)
}
