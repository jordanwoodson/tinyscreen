package io.github.jordanwoodson.tinyscreen.ui

import android.content.Intent
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jordanwoodson.tinyscreen.HostActions
import io.github.jordanwoodson.tinyscreen.MainActivity
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.chat.ui.ChatHost
import io.github.jordanwoodson.tinyscreen.player.VideoPlayer
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.ui.theme.TinyscreenTheme
import io.github.jordanwoodson.tinyscreen.web.WebEmbedHolder
import io.github.jordanwoodson.tinyscreen.web.WebEmbedPlayer
import io.github.jordanwoodson.tinyscreen.web.firstUrl
import io.github.jordanwoodson.tinyscreen.web.isBigscreenLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Screens drawn over the main screen, which stays composed underneath. */
private enum class Overlay { Settings, Login, Whispers }

@Composable
fun TinyscreenRoot(activity: MainActivity, host: HostActions) {
    val graph = (activity.application as TinyscreenApp).graph
    val settings by graph.settings.settings.collectAsState()
    TinyscreenTheme(dynamicColor = settings.dynamicColor, pureBlack = settings.pureBlack) {
        val vm: BigscreenViewModel = viewModel()
        val webEmbed: WebEmbedHolder = viewModel()
        val inPip by activity.inPip
        val landscape by activity.isLandscape
        // Settings, Login and Whispers cover the main screen instead of replacing it, so the stream
        // keeps playing and chat keeps its place, scroll and open sheets
        var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
        val snackbar = remember { SnackbarHostState() }
        // Every link the app opens goes through here. A bigscreen link without an embed means Destiny's
        // own stream; handing it to the browser could just bring it back to Tinyscreen.
        val openUrl: (String) -> Unit = remember(vm, host) {
            { url ->
                if (!isBigscreenLink(url)) {
                    host.openExternal(url)
                } else if (!vm.openLink(url)) {
                    vm.closeEmbed()
                }
            }
        }

        LaunchedEffect(Unit) {
            val effects = this
            activity.incomingLinks.collect { link ->
                @OptIn(ExperimentalCoroutinesApi::class)
                activity.incomingLinks.resetReplayCache()
                overlay = null
                val url = firstUrl(link)
                when {
                    vm.openLink(link) -> Unit
                    url != null && isBigscreenLink(url) -> vm.closeEmbed()
                    // Shared text: say so rather than sending it to the browser unasked
                    url != null -> effects.launch {
                        val result = snackbar.showSnackbar("Tinyscreen can't play this link", actionLabel = "Open")
                        if (result == SnackbarResult.ActionPerformed) host.openExternal(url)
                    }
                    else -> effects.launch { snackbar.showSnackbar("There's no link to play in that") }
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
            MainScreen(
                vm = vm,
                host = host,
                activity = activity,
                webEmbed = webEmbed,
                settings = settings,
                inPip = inPip,
                landscape = landscape,
                overlayOpen = overlay != null,
                snackbar = snackbar,
                openUrl = openUrl,
                onSettings = { overlay = Overlay.Settings },
                onLogin = { overlay = Overlay.Login },
                onWhispers = { overlay = Overlay.Whispers },
            )
            overlay?.let { current ->
                // Hidden rather than removed in picture-in-picture, so it's still there afterwards
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize().then(if (inPip) Modifier.composedButHidden() else Modifier),
                ) {
                    when (current) {
                        Overlay.Settings -> {
                            BackHandler { overlay = null }
                            val metered by graph.network.metered.collectAsState()
                            SettingsScreen(settings, metered, onChange = { graph.settings.launchUpdate(graph.scope, it) }, onBack = { overlay = null })
                        }
                        Overlay.Login -> {
                            BackHandler { overlay = null }
                            io.github.jordanwoodson.tinyscreen.auth.LoginScreen(onDone = { overlay = null })
                        }
                        Overlay.Whispers -> {
                            BackHandler { overlay = null }
                            io.github.jordanwoodson.tinyscreen.chat.ui.WhispersScreen(onBack = { overlay = null })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Measures the content but never places it: it stays composed, keeping its state and any WebViews,
 * without being drawn or reachable by touch.
 */
private fun Modifier.composedButHidden(): Modifier = layout { measurable, constraints ->
    measurable.measure(constraints)
    layout(0, 0) {}
}

@Composable
private fun MainScreen(
    vm: BigscreenViewModel,
    host: HostActions,
    activity: MainActivity,
    webEmbed: WebEmbedHolder,
    settings: AppSettings,
    inPip: Boolean,
    landscape: Boolean,
    overlayOpen: Boolean,
    snackbar: SnackbarHostState,
    openUrl: (String) -> Unit,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
    onWhispers: () -> Unit,
) {
    val graph = (activity.application as TinyscreenApp).graph
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val me by graph.chat.me.collectAsState()
    val unread by graph.chat.unreadWhispers.collectAsState()
    var cinema by rememberSaveable { mutableStateOf(false) }
    var chatOnly by rememberSaveable { mutableStateOf(false) }

    val playback by vm.playback.collectAsState()
    LaunchedEffect(playback) {
        (playback as? Playback.Web)?.note?.let { snackbar.showSnackbar(it) }
    }
    // The web player lives outside composition (see WebEmbedHolder): load what should play, blank it otherwise
    val webUrl = (playback as? Playback.Web)?.url
    LaunchedEffect(webUrl) { webEmbed.show(webUrl) }
    LaunchedEffect(inPip) { if (inPip) drawer.snapTo(DrawerValue.Closed) }

    BackHandler(enabled = !overlayOpen && drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !overlayOpen && landscape && !drawer.isOpen) { host.setFullscreen(false) }
    BackHandler(enabled = !overlayOpen && chatOnly && !landscape) { chatOnly = false }

    val openEmbed: (Embed) -> Unit = remember(vm) { { embed -> vm.setEmbed(embed); chatOnly = false } }

    // The stream and chat are each built in exactly one place and moved between the portrait,
    // landscape and picture-in-picture layouts, so switching layouts never reloads a web player or
    // resets chat. Both are remembered once, so they only capture stable values.
    val stream = remember(vm, host, webEmbed) {
        movableContentOf { modifier: Modifier, inPip: Boolean, fullscreen: Boolean ->
            StreamArea(vm = vm, host = host, webEmbed = webEmbed, inPip = inPip, isFullscreen = fullscreen, modifier = modifier)
        }
    }
    val currentOnLogin by rememberUpdatedState(onLogin)
    val currentOnWhispers by rememberUpdatedState(onWhispers)
    val currentOpenUrl by rememberUpdatedState(openUrl)
    val chat = remember(openEmbed) {
        movableContentOf { modifier: Modifier ->
            ChatSlot(
                onOpenEmbed = openEmbed,
                onOpenUrl = { currentOpenUrl(it) },
                onLogin = { currentOnLogin() },
                onWhispers = { currentOnWhispers() },
                modifier = modifier,
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            AppDrawer(
                nick = me?.nick,
                unreadWhispers = unread,
                onOpenUrl = { scope.launch { drawer.close() }; openUrl(it) },
                onBigscreen = { scope.launch { drawer.close() }; vm.closeEmbed() },
                onLogin = { scope.launch { drawer.close() }; onLogin() },
                onLogout = { scope.launch { drawer.close() }; graph.chat.logout() },
                onWhispers = { scope.launch { drawer.close() }; onWhispers() },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
            )
        },
    ) {
        // Not a Scaffold: it composes its content during measure, in a separate composition, and the
        // stream and chat slots must stay in this one to move between layouts with their state
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (inPip) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    stream(Modifier.fillMaxSize(), true, false)
                    // Chat stays loaded behind the picture-in-picture window
                    chat(Modifier.fillMaxSize().composedButHidden())
                }
            } else if (landscape) {
                LandscapeLayout(
                    vm = vm,
                    host = host,
                    settings = settings,
                    cinema = cinema,
                    onCinema = { cinema = !cinema },
                    stream = stream,
                    chat = chat,
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding(),
                ) {
                    TopBar(
                        nick = me?.nick,
                        onMenu = { scope.launch { drawer.open() } },
                        onLogin = onLogin,
                        content = vm.content.collectAsState().value,
                    )
                    if (chatOnly) {
                        MiniPlayerBar(vm = vm, webEmbed = webEmbed, onExpand = { chatOnly = false })
                    } else {
                        stream(Modifier.fillMaxWidth().aspectRatio(16f / 9f), false, false)
                        if (!cinema) StreamControlsFor(vm, host, activity)
                    }
                    if (settings.showTopEmbeds) {
                        val embeds by vm.embeds.collectAsState()
                        val content by vm.content.collectAsState()
                        TopEmbedsRow(embeds = embeds, current = content.embed, onSelect = openEmbed)
                    }
                    ChatControlBar(
                        cinema = cinema,
                        onRefresh = { graph.chat.reconnect() },
                        onCinema = { cinema = !cinema },
                        onPopout = { chatOnly = !chatOnly },
                        onSwap = null,
                        onClose = null,
                    )
                    chat(Modifier.weight(1f).fillMaxWidth())
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

/**
 * The chat pane: native chat, or destiny.gg's own chat page when "Use web chat" is on. This is the
 * one place that builds it; the layouts in [MainScreen] only decide where it goes.
 */
@Composable
private fun ChatSlot(
    onOpenEmbed: (Embed) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLogin: () -> Unit,
    onWhispers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = (LocalContext.current.applicationContext as TinyscreenApp).graph
    val settings by graph.settings.settings.collectAsState()
    ChatHost(
        settings = settings,
        onOpenEmbed = onOpenEmbed,
        onOpenUrl = onOpenUrl,
        onLogin = onLogin,
        onWhispers = onWhispers,
        modifier = modifier,
    )
}

@Composable
private fun TopBar(nick: String?, onMenu: () -> Unit, onLogin: () -> Unit, content: Content) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, "Menu") }
        Text("Tinyscreen", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (content is Content.Live) {
            Spacer(Modifier.width(8.dp))
            Badge("Live", BadgeTone.Danger)
        }
        Spacer(Modifier.weight(1f))
        if (nick != null) {
            Text(nick, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 12.dp))
        } else {
            IconButton(onClick = onLogin) { Icon(Icons.AutoMirrored.Filled.Login, "Log in") }
        }
    }
}

@Composable
private fun StreamControlsFor(vm: BigscreenViewModel, host: HostActions, activity: MainActivity) {
    val content by vm.content.collectAsState()
    val playback by vm.playback.collectAsState()
    val graph = (activity.application as TinyscreenApp).graph
    StreamControls(
        content = content,
        resolvedTitle = (playback as? Playback.Native)?.stream?.title,
        usingWebPlayer = playback is Playback.Web,
        canTogglePlayer = content.embed?.let { graph.resolvers.supports(it) && it.webEmbedUrl() != null } == true,
        onChangePlatform = vm::changePlatform,
        onCloseEmbed = vm::closeEmbed,
        onOpenUrl = host::openExternal,
        onShare = { url ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            activity.startActivity(Intent.createChooser(send, null))
        },
        onTogglePlayer = vm::togglePlayerType,
        onReload = vm::reload,
    )
}

/** Player, web embed, offline or blocked view — whatever the stream panel currently shows. */
@Composable
private fun StreamArea(
    vm: BigscreenViewModel,
    host: HostActions,
    webEmbed: WebEmbedHolder,
    inPip: Boolean,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
) {
    val graph = (LocalContext.current.applicationContext as TinyscreenApp).graph
    val settings by graph.settings.settings.collectAsState()
    val content by vm.content.collectAsState()
    val playback by vm.playback.collectAsState()
    val blocked by vm.blockedReason.collectAsState()
    val vod by vm.latestVod.collectAsState()
    val adBreak by io.github.jordanwoodson.tinyscreen.player.AdBreakMonitor.active.collectAsState()
    // The landscape layout offers a chat toggle on the player
    val chatVisible = if (isFullscreen) settings.landscapeChatVisible else null
    val onToggleChat = if (isFullscreen) {
        { graph.settings.launchUpdate(graph.scope) { it.copy(landscapeChatVisible = !it.landscapeChatVisible) } }
    } else {
        null
    }
    Box(
        modifier
            .background(Color.Black)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                host.setVideoBounds(Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt()))
            },
    ) {
        val reason = blocked
        when {
            reason != null -> BlockedPanel(reason, Modifier.fillMaxSize())
            content is Content.Offline -> OfflinePanel(vod, onOpenVod = { vm.openLink(it) }, modifier = Modifier.fillMaxSize())
            else -> when (val p = playback) {
                is Playback.Native -> VideoPlayer(
                    holder = vm.player,
                    title = titleFor(content) ?: p.stream.title,
                    platformLabel = p.stream.subtitle ?: p.embed.platformName,
                    isFullscreen = isFullscreen,
                    inPip = inPip,
                    adBreak = adBreak,
                    onToggleFullscreen = { host.setFullscreen(!isFullscreen) },
                    onEnterPip = host::enterPip,
                    chatVisible = chatVisible,
                    onToggleChat = onToggleChat,
                    onReload = vm::reload,
                    modifier = Modifier.fillMaxSize(),
                )
                is Playback.Web -> WebEmbedPlayer(webEmbed, modifier = Modifier.fillMaxSize())
                is Playback.Resolving -> FillBox { CircularProgressIndicator(color = Color.White) }
                is Playback.Failed -> FillBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(p.message, color = Color.White, modifier = Modifier.padding(16.dp))
                        FilledTonalButton(onClick = vm::reload) {
                            Icon(Icons.Filled.Refresh, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry")
                        }
                    }
                }
                Playback.Idle -> Unit
            }
        }
        if (playback is Playback.Web && !inPip) {
            // Web players have their own controls; offer fullscreen + PiP-less rotation only
            if (onToggleChat != null) {
                IconButton(onClick = onToggleChat, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.AutoMirrored.Filled.Chat, "Chat", tint = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

private fun titleFor(content: Content): String? = when (content) {
    is Content.Embedded -> content.meta?.metadata?.title ?: content.meta?.metadata?.displayName
    is Content.Host -> content.hosting.title ?: content.hosting.displayName
    is Content.Live -> content.entry.statusText
    Content.Offline -> null
}

@Composable
private fun MiniPlayerBar(vm: BigscreenViewModel, webEmbed: WebEmbedHolder, onExpand: () -> Unit) {
    val content by vm.content.collectAsState()
    val playback by vm.playback.collectAsState()
    val state = io.github.jordanwoodson.tinyscreen.player.rememberPlayerUiState(vm.player.player)
    // A web player keeps playing out of sight (see WebEmbedHolder), so the button controls whichever is in use
    val web = playback is Playback.Web
    val webPlaying by webEmbed.playing.collectAsState()
    val playing = if (web) webPlaying else state.playWhenReady
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onExpand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(titleFor(content) ?: content.embed?.displayId ?: "Destiny is offline", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 13.sp)
        IconButton(onClick = {
            when {
                web -> webEmbed.togglePlayback()
                playing -> vm.player.player.pause()
                else -> vm.player.player.play()
            }
        }) {
            Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
        }
    }
}

@Composable
fun ChatControlBar(
    cinema: Boolean,
    onRefresh: () -> Unit,
    onCinema: () -> Unit,
    onPopout: () -> Unit,
    onSwap: (() -> Unit)?,
    onClose: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF111113)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarButton(Icons.Filled.Refresh, "Refresh", onClick = onRefresh)
        Row(
            Modifier
                .padding(horizontal = 4.dp)
                .background(if (cinema) MaterialTheme.colorScheme.primaryContainer else Color(0xFF1C1D20), RoundedCornerShape(6.dp))
                .clickable(onClick = onCinema)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tv, null, modifier = Modifier.size(16.dp), tint = Color(0xFFB0B4BA))
            Spacer(Modifier.width(4.dp))
            Text("Cinema Mode", fontSize = 12.sp, color = Color(0xFFB0B4BA))
        }
        Spacer(Modifier.weight(1f))
        BarButton(Icons.AutoMirrored.Filled.OpenInNew, "Popout", onClick = onPopout)
        onSwap?.let { BarButton(Icons.Filled.SwapHoriz, "Swap", onClick = it) }
        onClose?.let { BarButton(Icons.Filled.Close, "Close", onClick = it) }
    }
}

@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(icon, label, modifier = Modifier.size(18.dp), tint = Color(0xFFB0B4BA))
    }
}

@Composable
private fun LandscapeLayout(
    vm: BigscreenViewModel,
    host: HostActions,
    settings: AppSettings,
    cinema: Boolean,
    onCinema: () -> Unit,
    stream: @Composable (modifier: Modifier, inPip: Boolean, fullscreen: Boolean) -> Unit,
    chat: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as MainActivity
    val graph = (activity.application as TinyscreenApp).graph
    val chatVisible = settings.landscapeChatVisible
    val toggleChat = { graph.settings.launchUpdate(graph.scope) { it.copy(landscapeChatVisible = !it.landscapeChatVisible) } }
    var dragFraction by remember { mutableFloatStateOf(settings.chatWidthFraction) }
    var totalWidth by remember { mutableIntStateOf(0) }
    val dragState = rememberDraggableState { delta ->
        if (totalWidth > 0) {
            val signed = if (settings.chatOnLeft) delta else -delta
            dragFraction = ((totalWidth * dragFraction + signed) / totalWidth).coerceIn(0.15f, 0.7f)
        }
    }
    // Stream controls only fit under the video on tall screens
    val tall = LocalConfiguration.current.screenHeightDp > 560

    Box(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
        // A plain Layout rather than BoxWithConstraints, which composes in a separate composition that
        // the stream and chat slots can't move into; swapping sides only changes where things are placed
        Layout(
            content = {
                Column(Modifier.layoutId(LandscapePart.Stream)) {
                    stream(Modifier.weight(1f).fillMaxWidth(), false, true)
                    if (!cinema && tall) {
                        StreamControlsFor(vm, host, activity)
                    }
                }
                if (chatVisible) {
                    Box(
                        Modifier
                            .layoutId(LandscapePart.Handle)
                            .width(10.dp)
                            .background(Color(0xFF111113))
                            .draggable(
                                dragState,
                                Orientation.Horizontal,
                                onDragStopped = {
                                    val f = dragFraction
                                    graph.settings.launchUpdate(graph.scope) { it.copy(chatWidthFraction = f) }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.width(3.dp).height(36.dp).background(Color(0xFF43484E), CircleShape))
                    }
                    Column(Modifier.layoutId(LandscapePart.Chat).background(MaterialTheme.colorScheme.background).imePadding()) {
                        if (settings.showTopEmbeds) {
                            val embeds by vm.embeds.collectAsState()
                            val content by vm.content.collectAsState()
                            TopEmbedsRow(embeds = embeds, current = content.embed, onSelect = { vm.setEmbed(it) })
                        }
                        ChatControlBar(
                            cinema = cinema,
                            onRefresh = { graph.chat.reconnect() },
                            onCinema = onCinema,
                            onPopout = toggleChat,
                            onSwap = { graph.settings.launchUpdate(graph.scope) { it.copy(chatOnLeft = !it.chatOnLeft) } },
                            onClose = toggleChat,
                        )
                        chat(Modifier.weight(1f).fillMaxWidth())
                    }
                } else {
                    // Hidden chat stays loaded, so showing it again doesn't start over
                    Box(Modifier.layoutId(LandscapePart.Hidden)) { chat(Modifier.composedButHidden()) }
                }
            },
            modifier = Modifier.fillMaxSize().onSizeChanged { totalWidth = it.width },
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            fun part(id: LandscapePart) = measurables.firstOrNull { it.layoutId == id }
            val handle = part(LandscapePart.Handle)?.measure(Constraints.fixedHeight(height))
            val handleWidth = handle?.width ?: 0
            val minChat = 260.dp.roundToPx()
            val chatWidth = if (handle == null) {
                0
            } else {
                (width * dragFraction).roundToInt().coerceIn(minChat, (width - 300.dp.roundToPx()).coerceAtLeast(minChat))
            }
            val chatPanel = part(LandscapePart.Chat)?.measure(Constraints.fixed(chatWidth, height))
            val hidden = part(LandscapePart.Hidden)?.measure(constraints)
            val streamWidth = (width - chatWidth - handleWidth).coerceAtLeast(0)
            val streamPanel = part(LandscapePart.Stream)!!.measure(Constraints.fixed(streamWidth, height))
            layout(width, height) {
                hidden?.place(0, 0)
                if (settings.chatOnLeft) {
                    chatPanel?.place(0, 0)
                    handle?.place(chatWidth, 0)
                    streamPanel.place(chatWidth + handleWidth, 0)
                } else {
                    streamPanel.place(0, 0)
                    handle?.place(streamWidth, 0)
                    chatPanel?.place(streamWidth + handleWidth, 0)
                }
            }
        }
        AnimatedVisibility(
            visible = !chatVisible,
            modifier = Modifier.align(if (settings.chatOnLeft) Alignment.CenterStart else Alignment.CenterEnd),
        ) {
            Surface(
                color = Color(0xFF1C1D20).copy(alpha = 0.85f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.padding(4.dp).clickable { toggleChat() },
            ) {
                Icon(Icons.AutoMirrored.Filled.Chat, "Open chat", tint = Color.White, modifier = Modifier.padding(10.dp))
            }
        }
    }
}

private enum class LandscapePart { Stream, Handle, Chat, Hidden }
