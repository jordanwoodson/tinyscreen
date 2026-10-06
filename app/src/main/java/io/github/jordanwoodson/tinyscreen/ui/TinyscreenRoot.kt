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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import io.github.jordanwoodson.tinyscreen.web.WebEmbedPlayer
import kotlinx.coroutines.launch

private enum class Screen { Main, Settings, Login, Whispers }

@Composable
fun TinyscreenRoot(activity: MainActivity, host: HostActions) {
    val graph = (activity.application as TinyscreenApp).graph
    val settings by graph.settings.settings.collectAsState()
    TinyscreenTheme(dynamicColor = settings.dynamicColor, pureBlack = settings.pureBlack) {
        val vm: BigscreenViewModel = viewModel()
        val inPip by activity.inPip
        val landscape by activity.isLandscape
        var screen by rememberSaveable { mutableStateOf(Screen.Main) }

        LaunchedEffect(Unit) {
            activity.incomingLinks.collect { link ->
                if (!vm.openLink(link)) host.openExternal(link)
                activity.incomingLinks.resetReplayCache()
                screen = Screen.Main
            }
        }

        if (inPip) {
            StreamArea(vm = vm, host = host, settings = settings, inPip = true, isFullscreen = false, modifier = Modifier.fillMaxSize())
            return@TinyscreenTheme
        }

        when (screen) {
            Screen.Settings -> {
                BackHandler { screen = Screen.Main }
                val metered by graph.network.metered.collectAsState()
                SettingsScreen(settings, metered, onChange = { graph.settings.launchUpdate(graph.scope, it) }, onBack = { screen = Screen.Main })
            }
            Screen.Login -> {
                BackHandler { screen = Screen.Main }
                io.github.jordanwoodson.tinyscreen.auth.LoginScreen(onDone = { screen = Screen.Main })
            }
            Screen.Whispers -> {
                BackHandler { screen = Screen.Main }
                io.github.jordanwoodson.tinyscreen.chat.ui.WhispersScreen(onBack = { screen = Screen.Main })
            }
            Screen.Main -> MainScreen(
                vm = vm,
                host = host,
                activity = activity,
                settings = settings,
                landscape = landscape,
                onSettings = { screen = Screen.Settings },
                onLogin = { screen = Screen.Login },
                onWhispers = { screen = Screen.Whispers },
            )
        }
    }
}

@Composable
private fun MainScreen(
    vm: BigscreenViewModel,
    host: HostActions,
    activity: MainActivity,
    settings: AppSettings,
    landscape: Boolean,
    onSettings: () -> Unit,
    onLogin: () -> Unit,
    onWhispers: () -> Unit,
) {
    val graph = (activity.application as TinyscreenApp).graph
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val me by graph.chat.me.collectAsState()
    val unread by graph.chat.unreadWhispers.collectAsState()
    var cinema by rememberSaveable { mutableStateOf(false) }
    var chatOnly by rememberSaveable { mutableStateOf(false) }

    val playback by vm.playback.collectAsState()
    LaunchedEffect(playback) {
        (playback as? Playback.Web)?.note?.let { snackbar.showSnackbar(it) }
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = landscape && !drawer.isOpen) { host.setFullscreen(false) }
    BackHandler(enabled = chatOnly && !landscape) { chatOnly = false }

    val openEmbed: (Embed) -> Unit = { vm.setEmbed(it); chatOnly = false }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            AppDrawer(
                nick = me?.nick,
                unreadWhispers = unread,
                onOpenUrl = { scope.launch { drawer.close() }; host.openExternal(it) },
                onBigscreen = { scope.launch { drawer.close() }; vm.closeEmbed() },
                onLogin = { scope.launch { drawer.close() }; onLogin() },
                onLogout = { scope.launch { drawer.close() }; graph.chat.logout() },
                onWhispers = { scope.launch { drawer.close() }; onWhispers() },
                onSettings = { scope.launch { drawer.close() }; onSettings() },
            )
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            val chat: @Composable (Modifier) -> Unit = { mod ->
                ChatHost(
                    settings = settings,
                    onOpenEmbed = openEmbed,
                    onOpenUrl = host::openExternal,
                    onLogin = onLogin,
                    onWhispers = onWhispers,
                    modifier = mod,
                )
            }
            if (landscape) {
                LandscapeLayout(
                    vm = vm,
                    host = host,
                    settings = settings,
                    cinema = cinema,
                    onCinema = { cinema = !cinema },
                    chat = chat,
                    modifier = Modifier.padding(padding),
                )
            } else {
                Column(
                    Modifier
                        .padding(padding)
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
                        MiniPlayerBar(vm = vm, onExpand = { chatOnly = false })
                    } else {
                        StreamArea(
                            vm = vm,
                            host = host,
                            settings = settings,
                            inPip = false,
                            isFullscreen = false,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        )
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
        }
    }
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
fun StreamArea(
    vm: BigscreenViewModel,
    host: HostActions,
    settings: AppSettings,
    inPip: Boolean,
    isFullscreen: Boolean,
    modifier: Modifier = Modifier,
    chatVisible: Boolean? = null,
    onToggleChat: (() -> Unit)? = null,
) {
    val content by vm.content.collectAsState()
    val playback by vm.playback.collectAsState()
    val blocked by vm.blockedReason.collectAsState()
    val vod by vm.latestVod.collectAsState()
    val adBreak by io.github.jordanwoodson.tinyscreen.player.AdBreakMonitor.active.collectAsState()
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
                is Playback.Web -> WebEmbedPlayer(p.url, adblock = settings.adblock, modifier = Modifier.fillMaxSize())
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
private fun MiniPlayerBar(vm: BigscreenViewModel, onExpand: () -> Unit) {
    val content by vm.content.collectAsState()
    val state = io.github.jordanwoodson.tinyscreen.player.rememberPlayerUiState(vm.player.player)
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onExpand)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Tv, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(titleFor(content) ?: content.embed?.displayId ?: "Destiny is offline", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 13.sp)
        IconButton(onClick = { if (state.playWhenReady) vm.player.player.pause() else vm.player.player.play() }) {
            Icon(if (state.playWhenReady) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
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
    chat: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val graph = (activity.application as TinyscreenApp).graph
    val chatVisible = settings.landscapeChatVisible
    val toggleChat = { graph.settings.launchUpdate(graph.scope) { it.copy(landscapeChatVisible = !it.landscapeChatVisible) } }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.displayCutout)) {
        val total = maxWidth
        val panelHeight = maxHeight
        var dragFraction by remember { mutableFloatStateOf(settings.chatWidthFraction) }
        val minChat = 260.dp
        val chatWidth: Dp = (total * dragFraction).coerceIn(minChat, (total - 300.dp).coerceAtLeast(minChat))
        val density = LocalDensity.current
        val dragState = rememberDraggableState { delta ->
            val dx = with(density) { delta.toDp() }
            val signed = if (settings.chatOnLeft) dx else -dx
            dragFraction = ((total * dragFraction + signed) / total).coerceIn(0.15f, 0.7f)
        }

        val stream: @Composable (Modifier) -> Unit = { mod ->
            Column(mod.fillMaxHeight()) {
                StreamArea(
                    vm = vm,
                    host = host,
                    settings = settings,
                    inPip = false,
                    isFullscreen = true,
                    chatVisible = chatVisible,
                    onToggleChat = toggleChat,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                if (!cinema && panelHeight > 560.dp) {
                    StreamControlsFor(vm, host, activity)
                }
            }
        }
        val handle: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxHeight()
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
        }
        val chatColumn: @Composable () -> Unit = {
            Column(Modifier.width(chatWidth).fillMaxHeight().background(MaterialTheme.colorScheme.background).imePadding()) {
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
        }

        Row(Modifier.fillMaxSize()) {
            if (chatVisible && settings.chatOnLeft) {
                chatColumn(); handle()
            }
            stream(Modifier.weight(1f))
            if (chatVisible && !settings.chatOnLeft) {
                handle(); chatColumn()
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
