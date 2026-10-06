package io.github.jordanwoodson.tinyscreen.chat.platform

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.isNotEmpty
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import io.github.jordanwoodson.tinyscreen.ui.BigscreenViewModel
import io.github.jordanwoodson.tinyscreen.ui.platformIcon
import kotlinx.coroutines.launch

/**
 * destiny.gg chat ([dggChat]) plus a tab for each platform chat that makes sense right now: swipe or
 * tap between them. Every page stays composed, so destiny.gg chat isn't torn down while another tab
 * is shown; platform WebViews live in [PlatformWebViews] and survive rotation and hidden chat.
 */
@Composable
fun PlatformChatPager(
    vm: BigscreenViewModel,
    settings: AppSettings,
    onOpenEmbed: (Embed) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    dggChat: @Composable (Modifier) -> Unit,
) {
    val model: PlatformChatViewModel = viewModel()
    if (!settings.platformChatTabs) {
        LaunchedEffect(Unit) { model.webViews.retain(emptySet()) }
        dggChat(modifier)
        return
    }
    SideEffect { model.webViews.adblock = settings.adblock }

    val info by vm.streamInfo.collectAsState()
    val content by vm.content.collectAsState()
    val found by model.found.collectAsState()
    val options = ChatTabOptions(hidden = settings.hiddenChatPlatforms, otherStreams = settings.otherStreamChatTab)
    val computed = remember(info, content, found, options) { PlatformChats.tabs(info, content, found, options) }
    val tabs = computed.tabs
    val currentTabs by rememberUpdatedState(tabs)
    LaunchedEffect(computed.lookups) { model.lookUp(computed.lookups) }
    LaunchedEffect(tabs) { model.webViews.retain(tabs.mapNotNullTo(mutableSetOf()) { (it as? ChatTab.Platform)?.key }) }

    val openEmbed by rememberUpdatedState(onOpenEmbed)
    val openUrl by rememberUpdatedState(onOpenUrl)
    LaunchedEffect(model) {
        model.links.collect { url -> LinkParser.fromUrl(url)?.let(openEmbed) ?: openUrl(url) }
    }

    val pager = rememberPagerState(initialPage = tabs.indexOfFirst { it.key == model.selectedKey }.coerceAtLeast(0)) {
        currentTabs.size
    }
    val currentOther by rememberUpdatedState(computed.otherStream)
    LaunchedEffect(pager) {
        snapshotFlow { currentTabs.getOrNull(pager.currentPage)?.key }.collect { key ->
            if (key == null) return@collect
            // When the selected tab disappeared (bigscreen switched streams), the pager landing on a
            // neighbour isn't the user's choice
            val chosen = currentTabs.any { it.key == model.selectedKey }
            model.selectedKey = key
            if (chosen) model.followOtherStream = key == currentOther
        }
    }
    // Switching streams while reading the old stream's chat moves on to the new stream's chat. A YouTube
    // tab only appears once its live chat is confirmed, so this also covers that gap.
    LaunchedEffect(computed.otherStream) {
        val other = computed.otherStream ?: return@LaunchedEffect
        if (!model.followOtherStream) return@LaunchedEffect
        val index = tabs.indexOfFirst { it.key == other }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    val scope = rememberCoroutineScope()
    val selected = pager.currentPage.coerceAtMost(tabs.lastIndex)
    var onScreen by remember { mutableStateOf(true) }

    Column(modifier.onVisibilityChanged(minFractionVisible = 0.01f) { onScreen = it }) {
        if (tabs.size > 1) {
            ChatTabStrip(
                tabs = tabs,
                selected = selected,
                onSelect = { scope.launch { pager.animateScrollToPage(it) } },
                menu = (tabs[selected] as? ChatTab.Platform)?.let { tab ->
                    { TabMenu(tab, model, onOpenUrl) }
                },
            )
        }
        ChatPages(pager, tabs, model, dggChat, onOpenUrl, Modifier.weight(1f).fillMaxWidth())
    }

    // Kept, not dismissed, while chat is out of sight (picture-in-picture)
    val signIn by model.signIn.collectAsState()
    signIn?.takeIf { onScreen }?.let { SignInScreen(it, model, onOpenUrl) }
}

@Composable
private fun ChatPages(
    pager: PagerState,
    tabs: List<ChatTab>,
    model: PlatformChatViewModel,
    dggChat: @Composable (Modifier) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier,
) {
    HorizontalPager(
        state = pager,
        modifier = modifier,
        // Keep every page composed: leaving destiny.gg chat mustn't tear it down
        beyondViewportPageCount = tabs.size,
        key = { tabs.getOrNull(it)?.key ?: it },
    ) { page ->
        when (val tab = tabs.getOrNull(page)) {
            ChatTab.Dgg -> dggChat(Modifier.fillMaxSize())
            is ChatTab.Platform -> PlatformChatPage(tab, model, current = pager.currentPage == page, onOpenUrl = onOpenUrl)
            null -> Unit
        }
    }
}

@Composable
private fun PlatformChatPage(tab: ChatTab.Platform, model: PlatformChatViewModel, current: Boolean, onOpenUrl: (String) -> Unit) {
    // Created the first time the tab is shown, then kept
    var created by remember(tab.key) { mutableStateOf(model.webViews.has(tab.key)) }
    LaunchedEffect(current) { if (current) created = true }
    // Paused unless it's on screen: another page, picture-in-picture, hidden landscape chat and a
    // stopped app all leave the chat slot composed but out of sight
    var onScreen by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = created && onScreen && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val signIn by model.signIn.collectAsState()
    val signedIn = remember(signIn) { signedIn(tab.platform) }
    Column(Modifier.fillMaxSize().background(Color.Black).onVisibilityChanged(minFractionVisible = 0.01f) { onScreen = it }) {
        if (signedIn == false && tab.platform == "youtube" && !model.youTubeHintDismissed) {
            YouTubeAppHint(tab, onOpenUrl, onDismiss = { model.youTubeHintDismissed = true })
        }
        if (!created) return@Column
        AndroidView(
            factory = { ctx ->
                FrameLayout(ctx).apply {
                    addView(model.webViews.obtain(tab, ctx), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                }
            },
            onRelease = { container ->
                // After rotating, the new layout may already have taken the WebView
                if (container.isNotEmpty()) {
                    container.removeAllViews()
                    model.webViews.release(tab.key)
                }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        LaunchedEffect(active) { model.webViews.setActive(tab.key, active) }
        DisposableEffect(tab.key) { onDispose { model.webViews.setActive(tab.key, false) } }
    }
}

/** Google often refuses to sign in inside apps, so point at the YouTube app for chatting. */
@Composable
private fun YouTubeAppHint(tab: ChatTab.Platform, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF1C1D20)).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Google may not let you sign in here. To chat, open the stream in the YouTube app.",
            fontSize = 12.sp,
            color = Color(0xFFB0B4BA),
            modifier = Modifier.weight(1f).padding(vertical = 6.dp),
        )
        TextButton(onClick = { openInApp(context, tab.watchUrl, onOpenUrl) }) { Text("Open app") }
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp), tint = Color(0xFFB0B4BA))
        }
    }
}

@Composable
private fun ChatTabStrip(tabs: List<ChatTab>, selected: Int, onSelect: (Int) -> Unit, menu: (@Composable () -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF111113)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { i, tab -> TabChip(tab, selected = i == selected, onClick = { onSelect(i) }) }
        }
        menu?.invoke()
    }
}

@Composable
private fun TabChip(tab: ChatTab, selected: Boolean, onClick: () -> Unit) {
    val bring = remember { BringIntoViewRequester() }
    LaunchedEffect(selected) { if (selected) bring.bringIntoView() }
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else Color(0xFFB0B4BA)
    Row(
        Modifier
            .bringIntoViewRequester(bring)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color(0xFF1C1D20))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tab) {
            ChatTab.Dgg -> Icon(Icons.AutoMirrored.Filled.Chat, null, tint = fg, modifier = Modifier.size(14.dp))
            is ChatTab.Platform -> platformIcon(tab.platform)?.let { Icon(painterResource(it), null, tint = fg, modifier = Modifier.size(14.dp)) }
        }
        Spacer(Modifier.width(5.dp))
        Text(
            when (tab) {
                ChatTab.Dgg -> "DGG"
                is ChatTab.Platform -> tab.label
            },
            color = fg,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 150.dp),
        )
    }
}

@Composable
private fun TabMenu(tab: ChatTab.Platform, model: PlatformChatViewModel, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val name = Embed.platformName(tab.platform)
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Filled.MoreVert, "$name chat options", modifier = Modifier.size(18.dp), tint = Color(0xFFB0B4BA))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuItem("Reload", Icons.Filled.Refresh) { open = false; model.webViews.reload(tab.key) }
            if (ChatUrls.signIn(tab.platform) != null) {
                MenuItem("Sign in to $name", Icons.AutoMirrored.Filled.Login) { open = false; model.startSignIn(tab) }
            }
            MenuItem("Open in $name app", Icons.AutoMirrored.Filled.OpenInNew) { open = false; openInApp(context, tab.watchUrl, onOpenUrl) }
            MenuItem("Open chat in browser", Icons.Filled.Language) { open = false; onOpenUrl(tab.chatUrl) }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}

/** The platform's own login page, full screen; the chats reload with the new session afterwards. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInScreen(request: SignInRequest, model: PlatformChatViewModel, onOpenUrl: (String) -> Unit) {
    val context = LocalContext.current
    val progress by model.webViews.signInProgress.collectAsState()
    Dialog(
        onDismissRequest = model::finishSignIn,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false),
    ) {
        // Back steps back through the sign-in pages (a provider's page, then the platform's) before leaving
        BackHandler { if (!model.webViews.signInBack()) model.finishSignIn() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Sign in to ${Embed.platformName(request.platform)}") },
                    navigationIcon = { IconButton(onClick = model::finishSignIn) { Icon(Icons.Filled.Close, "Close") } },
                    actions = { TextButton(onClick = model::finishSignIn) { Text("Done") } },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                if (request.platform == "youtube") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "If Google says this browser or app may not be secure, chat from the YouTube app instead.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { model.finishSignIn(); openInApp(context, request.appUrl, onOpenUrl) }) { Text("Open app") }
                    }
                }
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            addView(model.webViews.signInView(request, ctx), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    },
                    onRelease = { it.removeAllViews() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}
