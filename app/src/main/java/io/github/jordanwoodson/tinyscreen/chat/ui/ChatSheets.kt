package io.github.jordanwoodson.tinyscreen.chat.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.chat.ChatApi
import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import io.github.jordanwoodson.tinyscreen.chat.ChatUser
import io.github.jordanwoodson.tinyscreen.chat.Emote
import io.github.jordanwoodson.tinyscreen.chat.Flair
import io.github.jordanwoodson.tinyscreen.chat.LoggedLine
import io.github.jordanwoodson.tinyscreen.chat.UserInfo
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Times
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun EmotePickerSheet(
    emotes: List<Emote>,
    me: ChatUser?,
    scale: Float,
    animate: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Emote?>(null) }
    val sections = remember(emotes, query) {
        val filtered = emotes.filter { query.isBlank() || it.prefix.contains(query, ignoreCase = true) }
        val bySection = filtered.groupBy { if (it.twitch) -1 else it.minimumSubTier }
        bySection.keys.sorted().map { tier ->
            val title = when (tier) {
                -1 -> "Twitch Subscribers"
                0 -> "All Users"
                else -> "Tier $tier Subscribers"
            }
            Triple(tier, title, bySection.getValue(tier).sortedBy { it.prefix.lowercase() })
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Search emotes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        preview?.let { e ->
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                EmoteImage(e, 1.5f, animate)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(e.prefix, fontWeight = FontWeight.Bold)
                    e.creator?.let { Text("by $it", style = MaterialTheme.typography.labelMedium) }
                    if (e.minimumSubTier > 0) Text("Tier ${e.minimumSubTier}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        // A lazy grid: only the emotes on screen are composed, not all ~350 at once
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.75f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            sections.forEach { (_, title, list) ->
                item(key = "h$title", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                items(list, key = { "e" + it.prefix }) { e ->
                    val allowed = me?.canUse(e) ?: (e.minimumSubTier == 0 && !e.twitch)
                    Box(
                        Modifier
                            .height(48.dp)
                            .background(Color(0xFF1A1A1A), RoundedCornerShape(6.dp))
                            .combinedClickable(
                                onClickLabel = if (allowed) "Insert" else "Show details",
                                onClick = { if (allowed) onPick(e.prefix) else preview = e },
                                onLongClickLabel = "Show details",
                                onLongClick = { preview = e },
                            )
                            .padding(4.dp)
                            .alpha(if (allowed) 1f else 0.35f),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Wide emotes shrink to fit the cell
                        val fit = minOf(scale, 44f / e.width.coerceAtLeast(1), 40f / e.height.coerceAtLeast(1))
                        EmoteImage(e, fit, animate = false)
                        if (!allowed) Icon(Icons.Filled.Lock, "Locked", tint = Color.White, modifier = Modifier.size(12.dp).align(Alignment.BottomEnd))
                    }
                }
            }
        }
    }
}

private data class UserSection(val title: String, val features: Set<String>)

private val SECTIONS = listOf(
    UserSection("Admin", setOf("admin")),
    UserSection("Moderator", setOf("moderator")),
    UserSection("Broadcaster", setOf("flair12")),
    UserSection("Trusted User", setOf("flair4")),
    UserSection("Contributor", setOf("flair5", "flair16")),
    UserSection("Subscriber Tier 5", setOf("flair42")),
    UserSection("Subscriber Tier 4", setOf("flair8")),
    UserSection("Subscriber Tier 3", setOf("flair3")),
    UserSection("Subscriber Tier 2", setOf("flair1")),
    UserSection("Subscriber Tier 1", setOf("flair13")),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListSheet(
    users: List<ChatUser>,
    flairs: Map<String, Flair>,
    onUser: (String) -> Unit,
    onWhisper: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val grouped = remember(users, query) {
        val filtered = users.filter { query.isBlank() || it.nick.contains(query, ignoreCase = true) }
        val bySection = filtered.groupBy { u ->
            when {
                u.isBot -> "Bot"
                else -> SECTIONS.firstOrNull { s -> u.features.any { it in s.features } }?.title ?: "User"
            }
        }
        (SECTIONS.map { it.title } + listOf("User", "Bot")).mapNotNull { title ->
            bySection[title]?.sortedBy { it.nick.lowercase() }?.let { title to it }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text("Users (${users.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            placeholder = { Text("Username search ...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
            grouped.forEach { (title, list) ->
                item(key = "h$title") {
                    Text(
                        "${list.size} $title${if (list.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(list, key = { "u" + it.key }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onUser(u.nick) }.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            buildAnnotatedString { withStyle(usernameStyle(u, flairs)) { append(u.nick) } },
                            modifier = Modifier.weight(1f),
                            fontSize = 14.sp,
                        )
                        IconButton(onClick = { onWhisper(u.nick) }) { Icon(Icons.Filled.Mail, "Whisper", tint = Color(0xFF8A8A8A)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UserInfoSheet(
    nick: String,
    chat: ChatRepository,
    settings: AppSettings,
    flairs: Map<String, Flair>,
    onDismiss: () -> Unit,
    onFocus: () -> Unit,
    onMention: () -> Unit,
    onWhisper: () -> Unit,
    onOpenEmbed: (Embed) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var info by remember { mutableStateOf<UserInfo?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<LoggedLine>?>(null) }
    val me = chat.me.value
    LaunchedEffect(nick) {
        val result = runCatching { ChatApi.userInfo(nick) }
        info = result.getOrNull()
        notFound = result.isSuccess && info == null
        logs = runCatching { ChatApi.rustleSearch(nick) }.getOrDefault(emptyList())
    }
    val user = info?.user ?: chat.users.value[nick.lowercase()] ?: ChatUser(nick = nick)
    val ignored = settings.ignoredUsers.any { it.equals(nick, ignoreCase = true) }
    val highlighted = settings.highlightNicks.any { it.equals(nick, ignoreCase = true) }
    val graph = (androidx.compose.ui.platform.LocalContext.current.applicationContext as TinyscreenApp).graph

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            item {
                Text(
                    buildAnnotatedString { withStyle(usernameStyle(user, flairs)) { append(user.nick) } },
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (notFound) Text("User not found.", color = MaterialTheme.colorScheme.error)
                val badges = user.features.mapNotNull { flairs[it] }.sortedBy { it.priority }
                if (badges.isNotEmpty()) {
                    FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        badges.forEach { f ->
                            Row(
                                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                f.imageUrl?.let { AsyncImage(it, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                                Text(f.label, fontSize = 12.sp)
                            }
                        }
                    }
                }
                user.watching?.let { w ->
                    if (w.platform != null && w.id != null) {
                        Text(
                            "Watching ${Embed.platformName(w.platform)}/${w.id}",
                            color = ChatColors.Link,
                            modifier = Modifier.clickable { onOpenEmbed(Embed(w.platform, w.id)) }.padding(vertical = 4.dp),
                        )
                    }
                }
                Times.parse(user.createdDate)?.let {
                    Text("Joined on ${DateFormat.getDateInstance().format(Date(it.toEpochMilli()))}", style = MaterialTheme.typography.bodySmall)
                }
                info?.age?.takeIf { it.isNotBlank() }?.let { Text("Age: $it", style = MaterialTheme.typography.bodySmall) }
                info?.gender?.takeIf { it.isNotBlank() }?.let { Text("Gender: $it", style = MaterialTheme.typography.bodySmall) }
                info?.bio?.takeIf { it.isNotBlank() }?.let { Text(it, modifier = Modifier.padding(vertical = 6.dp)) }

                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = onMention, label = { Text("Mention") })
                    if (me != null && !user.nick.equals(me.nick, true)) AssistChip(onClick = onWhisper, label = { Text("Whisper") })
                    AssistChip(onClick = onFocus, label = { Text("Focus") })
                    AssistChip(onClick = { chat.send("/stalk ${user.nick} 10"); onDismiss() }, label = { Text("Stalk") })
                    AssistChip(onClick = { chat.send("/mentions ${user.nick} 10"); onDismiss() }, label = { Text("Mentions") })
                    AssistChip(
                        onClick = {
                            graph.settings.launchUpdate(graph.scope) { s ->
                                s.copy(highlightNicks = if (highlighted) s.highlightNicks.filterNot { it.equals(nick, true) }.toSet() else s.highlightNicks + nick)
                            }
                        },
                        label = { Text(if (highlighted) "Unhighlight" else "Highlight") },
                    )
                    if (!user.isBot) {
                        AssistChip(
                            onClick = {
                                graph.settings.launchUpdate(graph.scope) { s ->
                                    s.copy(ignoredUsers = if (ignored) s.ignoredUsers.filterNot { it.equals(nick, true) }.toSet() else s.ignoredUsers + nick)
                                }
                                onDismiss()
                            },
                            label = { Text(if (ignored) "Unignore" else "Ignore") },
                        )
                    }
                    AssistChip(
                        onClick = { onOpenUrl("https://rustlesearch.dev/?username=${user.nick}&channel=Destinygg") },
                        label = { Text("RustleSearch") },
                    )
                }
                if (me?.isModerator == true && !user.nick.equals(me.nick, true)) {
                    Text("Moderation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("1m", "10m", "1h", "1d").forEach { t ->
                            AssistChip(onClick = { chat.send("/mute ${user.nick} $t"); onDismiss() }, label = { Text("Mute $t") })
                        }
                        listOf("1d", "7d", "30d", "perm").forEach { t ->
                            AssistChip(
                                onClick = { chat.send("/ipban ${user.nick} $t ${user.nick} banned by ${me.nick}."); onDismiss() },
                                label = { Text("IP Ban $t") },
                            )
                        }
                        user.id?.let { id ->
                            AssistChip(onClick = { onOpenUrl("https://www.destiny.gg/admin/user/$id/edit") }, label = { Text("Admin") })
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Recent messages", style = MaterialTheme.typography.titleSmall)
            }
            val l = logs
            if (l == null) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            } else if (l.isEmpty()) {
                item { Text("No messages found.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                items(l) { line ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(line.timestamp)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(line.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}
