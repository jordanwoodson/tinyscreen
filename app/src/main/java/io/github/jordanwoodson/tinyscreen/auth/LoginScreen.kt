package io.github.jordanwoodson.tinyscreen.auth

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.isDestinyHost
import io.github.jordanwoodson.tinyscreen.web.createWebView
import kotlinx.coroutines.launch

/**
 * Signs in through destiny.gg's own login page. The WebView's cookie store is shared with
 * OkHttp, so once the session cookie is set the chat socket is authenticated too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TinyscreenApp).graph
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }

    val webView = remember {
        createWebView(
            context,
            adblock = { graph.settings.settings.value.adblock },
            userAgent = Http.MOBILE_UA,
            onPageFinished = { view, url ->
                loading = false
                val uri = Uri.parse(url)
                if (uri.path.orEmpty().startsWith("/login")) {
                    // Stay signed in: the site re-issues the chat session from the rememberme cookie
                    view.evaluateJavascript(
                        "document.querySelectorAll('input[type=checkbox]').forEach(function(c){" +
                            "if(/remember/i.test((c.name||'')+(c.id||'')+(c.parentElement&&c.parentElement.textContent||''))){c.checked=true;" +
                            "c.dispatchEvent(new Event('change',{bubbles:true}));}});",
                        null,
                    )
                }
                val onDgg = isDestinyHost(uri.host)
                val path = uri.path.orEmpty()
                if (onDgg && !path.startsWith("/login") && !path.startsWith("/auth") && !path.startsWith("/register")) {
                    status = "Checking session…"
                    scope.launch {
                        if (graph.chat.refreshMe()) {
                            graph.chat.reconnect()
                            onDone()
                        } else {
                            status = null
                        }
                    }
                }
            },
        )
    }
    DisposableEffect(webView) {
        webView.loadUrl("https://www.destiny.gg/login")
        onDispose { webView.destroy() }
    }
    // Back steps back through the sign-in pages (a provider's page, then destiny.gg's) before leaving
    BackHandler { if (webView.canGoBack()) webView.goBack() else onDone() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in to destiny.gg") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, "Close") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading || status != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
            }
            Box(Modifier.weight(1f)) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
