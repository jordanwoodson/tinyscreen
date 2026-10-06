package io.github.jordanwoodson.tinyscreen.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.chat.WhisperState
import io.github.jordanwoodson.tinyscreen.ui.fromNow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhispersScreen(onBack: () -> Unit) {
    val chat = (LocalContext.current.applicationContext as TinyscreenApp).graph.chat
    val open by chat.openConversation.collectAsState()
    val conversations by chat.conversations.collectAsState()
    val me by chat.me.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var newNick by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { chat.refreshWhispers() }
    LaunchedEffect(query) {
        delay(300)
        chat.searchConversations(query)
    }

    val back = { if (open != null) chat.openWhisper(null) else onBack() }
    androidx.activity.compose.BackHandler(enabled = open != null) { chat.openWhisper(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(open?.let { "Messages with $it" } ?: "Whispers") },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        if (me == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Log in to read and send whispers.")
            }
            return@Scaffold
        }
        val nick = open
        if (nick == null) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search conversations") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newNick,
                        onValueChange = { newNick = it.trim() },
                        placeholder = { Text("New message to…") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { if (newNick.isNotBlank()) chat.openWhisper(newNick) }) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Open")
                    }
                }
                if (conversations.isEmpty()) {
                    Text("No conversations yet", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.username }) { c ->
                        ListItem(
                            headlineContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(c.username, fontWeight = if (c.unread > 0) FontWeight.Bold else FontWeight.Normal)
                                    if (c.unread > 0) {
                                        Spacer(Modifier.size(6.dp))
                                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                                        Text("  ${c.unread} new", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            },
                            supportingContent = {
                                Text(
                                    (if (c.lastMessageFromMe) "You: " else "") + c.lastMessage,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = { Text(fromNow(Instant.ofEpochMilli(c.timestamp)), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.clickable { chat.openWhisper(c.username) },
                        )
                    }
                }
            }
        } else {
            val messages by chat.conversationMessages.collectAsState()
            val notice by chat.whisperNotice.collectAsState()
            var draft by remember(nick) { mutableStateOf("") }
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex) }
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    item {
                        Text(
                            "Messages between you and $nick",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(messages) { m ->
                        val mine = m.from.equals(me?.nick, ignoreCase = true)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                            Column(
                                Modifier
                                    .padding(vertical = 3.dp)
                                    .widthIn(max = 300.dp)
                                    .background(
                                        if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                                        RoundedCornerShape(14.dp),
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(m.text, modifier = Modifier.alpha(if (m.state == WhisperState.Sending) 0.6f else 1f))
                                Text(
                                    when (m.state) {
                                        WhisperState.Sending -> "Sending…"
                                        WhisperState.Failed -> "Not sent: ${m.error ?: "unknown error"}"
                                        WhisperState.Sent -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(m.timestamp))
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (m.state == WhisperState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                notice?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Whisper $nick") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        val text = draft
                        // Cleared only once it's on its way
                        scope.launch { if (chat.submitWhisper(nick, text) && draft == text) draft = "" }
                    }) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    }
}
