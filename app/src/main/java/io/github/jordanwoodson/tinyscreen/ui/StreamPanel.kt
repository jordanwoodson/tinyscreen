package io.github.jordanwoodson.tinyscreen.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.jordanwoodson.tinyscreen.R
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LatestVod
import io.github.jordanwoodson.tinyscreen.stream.Times
import io.github.jordanwoodson.tinyscreen.stream.TopEmbed
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant

enum class BadgeTone(val bg: Color, val fg: Color, val border: Color) {
    Danger(Color(0xFF3B1219), Color(0xFFFF9592), Color(0xFF72232D)),
    Neutral(Color(0xFF212225), Color(0xFFAFB3BA), Color(0xFF363A3F)),
    Primary(Color(0xFF0D2847), Color(0xFF70B8FF), Color(0xFF104D87)),
}

@DrawableRes
fun platformIcon(platform: String): Int? = when (Embed.basePlatform(platform)) {
    "kick" -> R.drawable.ic_platform_kick
    "twitch" -> R.drawable.ic_platform_twitch
    "youtube" -> R.drawable.ic_platform_youtube
    "rumble" -> R.drawable.ic_platform_rumble
    "facebook" -> R.drawable.ic_platform_facebook
    "vimeo" -> R.drawable.ic_platform_vimeo
    else -> null
}

@Composable
fun Badge(text: String, tone: BadgeTone, icon: Int? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone.bg)
            .border(BorderStroke(1.dp, tone.border), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(painterResource(icon), null, tint = tone.fg, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, color = tone.fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun UptimeBadge(start: Instant) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(start) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val secs = Duration.between(start, Instant.ofEpochMilli(now)).seconds.coerceAtLeast(0)
    Badge("%02d:%02d:%02d".format(secs / 3600, (secs % 3600) / 60, secs % 60), BadgeTone.Neutral)
}

private val viewersFormat: NumberFormat = NumberFormat.getIntegerInstance()

fun formatViewers(n: Int) = "${viewersFormat.format(n)} Viewers"

/** Badges, title and buttons shown under the player (#stream-controls). */
@Composable
fun StreamControls(
    content: Content,
    resolvedTitle: String?,
    usingWebPlayer: Boolean,
    canTogglePlayer: Boolean,
    onChangePlatform: () -> Unit,
    onCloseEmbed: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onShare: (String) -> Unit,
    onTogglePlayer: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (content) {
                is Content.Embedded -> {
                    val meta = content.meta?.metadata
                    Badge("Embed", BadgeTone.Danger)
                    if (meta?.live == true && meta.viewers != null) Badge(formatViewers(meta.viewers), BadgeTone.Neutral)
                    if (meta?.live == true) Times.parse(meta.createdDate)?.let { UptimeBadge(it) }
                    Badge(content.embed.platformName, BadgeTone.Primary, platformIcon(content.embed.platform))
                    Badge(meta?.displayName ?: content.embed.displayId, BadgeTone.Neutral)
                }
                is Content.Host -> {
                    val h = content.hosting
                    Badge("Host", BadgeTone.Danger)
                    h.viewers?.let { Badge(formatViewers(it), BadgeTone.Neutral) }
                    Times.parse(h.streamStartedAt)?.let { UptimeBadge(it) }
                    Badge(Embed.platformName(h.platform ?: ""), BadgeTone.Primary, platformIcon(h.platform ?: ""))
                    Badge(h.displayName ?: h.id ?: "", BadgeTone.Neutral)
                }
                is Content.Live -> {
                    Badge(if (content.premiere) "Premiere" else "Live", BadgeTone.Danger)
                    val viewers = content.allLive.mapNotNull { it.viewers }
                    if (viewers.isNotEmpty()) Badge(formatViewers(viewers.sum()), BadgeTone.Neutral)
                    content.allLive.mapNotNull { Times.parse(it.startedAt) }.minOrNull()?.let { UptimeBadge(it) }
                    content.allLive.forEach { s ->
                        val p = s.platform ?: return@forEach
                        Badge(
                            Embed.platformName(p),
                            if (p == content.entry.platform) BadgeTone.Primary else BadgeTone.Neutral,
                            platformIcon(p),
                        )
                    }
                    Badge("destiny", BadgeTone.Neutral)
                }
                Content.Offline -> Unit
            }
        }
        val title = when (content) {
            is Content.Embedded -> content.meta?.metadata?.title ?: resolvedTitle
                ?: "Embedding ${content.meta?.metadata?.displayName ?: content.embed.displayId}"
            is Content.Host -> content.hosting.title ?: "Hosting ${content.hosting.displayName}"
            is Content.Live -> content.entry.statusText ?: "Destiny is live"
            Content.Offline -> "Destiny is offline"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(top = 6.dp),
            )
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val embed = content.embed
                    if (embed != null) {
                        if (canTogglePlayer) {
                            DropdownMenuItem(
                                text = { Text(if (usingWebPlayer) "Use native player" else "Use web player") },
                                leadingIcon = { Icon(if (usingWebPlayer) Icons.Filled.SmartDisplay else Icons.Filled.Language, null) },
                                onClick = { menu = false; onTogglePlayer() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Reload stream") },
                            leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                            onClick = { menu = false; onReload() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; onShare(embed.bigscreenUrl) },
                        )
                        platformPageUrl(embed)?.let { url ->
                            DropdownMenuItem(
                                text = { Text("Open on ${embed.platformName}") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                                onClick = { menu = false; onOpenUrl(url) },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text("Open destiny.gg/bigscreen") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        onClick = { menu = false; onOpenUrl(embed?.bigscreenUrl ?: "https://www.destiny.gg/bigscreen") },
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SmallButton("Subscribe", Icons.Filled.Star) { onOpenUrl("https://www.destiny.gg/subscribe") }
            SmallButton("Donate", Icons.Filled.AttachMoney) { onOpenUrl("https://www.destiny.gg/donate") }
            if (content is Content.Embedded) {
                SmallButton("Close Embed", Icons.Filled.Close, onClick = onCloseEmbed)
            }
            if (content is Content.Live && content.allLive.size >= 2) {
                SmallButton("Change Platform", Icons.Filled.SyncAlt, onClick = onChangePlatform)
            }
        }
    }
}

fun platformPageUrl(embed: Embed): String? = when (embed.platform) {
    "kick" -> "https://kick.com/${embed.id}"
    "kick-vod" -> embed.id.split('/').let { if (it.size == 2) "https://kick.com/${it[0]}/videos/${it[1]}" else null }
    "twitch" -> "https://www.twitch.tv/${embed.id}"
    "twitch-vod" -> "https://www.twitch.tv/videos/${embed.id}"
    "twitch-clip" -> "https://clips.twitch.tv/${embed.id}"
    "youtube" -> "https://www.youtube.com/watch?v=${embed.id}"
    "rumble" -> "https://rumble.com/embed/${embed.id}"
    "vimeo" -> "https://vimeo.com/${embed.id}"
    "facebook" -> "https://www.facebook.com/${embed.id}"
    else -> null
}

@Composable
private fun SmallButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
}

/** The "top embeds" strip above chat. */
@Composable
fun TopEmbedsRow(embeds: List<TopEmbed>, current: Embed?, onSelect: (Embed) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Color(0xFF111113))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (embeds.isEmpty()) {
            Text("No Embeds", color = Color(0xFF43484E), fontSize = 13.sp, modifier = Modifier.padding(6.dp))
        }
        embeds.forEach { e ->
            val selected = current?.matches(e.platform, e.mediaItem?.identifier?.mediaId ?: e.id) == true
            val tint = if (selected) Color(0xFFEDEEF0) else Color(0xFF8B9099)
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selected) Color(0xFF26282C) else Color.Transparent)
                    .clickable { onSelect(e.embed) }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                platformIcon(e.platform)?.let { Icon(painterResource(it), null, tint = tint, modifier = Modifier.size(14.dp)) }
                Spacer(Modifier.width(5.dp))
                Text(
                    e.displayName,
                    color = tint,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("${e.count}", color = tint.copy(alpha = 0.6f), fontSize = 11.sp)
            }
        }
    }
}

/** Sleeping Destiny with the latest VOD card, as on the site when offline. */
@Composable
fun OfflinePanel(vod: LatestVod?, onOpenVod: (String) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(Color(0xFF18428D))) {
        val desat = remember { ColorMatrix().apply { setToSaturation(0.3f) } }
        // Bundled: the site's copy has a hashed file name that changes with each of their deploys
        Image(
            painter = painterResource(R.drawable.sleeping_destiny),
            contentDescription = "Destiny is offline",
            colorFilter = ColorFilter.colorMatrix(desat),
            modifier = Modifier.align(Alignment.Center).width(minOf(360.dp, maxWidth / 2)).aspectRatio(1f),
            contentScale = ContentScale.Fit,
        )
        if (vod?.embedUrl != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
                    .widthIn(max = minOf(300.dp, maxWidth * 0.6f))
                    .clickable { onOpenVod(vod.embedUrl) },
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("LATEST VOD", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(fromNow(Times.parse(vod.streamEndTime)), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        AsyncImage(
                            model = vod.mediumThumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                platformIcon(vod.platform ?: "")?.let {
                                    Icon(painterResource(it), null, tint = Color.White, modifier = Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                }
                                Text("destiny", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp)
                            }
                            Text(vod.title ?: "", color = Color.White, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BlockedPanel(reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color(0xFF500F1C)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Block, null, tint = Color.White, modifier = Modifier.size(56.dp))
        Text("THIS CONTENT IS BLOCKED", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (reason.isNotBlank()) {
            Text(reason, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

fun fromNow(instant: Instant?): String {
    instant ?: return ""
    val secs = Duration.between(instant, Instant.now()).seconds
    return when {
        secs < 45 -> "a few seconds ago"
        secs < 90 -> "a minute ago"
        secs < 45 * 60 -> "${(secs + 30) / 60} minutes ago"
        secs < 90 * 60 -> "an hour ago"
        secs < 22 * 3600 -> "${(secs + 1800) / 3600} hours ago"
        secs < 36 * 3600 -> "a day ago"
        secs < 26 * 86400 -> "${(secs + 43200) / 86400} days ago"
        secs < 46 * 86400 -> "a month ago"
        secs < 320 * 86400 -> "${(secs / 86400 + 15) / 30} months ago"
        secs < 548 * 86400 -> "a year ago"
        else -> "${secs / 86400 / 365} years ago"
    }
}

@Composable
fun FillBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
