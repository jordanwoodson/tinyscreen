package io.github.jordanwoodson.tinyscreen.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.chat.ChatLine
import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import io.github.jordanwoodson.tinyscreen.chat.ConnectionStatus
import io.github.jordanwoodson.tinyscreen.chat.PaidEvent
import io.github.jordanwoodson.tinyscreen.chat.PollState
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NativeChat(
    settings: AppSettings,
    onOpenEmbed: (Embed) -> Unit,
    onOpenUrl: (String) -> Unit,
    onLogin: () -> Unit,
    onWhispers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = (LocalContext.current.applicationContext as TinyscreenApp).graph
    val chat = graph.chat
    val rows by chat.rows.collectAsState()
    val me by chat.me.collectAsState()
    val emotes by chat.assets.emotes.collectAsState()
    val flairs by chat.assets.flairs.collectAsState()
    val spotlights by chat.spotlights.collectAsState()
    val pinned by chat.pinned.collectAsState()
    val dismissed by chat.dismissedPins.collectAsState()
    val poll by chat.poll.collectAsState()
    val events by chat.events.collectAsState()
    val unread by chat.unreadWhispers.collectAsState()
    val status by chat.status.collectAsState()
    val scope = rememberCoroutineScope()

    var input by remember { mutableStateOf(TextFieldValue("")) }
    var focused by rememberSaveable { mutableStateOf<String?>(null) }
    val watchingFocus by chat.watchingOnly.collectAsState()
    var userSheet by rememberSaveable { mutableStateOf<String?>(null) }
    var showUsers by rememberSaveable { mutableStateOf(false) }
    var showEmotes by rememberSaveable { mutableStateOf(false) }
    var expandedEvent by remember { mutableStateOf<PaidEvent?>(null) }

    LaunchedEffect(Unit) { chat.start() }
    LaunchedEffect(Unit) {
        chat.inputRequests.collect { text -> input = TextFieldValue(text, TextRange(text.length)) }
    }
    LaunchedEffect(Unit) { chat.embedRequests.collect { onOpenEmbed(it) } }
    LaunchedEffect(Unit) { chat.loginRequests.collect { onLogin() } }

    val scale = settings.chatFontScale
    val fontSize = (13.5f * scale).sp
    val inlineContent = rememberInlineContent(emotes.byPrefix.values, flairs.values, scale, settings.animateEmotes)
    val ctx = LineContext(
        me = me,
        flairs = flairs,
        fontSize = fontSize,
        emoteScale = scale,
        showTime = settings.showTimestamps,
        hideFlairs = settings.hideFlairs,
        animate = settings.animateEmotes,
        greentext = settings.greentext,
        formatEmbeds = settings.formatEmbedLinks,
        tags = remember(settings.taggedUsers) { tagColors(settings.taggedUsers) },
        spotlights = spotlights,
        focused = focused,
    )
    val actions = LineActions(
        onUser = { userSheet = it },
        onEmbed = onOpenEmbed,
        onUrl = onOpenUrl,
        onReveal = chat::reveal,
        onEvent = { expandedEvent = it },
    )

    val listState = rememberLazyListState()
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(listState) {
        snapshotFlow { atBottom to listState.isScrollInProgress }.collect { (bottom, scrolling) ->
            if (scrolling) follow = bottom
            if (bottom) follow = true
        }
    }
    LaunchedEffect(rows.firstOrNull()?.key, rows.size) {
        if (follow && rows.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(modifier.background(ChatColors.Background)) {
        if (events.isNotEmpty()) EventBar(events, onClick = { expandedEvent = if (expandedEvent?.uuid == it.uuid) null else it })
        poll?.let { PollPanel(it, onVote = chat::vote, onClose = chat::dismissPoll, fontSize = ctx.fontSize) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                items(rows, key = { it.key }, contentType = { it.line::class }) { row ->
                    ChatLineView(row, ctx, actions, inlineContent)
                }
            }
            val pin = pinned
            if (pin != null && pin.uuid !in dismissed && expandedEvent == null) {
                PinnedCard(pin.nick, pin.text, me?.isModerator == true, ctx, inlineContent, actions,
                    onDismiss = { chat.dismissPin(pin.uuid) },
                    onUnpin = { chat.send("/unpin") },
                    modifier = Modifier.align(Alignment.TopCenter))
            } else if (pin != null && pin.uuid in dismissed) {
                IconButton(onClick = { chat.showPin(pin.uuid) }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.PushPin, "Show pinned message", tint = Color(0xFF838383))
                }
            }
            expandedEvent?.let { ev ->
                Box(Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) {
                    EventCard(ev, ctx, onClick = { expandedEvent = null })
                    if (me?.isModerator == true) {
                        Text(
                            "Remove Event",
                            color = ChatColors.Error,
                            fontSize = 12.sp,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clickable { chat.removeEvent(ev.uuid); expandedEvent = null },
                        )
                    }
                }
            }
            focused?.let { nick ->
                Row(
                    Modifier.align(Alignment.TopStart).padding(6.dp).background(Color(0xFF222222), RoundedCornerShape(12.dp))
                        .clickable { focused = null }.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Focusing $nick", color = ChatColors.Bright, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Close, "Stop focusing", tint = ChatColors.Bright, modifier = Modifier.size(14.dp))
                }
            }
            if (!follow && !atBottom) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xFF222222)).clickable {
                        follow = true
                        scope.launch { listState.scrollToItem(0) }
                    }.padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("More messages below", color = Color(0xFF838383), fontSize = 13.sp)
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color(0xFF838383))
                }
            }
        }

        if (status != ConnectionStatus.Connected) {
            Text(
                if (status == ConnectionStatus.Connecting) "Connecting…" else "Disconnected — tap to reconnect",
                color = Color(0xFF838383),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF151515)).clickable { chat.reconnect() }.padding(6.dp),
            )
        }

        Autocomplete(
            input = input,
            chat = chat,
            onPick = { input = it },
        )
        ChatInput(
            value = input,
            onValueChange = { input = it },
            chat = chat,
            settings = settings,
            onSend = {
                chat.send(input.text)
                input = TextFieldValue("")
                follow = true
            },
            onLogin = onLogin,
        )
        Row(
            Modifier.fillMaxWidth().background(ChatColors.Background).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { showEmotes = true }) { Icon(Icons.Filled.EmojiEmotions, "Emotes", tint = Color(0xFF9A9A9A)) }
            IconButton(onClick = onWhispers) {
                BadgedBox(badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } }) {
                    Icon(Icons.Filled.Mail, "Whispers", tint = Color(0xFF9A9A9A))
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { chat.setWatchingOnly(!watchingFocus) }) {
                Icon(
                    if (watchingFocus) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    "Only show people watching the same stream",
                    tint = if (watchingFocus) MaterialTheme.colorScheme.primary else Color(0xFF9A9A9A),
                )
            }
            IconButton(onClick = { showUsers = true }) { Icon(Icons.Filled.People, "Users", tint = Color(0xFF9A9A9A)) }
        }
    }

    if (showEmotes) {
        EmotePickerSheet(
            emotes = emotes.byPrefix.values.toList(),
            me = me,
            scale = 1f,
            animate = settings.animateEmotes,
            onPick = { prefix ->
                val t = input.text
                val text = (if (t.isEmpty() || t.endsWith(" ")) t else "$t ") + prefix + " "
                input = TextFieldValue(text, TextRange(text.length))
            },
            onDismiss = { showEmotes = false },
        )
    }
    if (showUsers) {
        val users by chat.users.collectAsState()
        UserListSheet(
            users = users.values.toList(),
            flairs = flairs,
            onUser = { userSheet = it; showUsers = false },
            onWhisper = { nick ->
                val text = "/whisper $nick ${input.text}"
                input = TextFieldValue(text, TextRange(text.length))
                showUsers = false
            },
            onDismiss = { showUsers = false },
        )
    }
    userSheet?.let { nick ->
        UserInfoSheet(
            nick = nick,
            chat = chat,
            settings = settings,
            flairs = flairs,
            onDismiss = { userSheet = null },
            onFocus = { focused = nick.lowercase(); userSheet = null },
            onMention = {
                val text = input.text.trimEnd().let { if (it.isEmpty()) "" else "$it " } + "$nick "
                input = TextFieldValue(text, TextRange(text.length))
                userSheet = null
            },
            onWhisper = { chat.openWhisper(nick); userSheet = null; onWhispers() },
            onOpenEmbed = { onOpenEmbed(it); userSheet = null },
            onOpenUrl = onOpenUrl,
        )
    }
}

@Composable
private fun ChatInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    chat: ChatRepository,
    settings: AppSettings,
    onSend: () -> Unit,
    onLogin: () -> Unit,
) {
    val me by chat.me.collectAsState()
    val muteEnds by chat.muteEndsAt.collectAsState()
    val subOnly by chat.subOnly.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(muteEnds) {
        while (muteEnds != null && now < muteEnds!!) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val muted = muteEnds?.let { it > now } == true
    val placeholder = when {
        me == null -> "Write something ..."
        muted -> "Sorry, ${me!!.nick}, you are muted. You can chat again ${ChatRepository.inDuration(muteEnds!! - now)}."
        else -> "Write something ${me!!.nick} ..."
    }
    val subLocked = subOnly > 0 && me?.isPrivileged != true && (me?.subTier ?: 0) < subOnly
    Row(
        Modifier.fillMaxWidth().background(ChatColors.Background).padding(start = 6.dp, end = 6.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp) },
            leadingIcon = if (subLocked) {
                { Icon(Icons.Filled.Lock, "Subscriber-only mode", tint = Color(0xFFFFC531)) }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (me == null) onLogin() else onSend() }),
            maxLines = 5,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = ChatColors.Bright, fontSize = (14 * settings.chatFontScale).sp),
            shape = RoundedCornerShape(10.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF151515),
                unfocusedContainerColor = Color(0xFF151515),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 140.dp),
        )
        IconButton(onClick = { if (me == null) onLogin() else onSend() }, enabled = value.text.isNotBlank() || me == null) {
            Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (value.text.isNotBlank()) MaterialTheme.colorScheme.primary else Color(0xFF555555))
        }
    }
}

/** Tab-completion strip: emotes first, then recently-seen users, then commands. */
@Composable
private fun Autocomplete(input: TextFieldValue, chat: ChatRepository, onPick: (TextFieldValue) -> Unit) {
    val emotes by chat.assets.emotes.collectAsState()
    val me by chat.me.collectAsState()
    val text = input.text
    val caret = input.selection.end.coerceIn(0, text.length)
    val start = text.lastIndexOfAny(charArrayOf(' ', '\n'), (caret - 1).coerceAtLeast(0)).let { if (caret == 0) 0 else it + 1 }
    val word = if (caret > start) text.substring(start, caret) else ""
    // The user list is read when the word changes rather than watched: it changes all the time
    val suggestions = remember(word, emotes, me) {
        if (word.isEmpty()) return@remember emptyList()
        val users = chat.users.value
        val usersOnly = word.startsWith("@") || word.startsWith(">")
        val needle = word.trimStart('@', '>').lowercase()
        val out = mutableListOf<String>()
        if (word.startsWith("/") && start == 0) {
            val cmds = ChatRepository.USER_COMMANDS + if (me?.isModerator == true) ChatRepository.MOD_COMMANDS else emptyList()
            cmds.filter { it.startsWith(needle.removePrefix("/")) }.take(20).mapTo(out) { "/$it" }
            return@remember out
        }
        if (!usersOnly && needle.isNotEmpty()) {
            emotes.byPrefix.values.filter { it.prefix.lowercase().startsWith(needle) && (me?.canUse(it) ?: (it.minimumSubTier == 0)) }
                .sortedBy { it.prefix.length }.take(12).mapTo(out) { it.prefix }
        }
        if (needle.length >= 1) {
            users.values.filter { it.key.startsWith(needle) }
                .sortedByDescending { chat.lastSeen(it.key) }
                .take(20 - out.size.coerceAtMost(20)).mapTo(out) { (if (word.startsWith("@")) "@" else "") + it.nick }
        }
        out.distinct().take(20)
    }
    if (suggestions.isEmpty() || (suggestions.size == 1 && suggestions[0] == word)) return
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF111111)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        suggestions.forEach { s ->
            val emote = emotes.byPrefix[s]
            Row(
                Modifier.background(Color(0xFF1E1E1E), RoundedCornerShape(8.dp)).clickable {
                    val replaced = text.substring(0, start) + s + " " + text.substring(caret)
                    val pos = start + s.length + 1
                    onPick(TextFieldValue(replaced, TextRange(pos)))
                }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (emote != null) {
                    EmoteImage(emote, 0.7f, animate = false)
                    Spacer(Modifier.width(4.dp))
                }
                Text(s, color = ChatColors.Bright, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun EventBar(events: List<PaidEvent>, onClick: (PaidEvent) -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF111111)).horizontalScroll(rememberScrollState()).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        events.forEach { ev ->
            val color = eventColor(ev)
            val total = (ev.expiresAt - ev.timestamp).coerceAtLeast(1)
            val left = ((ev.expiresAt - now).toFloat() / total).coerceIn(0f, 1f)
            Box(
                Modifier
                    .border(1.dp, color, RoundedCornerShape(12.dp))
                    .background(
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            0f to Color(0xFF282828), left to Color(0xFF282828), left to Color(0xFF151515), 1f to Color(0xFF151515),
                        ),
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onClick(ev) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(ev.user?.nick ?: ev.title.substringBefore(' '), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun PinnedCard(
    nick: String,
    text: String,
    canUnpin: Boolean,
    ctx: LineContext,
    inline: Map<String, androidx.compose.foundation.text.InlineTextContent>,
    actions: LineActions,
    onDismiss: () -> Unit,
    onUnpin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = (LocalContext.current.applicationContext as TinyscreenApp).graph
    val segments = remember(text) { graph.chat.formatter.format(text, null).segments }
    Column(
        modifier
            .padding(6.dp)
            .fillMaxWidth()
            .background(Color(0xFF151515), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF505050), RoundedCornerShape(10.dp))
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PushPin, null, tint = ChatColors.Bright, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Pinned by $nick", color = Color(0xFF9A9A9A), fontSize = 12.sp, modifier = Modifier.weight(1f))
            if (canUnpin) Text("Unpin", color = ChatColors.Error, fontSize = 12.sp, modifier = Modifier.clickable(onClick = onUnpin).padding(4.dp))
            Icon(Icons.Filled.Close, "Hide", tint = Color(0xFF9A9A9A), modifier = Modifier.size(18.dp).clickable(onClick = onDismiss))
        }
        val line = ChatLine.User(0, 0, io.github.jordanwoodson.tinyscreen.chat.ChatUser(nick = nick), text, segments,
            false, false, false, null, emptySet(), "")
        Box(Modifier.heightIn(max = 160.dp)) {
            ChatLineView(line, ctx.copy(showTime = false, focused = null), actions, inline, continued = true)
        }
    }
}

@Composable
private fun PollPanel(poll: PollState, onVote: (Int) -> Unit, onClose: () -> Unit, fontSize: androidx.compose.ui.unit.TextUnit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(poll) {
        while (!poll.ended) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = (now + poll.serverOffsetMs - poll.startMs).coerceAtLeast(0)
    val remaining = (1f - elapsed.toFloat() / poll.durationMs).coerceIn(0f, 1f)
    val winner = if (poll.ended) poll.totals.indices.maxByOrNull { poll.totals[it] } else null
    Column(Modifier.fillMaxWidth().background(Color(0xFF151515)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(poll.question, color = ChatColors.Bright, fontWeight = FontWeight.Bold, fontSize = fontSize, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Close, "Hide poll", tint = Color(0xFF9A9A9A), modifier = Modifier.size(18.dp).clickable(onClick = onClose))
        }
        Text(
            if (poll.ended) "Poll ended! ${poll.totalVotes} votes cast." else
                "${if (poll.weighted) "Sub-weighted poll" else "Poll"} started by ${poll.startedBy} for ${poll.durationMs / 1000} seconds.  ·  ${poll.totalVotes} votes",
            color = Color(0xFF9A9A9A),
            fontSize = 12.sp,
        )
        if (!poll.ended) {
            Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(3.dp).background(Color(0xFF333333))) {
                Box(Modifier.fillMaxWidth(remaining).height(3.dp).background(MaterialTheme.colorScheme.primary))
            }
        }
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            poll.options.forEachIndexed { i, option ->
                val votes = poll.totals.getOrElse(i) { 0 }
                val pct = if (poll.totalVotes > 0) votes.toFloat() / poll.totalVotes else 0f
                val mine = poll.myVote == i + 1
                Box(
                    Modifier
                        .padding(vertical = 3.dp)
                        .fillMaxWidth()
                        .background(if (mine) Color(0xFF333333) else Color(0xFF1E1E1E), RoundedCornerShape(6.dp))
                        .clickable(enabled = poll.canVote && !poll.ended) { onVote(i + 1) },
                ) {
                    Box(
                        Modifier.fillMaxWidth(pct).height(34.dp).background(
                            if (winner == i) Color(0xFF4C7C42) else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            RoundedCornerShape(6.dp),
                        ),
                    )
                    Row(Modifier.padding(horizontal = 8.dp).height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).background(Color(0xFF333333), CircleShape), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = ChatColors.Bright, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(option, color = ChatColors.Bright, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${(pct * 100).toInt()}% ($votes votes)", color = Color(0xFF9A9A9A), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
