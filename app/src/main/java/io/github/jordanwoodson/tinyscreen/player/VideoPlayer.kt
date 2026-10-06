package io.github.jordanwoodson.tinyscreen.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import io.github.jordanwoodson.tinyscreen.settings.Quality
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Scrim = Color.Black.copy(alpha = 0.45f)
private val LiveRed = Color(0xFFE53935)

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    holder: PlayerHolder,
    title: String?,
    platformLabel: String?,
    isFullscreen: Boolean,
    inPip: Boolean,
    adBreak: Boolean,
    /** Null where the phone can't be turned (split-screen, large screens), which hides the button. */
    onToggleFullscreen: (() -> Unit)?,
    onEnterPip: () -> Unit,
    modifier: Modifier = Modifier,
    chatVisible: Boolean? = null,
    onToggleChat: (() -> Unit)? = null,
    onReload: () -> Unit = {},
) {
    val state = rememberPlayerUiState(holder.player)
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<Job?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var gestureLevel by remember { mutableStateOf<Pair<ImageVector, Float>?>(null) }
    var interactionTick by remember { mutableIntStateOf(0) }

    fun poke() {
        interactionTick++
        controlsVisible = true
    }

    LaunchedEffect(controlsVisible, interactionTick, state.isPlaying, showSettings) {
        if (controlsVisible && state.isPlaying && !showSettings) {
            delay(3_500)
            controlsVisible = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(700)
            seekFeedback = null
        }
    }
    LaunchedEffect(gestureLevel) {
        if (gestureLevel != null) {
            delay(900)
            gestureLevel = null
        }
    }

    val context = LocalContext.current
    // The swipe-to-dim gesture only exists in fullscreen; hand brightness back to the system after
    DisposableEffect(isFullscreen) { onDispose { resetBrightness(context) } }
    Box(
        modifier
            .background(Color.Black)
            .pointerInput(state.canSeek) {
                detectTapGestures(
                    onTap = {
                        if (controlsVisible) controlsVisible = false else poke()
                    },
                    onDoubleTap = { offset ->
                        if (!state.canSeek && !state.isLive) return@detectTapGestures
                        val third = size.width / 3f
                        when {
                            offset.x < third -> {
                                holder.player.seekBack(); seekFeedback = "−10s"
                            }
                            offset.x > third * 2 -> {
                                holder.player.seekForward(); seekFeedback = "+10s"
                            }
                            else -> if (state.isPlaying) holder.player.pause() else holder.resume()
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom > 1.08f) zoomed = true else if (zoom < 0.92f) zoomed = false
                }
            }
            .then(
                if (isFullscreen && !inPip) {
                    Modifier.pointerInput(Unit) {
                        var accumulated = 0f
                        var onLeft = false
                        detectVerticalDragGestures(
                            onDragStart = { start ->
                                accumulated = 0f
                                onLeft = start.x < size.width / 2f
                            },
                            onVerticalDrag = { change, dy ->
                                accumulated -= dy / size.height
                                change.consume()
                                if (onLeft) {
                                    val level = adjustBrightness(context, accumulated)
                                    accumulated = 0f
                                    gestureLevel = Icons.Filled.Brightness6 to level
                                } else {
                                    val level = adjustVolume(context, accumulated)
                                    if (level != null) {
                                        accumulated = 0f
                                        gestureLevel = Icons.AutoMirrored.Filled.VolumeUp to level
                                    }
                                }
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val surfaceKey by holder.surfaceKey.collectAsState()
        // A fresh surface per stream keeps a new aspect ratio from inheriting the old buffer size
        androidx.compose.runtime.key(surfaceKey) {
            ContentFrame(
                player = holder.player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                contentScale = if (zoomed && !inPip) ContentScale.Crop else ContentScale.Fit,
            )
        }

        val audioOnly by holder.audioOnly.collectAsState()
        if (audioOnly) {
            // Muxed streams keep playing their smallest rendition; cover it
            Box(Modifier.fillMaxSize().background(Color.Black))
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Headphones, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(48.dp))
                if (!inPip) Text("Audio only", color = Color.White.copy(alpha = 0.7f))
            }
        }

        val recovery by holder.recovery.collectAsState()
        // Automatic retries show as buffering; the error only once they've stopped or wait for a network
        val retrying = state.error != null && recovery == Recovery.Retrying
        if (adBreak) {
            Surface(
                color = Color.Black.copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.Center).padding(16.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (inPip) "Ad blocked" else "Ad break blocked — stream resumes shortly", color = Color.White)
                }
            }
        } else if ((state.isBuffering && state.error == null) || retrying) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(if (inPip) 28.dp else 44.dp), color = Color.White)
        }

        seekFeedback?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center).background(Scrim, CircleShape).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        gestureLevel?.let { (icon, level) ->
            Surface(color = Scrim, shape = RoundedCornerShape(12.dp), modifier = Modifier.align(Alignment.Center)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("${(level * 100).toInt()}%", color = Color.White)
                }
            }
        }

        state.error?.takeUnless { retrying }?.let { err ->
            if (!inPip) {
                Column(
                    Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(12.dp)).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val (headline, detail) = when (recovery) {
                        Recovery.WaitingForNetwork -> "No connection" to "Resumes when you're back online"
                        Recovery.Offline -> "Stream ended" to "The channel is offline"
                        else -> "Playback error" to err.errorCodeName
                    }
                    Text(headline, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(detail, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = onReload) {
                        Icon(Icons.Filled.Refresh, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Retry")
                    }
                }
            }
        }

        if (!inPip) {
            AnimatedVisibility(
                visible = controlsVisible || !state.hasMedia,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize(),
            ) {
                Controls(
                    state = state,
                    holder = holder,
                    title = title,
                    platformLabel = platformLabel,
                    isFullscreen = isFullscreen,
                    chatVisible = chatVisible,
                    onToggleChat = onToggleChat,
                    onToggleFullscreen = onToggleFullscreen?.let { toggle -> { poke(); toggle() } },
                    onEnterPip = onEnterPip,
                    onSettings = { showSettings = true },
                    onZoom = { zoomed = !zoomed; poke() },
                    zoomed = zoomed,
                    onInteract = ::poke,
                    onReload = onReload,
                )
            }
        }
    }

    if (showSettings) {
        PlayerSettingsSheet(holder = holder, state = state, onDismiss = { showSettings = false })
    }
}

@Composable
private fun Controls(
    state: PlayerUiState,
    holder: PlayerHolder,
    title: String?,
    platformLabel: String?,
    isFullscreen: Boolean,
    chatVisible: Boolean?,
    onToggleChat: (() -> Unit)?,
    onToggleFullscreen: (() -> Unit)?,
    onEnterPip: () -> Unit,
    onSettings: () -> Unit,
    onZoom: () -> Unit,
    zoomed: Boolean,
    onInteract: () -> Unit,
    onReload: () -> Unit,
) {
    val player = holder.player
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 0.55f),
                0.3f to Color.Transparent,
                0.65f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.65f),
            ),
        ),
    ) {
        // Top bar
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopStart).padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                if (!title.isNullOrBlank()) {
                    Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                }
                if (!platformLabel.isNullOrBlank()) {
                    Text(platformLabel, color = Color.White.copy(alpha = 0.7f), maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
            IconButton(onClick = onEnterPip) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
            if (onToggleChat != null) {
                IconButton(onClick = { onToggleChat(); onInteract() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        if (chatVisible == true) "Hide chat" else "Show chat",
                        tint = if (chatVisible == true) MaterialTheme.colorScheme.primary else Color.White,
                    )
                }
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Player settings", tint = Color.White) }
        }

        // Center transport
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            if (state.canSeek) {
                IconButton(onClick = { player.seekBack(); onInteract() }) {
                    Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.35f),
                modifier = Modifier.size(64.dp).clip(CircleShape).clickable {
                    onInteract()
                    when {
                        state.error != null -> onReload()
                        state.isEnded -> holder.resume()
                        player.isPlaying || (player.playWhenReady && state.isBuffering) -> player.pause()
                        else -> {
                            if (state.isLive && !state.atLiveEdge && state.liveOffsetMs > 60_000) holder.goLive() else holder.resume()
                        }
                    }
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val icon = when {
                        state.isEnded -> Icons.Filled.Replay
                        state.playWhenReady -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    }
                    Icon(icon, "Play/pause", tint = Color.White, modifier = Modifier.size(40.dp))
                }
            }
            if (state.canSeek) {
                IconButton(onClick = { player.seekForward(); onInteract() }) {
                    Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
        }

        // Bottom bar
        Column(Modifier.fillMaxWidth().align(Alignment.BottomStart).padding(horizontal = 8.dp)) {
            if (state.canSeek && !state.isLive) {
                SeekBar(state = state, onSeek = { player.seekTo(it); onInteract() })
            } else if (state.isLive && state.canSeek && state.durationMs > 60_000) {
                // DVR window on live streams
                SeekBar(state = state, onSeek = { player.seekTo(it); onInteract() })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isLive) {
                    LiveChip(atEdge = state.atLiveEdge, offsetMs = state.liveOffsetMs, onClick = { holder.goLive(); onInteract() })
                } else if (state.hasMedia) {
                    Text(
                        "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (state.speed != 1f) {
                    Text("  ${state.speed}×", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { player.volume = if (player.volume > 0f) 0f else 1f; onInteract() }) {
                    Icon(
                        if (state.volume == 0f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        "Mute",
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onZoom) {
                    Icon(Icons.Filled.AspectRatio, if (zoomed) "Fit" else "Zoom", tint = if (zoomed) MaterialTheme.colorScheme.primary else Color.White)
                }
                if (onToggleFullscreen != null) {
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveChip(atEdge: Boolean, offsetMs: Long, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .background(if (atEdge) LiveRed else Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (atEdge) Color.White else LiveRed, CircleShape))
        Spacer(Modifier.width(5.dp))
        val label = if (atEdge || offsetMs == C.TIME_UNSET) "LIVE" else "GO LIVE  −${formatDuration(offsetMs)}"
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val progress = if (dragging) dragValue else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Slider(
            value = progress,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                onSeek((dragValue * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
        if (dragging) {
            Text(formatDuration((dragValue * duration).toLong()), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSettingsSheet(holder: PlayerHolder, state: PlayerUiState, onDismiss: () -> Unit) {
    val qualities by holder.qualities.collectAsState()
    val selected by holder.selectedQuality.collectAsState()
    val audioOnly by holder.audioOnly.collectAsState()
    val active by holder.activeQuality.collectAsState()
    val sleepAt by holder.sleepTimerEndsAt.collectAsState()
    val sleepMinutes by holder.sleepTimerMinutes.collectAsState()
    // The video size changes whenever the rendition does
    val playing = remember(state.videoSize) { holder.playingQualityLabel() }
    var page by remember { mutableStateOf("main") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        when (page) {
            "quality" -> LazyColumn {
                val auto = selected == null && active == Quality.AUTO
                // A Source or resolution default plays one rendition; tick that one
                val fixedDefault = selected == null && active != Quality.AUTO && active != Quality.AUDIO
                item {
                    SheetOption("Auto" + if (auto && playing != null) "  ·  $playing" else "", selected = auto) {
                        holder.selectQuality(null); onDismiss()
                    }
                }
                items(qualities) { q ->
                    SheetOption(
                        q.label + if (q.bitrate > 0) "  ·  ${"%.1f".format(q.bitrate / 1_000_000f)} Mbps" else "",
                        selected = selected?.label == q.label || (fixedDefault && q.label == playing),
                    ) { holder.selectQuality(q); onDismiss() }
                }
            }
            "speed" -> LazyColumn {
                items(listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)) { s ->
                    SheetOption("${s}×", selected = state.speed == s) { holder.player.setPlaybackSpeed(s); onDismiss() }
                }
            }
            "sleep" -> LazyColumn {
                items(listOf(0, 15, 30, 45, 60, 90, 120)) { m ->
                    SheetOption(if (m == 0) "Off" else "$m minutes", selected = m == sleepMinutes) {
                        holder.setSleepTimer(m); onDismiss()
                    }
                }
            }
            else -> Column(Modifier.padding(bottom = 24.dp)) {
                val quality = selected?.label ?: when (active) {
                    Quality.AUTO -> "Auto" + (playing?.let { " · $it" } ?: "")
                    Quality.AUDIO -> Quality.label(active)
                    else -> "${playing ?: Quality.label(active)} (default)"
                }
                SheetRow(Icons.Filled.HighQuality, "Quality", quality) { page = "quality" }
                if (!state.isLive || state.canSeek || state.speed != 1f) {
                    SheetRow(Icons.Filled.Speed, "Playback speed", if (state.speed == 1f) "Normal" else "${state.speed}×") { page = "speed" }
                }
                ListItem(
                    leadingContent = { Icon(Icons.Filled.Headphones, null) },
                    headlineContent = { Text("Audio only") },
                    supportingContent = { Text("Hides the video and drops to the smallest stream to save data") },
                    trailingContent = { Switch(checked = audioOnly, onCheckedChange = { holder.setAudioOnly(it) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                SheetRow(
                    Icons.Filled.Timer,
                    "Sleep timer",
                    sleepAt?.let { "Stops in ${((it - System.currentTimeMillis()) / 60_000).coerceAtLeast(0) + 1} min" } ?: "Off",
                ) { page = "sleep" }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                val v = state.videoSize
                Text(
                    buildString {
                        if (v.width > 0) append("${v.width}×${v.height}")
                        val fmt = holder.player.videoFormat
                        if (fmt != null) {
                            if (fmt.frameRate > 0) append(" @ ${fmt.frameRate.toInt()}fps")
                            fmt.codecs?.let { append("  ·  $it") }
                            if (fmt.bitrate > 0) append("  ·  ${"%.1f".format(fmt.bitrate / 1_000_000f)} Mbps")
                        }
                        if (state.isLive && state.liveOffsetMs != C.TIME_UNSET) append("  ·  ${"%.1f".format(state.liveOffsetMs / 1000f)}s behind live")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        if (page != "main") {
            TextButton(onClick = { page = "main" }, modifier = Modifier.padding(start = 8.dp, bottom = 16.dp)) { Text("Back") }
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        trailingContent = { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SheetOption(label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = {
            Box(Modifier.size(24.dp)) { if (selected) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary) }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun adjustBrightness(context: Context, delta: Float): Float {
    val activity = context as? Activity ?: return 0f
    val window = activity.window
    val attrs = window.attributes
    val current = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
    val next = (current + delta).coerceIn(0.01f, 1f)
    attrs.screenBrightness = next
    window.attributes = attrs
    return next
}

private fun resetBrightness(context: Context) {
    val window = (context as? Activity)?.window ?: return
    val attrs = window.attributes
    if (attrs.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) return
    attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    window.attributes = attrs
}

private fun adjustVolume(context: Context, delta: Float): Float? {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val steps = (delta * max * 1.5f).toInt()
    if (steps == 0) return null
    val next = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, max)
    audio.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
    return next.toFloat() / max
}
