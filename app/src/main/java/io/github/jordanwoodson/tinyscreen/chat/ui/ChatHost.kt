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
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
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
        WebChat(adblock = settings.adblock, onOpenEmbed = onOpenEmbed, onOpenUrl = onOpenUrl, modifier = modifier)
    } else {
        NativeChat(settings, onOpenEmbed, onOpenUrl, onLogin, onWhispers, modifier)
    }
}

@Composable
private fun WebChat(adblock: Boolean, onOpenEmbed: (Embed) -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val route: (Uri) -> Boolean = route@{ uri ->
        val onDgg = uri.host?.endsWith("destiny.gg") == true
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
            adblock = { adblock },
            onNavigate = route,
            onPageFinished = { view, _ ->
                // "/embed <link>" changes the chat page's own hash when it isn't inside bigscreen
                view.evaluateJavascript(
                    "window.addEventListener('hashchange',function(){Tinyscreen.hash(location.hash)});",
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
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun hash(hash: String) {
                    Embed.fromHash(hash)?.let { e -> post { onOpenEmbed(e) } }
                }
            }, "Tinyscreen")
            loadUrl("https://www.destiny.gg/embed/chat?follow=%2Fbigscreen")
        }
    }
    DisposableEffect(webView) { onDispose { webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier)
}
