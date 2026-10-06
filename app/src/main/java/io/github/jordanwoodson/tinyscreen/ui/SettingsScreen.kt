package io.github.jordanwoodson.tinyscreen.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.jordanwoodson.tinyscreen.BuildConfig
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var pendingNotify by remember { mutableStateOf<((AppSettings) -> AppSettings)?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> pendingNotify?.let { if (granted) onChange(it) }; pendingNotify = null }
    val enableNotification: ((AppSettings) -> AppSettings) -> Unit = { change ->
        pendingNotify = change
        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Header("Playback") }
            item {
                ChoiceRow(
                    "Preferred platform",
                    "Which of Destiny's simultaneous streams to open",
                    PlatformPreference.entries.map { it.label },
                    settings.preferredPlatform.label,
                ) { label -> onChange { s -> s.copy(preferredPlatform = PlatformPreference.entries.first { it.label == label }) } }
            }
            item {
                val options = listOf("auto" to "Auto", "1080" to "1080p", "720" to "720p", "480" to "480p", "360" to "360p")
                ChoiceRow(
                    "Default quality cap",
                    "Highest resolution chosen automatically",
                    options.map { it.second },
                    options.firstOrNull { it.first == settings.preferredQuality }?.second ?: "Auto",
                ) { label -> onChange { s -> s.copy(preferredQuality = options.first { it.second == label }.first) } }
            }
            item { Toggle("Native player", "Play Kick, Twitch, YouTube and Rumble in the built-in player instead of web embeds", settings.nativePlayer) { v -> onChange { it.copy(nativePlayer = v) } } }
            item { Toggle("Low latency", "Stay close to the live edge", settings.lowLatency) { v -> onChange { it.copy(lowLatency = v) } } }
            item { Toggle("Background playback", "Keep audio playing when you leave the app", settings.backgroundPlayback) { v -> onChange { it.copy(backgroundPlayback = v) } } }
            item { Toggle("Picture-in-picture on leave", "Shrink the stream into a floating window when you go home", settings.autoPip) { v -> onChange { it.copy(autoPip = v) } } }
            item { Toggle("Block ads", "Strip stitched Twitch ads and block ad/tracker hosts in web views", settings.adblock) { v -> onChange { it.copy(adblock = v) } } }

            item { Header("Layout") }
            item { Toggle("Chat on the left", "Landscape layout, like the Swap button", settings.chatOnLeft) { v -> onChange { it.copy(chatOnLeft = v) } } }
            item { Toggle("Show chat in landscape", null, settings.landscapeChatVisible) { v -> onChange { it.copy(landscapeChatVisible = v) } } }
            item { Toggle("Show top embeds", "The list of streams chat is watching", settings.showTopEmbeds) { v -> onChange { it.copy(showTopEmbeds = v) } } }

            item { Header("Chat") }
            item { Toggle("Use web chat", "Load destiny.gg's own chat page instead of the native chat", settings.webChat) { v -> onChange { it.copy(webChat = v) } } }
            item { Toggle("Timestamps", null, settings.showTimestamps) { v -> onChange { it.copy(showTimestamps = v) } } }
            item { Toggle("Hide flairs", null, settings.hideFlairs) { v -> onChange { it.copy(hideFlairs = v) } } }
            item { Toggle("Animated emotes", null, settings.animateEmotes) { v -> onChange { it.copy(animateEmotes = v) } } }
            item { Toggle("Highlight mentions", "Messages that mention you or your highlight words", settings.highlightMentions) { v -> onChange { it.copy(highlightMentions = v) } } }
            item { Toggle("Greentext", "Colour messages starting with >", settings.greentext) { v -> onChange { it.copy(greentext = v) } } }
            item { Toggle("Format embed links", "Turn #kick/… style links into buttons", settings.formatEmbedLinks) { v -> onChange { it.copy(formatEmbedLinks = v) } } }
            item { Toggle("Show combos", "C-C-C-COMBO counters for repeated emotes", settings.showCombos) { v -> onChange { it.copy(showCombos = v) } } }
            item { Toggle("Hide NSFW/NSFL links", null, settings.hideNsfw) { v -> onChange { it.copy(hideNsfw = v) } } }
            item { Toggle("Show removed messages", "Keep messages from muted/banned users, struck through", settings.showRemoved) { v -> onChange { it.copy(showRemoved = v) } } }
            item { Toggle("Join/leave notices", null, settings.showJoinLeave) { v -> onChange { it.copy(showJoinLeave = v) } } }
            item { Toggle("Whispers in chat", "Show incoming whispers inline in the chat", settings.whispersInChat) { v -> onChange { it.copy(whispersInChat = v) } } }
            item {
                Toggle("Whisper notifications", "Notify when someone whispers you while the app is in the background", settings.notifyWhispers) { v ->
                    if (v) enableNotification { it.copy(notifyWhispers = true) } else onChange { it.copy(notifyWhispers = false) }
                }
            }
            item {
                Toggle("Highlight notifications", "Notify when you're mentioned while the app is in the background", settings.notifyHighlights) { v ->
                    if (v) enableNotification { it.copy(notifyHighlights = true) } else onChange { it.copy(notifyHighlights = false) }
                }
            }
            item { Toggle("Hide mentions of ignored users", null, settings.hideIgnoredMentions) { v -> onChange { it.copy(hideIgnoredMentions = v) } } }
            item {
                ListItem(
                    headlineContent = { Text("Chat text size") },
                    supportingContent = {
                        Slider(
                            value = settings.chatFontScale,
                            onValueChange = { v -> onChange { it.copy(chatFontScale = v) } },
                            valueRange = 0.75f..1.6f,
                            steps = 16,
                        )
                    },
                    trailingContent = { Text("${(settings.chatFontScale * 100).toInt()}%") },
                )
            }
            item { WordsRow("Highlight words", settings.highlightWords) { editing = "highlight" } }
            item { WordsRow("Ignored users", settings.ignoredUsers) { editing = "ignore" } }
            item { WordsRow("Highlighted users", settings.highlightNicks) { editing = "highlightNicks" } }

            item { Header("Appearance") }
            item { Toggle("Material You colours", "Use your wallpaper colours", settings.dynamicColor) { v -> onChange { it.copy(dynamicColor = v) } } }
            item { Toggle("Pure black", "For OLED screens", settings.pureBlack) { v -> onChange { it.copy(pureBlack = v) } } }

            item {
                Text(
                    "Tinyscreen ${BuildConfig.VERSION_NAME} — an unofficial destiny.gg bigscreen client",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    when (editing) {
        "highlight" -> WordListDialog("Highlight words", settings.highlightWords, onDismiss = { editing = null }) { words ->
            onChange { it.copy(highlightWords = words) }
        }
        "ignore" -> WordListDialog("Ignored users", settings.ignoredUsers, onDismiss = { editing = null }) { words ->
            onChange { it.copy(ignoredUsers = words) }
        }
        "highlightNicks" -> WordListDialog("Highlighted users", settings.highlightNicks, onDismiss = { editing = null }) { words ->
            onChange { it.copy(highlightNicks = words) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(title: String, subtitle: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(subtitle)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    options.forEach { option ->
                        FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(option) })
                    }
                }
            }
        },
    )
}

@Composable
private fun WordsRow(title: String, words: Set<String>, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (words.isEmpty()) "None" else words.sorted().joinToString(", ")) },
        modifier = Modifier.clickable(onClick = onEdit),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WordListDialog(title: String, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var words by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = {
                        val w = input.trim()
                        if (w.isNotEmpty()) words = words + w
                        input = ""
                    }) { Text("Add") }
                }
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    words.sorted().forEach { w ->
                        InputChip(
                            selected = false,
                            onClick = { words = words - w },
                            label = { Text(w) },
                            trailingIcon = { Icon(Icons.Filled.Close, "Remove") },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(words); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
