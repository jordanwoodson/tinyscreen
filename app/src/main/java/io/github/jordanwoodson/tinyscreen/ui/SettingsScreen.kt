package io.github.jordanwoodson.tinyscreen.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.jordanwoodson.tinyscreen.BuildConfig
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference
import io.github.jordanwoodson.tinyscreen.settings.Quality

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    /** Whether the current network is metered, i.e. which quality setting is in use. */
    metered: Boolean,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val notifications = android.Manifest.permission.POST_NOTIFICATIONS
    var pendingNotify by remember { mutableStateOf<((AppSettings) -> AppSettings)?>(null) }
    var notificationHelp by remember { mutableStateOf<NotificationHelp?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val change = pendingNotify
        pendingNotify = null
        when {
            granted -> change?.let(onChange)
            // After a second "Don't allow" Android stops asking; only system settings can allow it now
            activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, notifications) ->
                notificationHelp = NotificationHelp.Blocked
        }
    }
    val enableNotification: ((AppSettings) -> AppSettings) -> Unit = { change ->
        when {
            ContextCompat.checkSelfPermission(context, notifications) == PackageManager.PERMISSION_GRANTED -> {
                onChange(change)
                if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) notificationHelp = NotificationHelp.Blocked
            }
            activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, notifications) -> {
                pendingNotify = change
                notificationHelp = NotificationHelp.Rationale
            }
            else -> {
                pendingNotify = change
                permission.launch(notifications)
            }
        }
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
                QualityRow(
                    "Quality on Wi-Fi",
                    "Unmetered networks${if (!metered) " · in use now" else ""}. Auto adapts to the connection; " +
                        "a resolution plays the best stream up to it",
                    settings.qualityWifi,
                ) { q -> onChange { it.copy(qualityWifi = q) } }
            }
            item {
                QualityRow(
                    "Quality on mobile data",
                    "Metered networks, including hotspots${if (metered) " · in use now" else ""}",
                    settings.qualityMobile,
                ) { q -> onChange { it.copy(qualityMobile = q) } }
            }
            item {
                Toggle(
                    "Remember my choice",
                    "A quality picked in the player becomes the default for the network you're on",
                    settings.rememberQuality,
                ) { v -> onChange { it.copy(rememberQuality = v) } }
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
                // Saved when you let go, not on every frame of the drag
                var fontScale by remember(settings.chatFontScale) { mutableFloatStateOf(settings.chatFontScale) }
                ListItem(
                    headlineContent = { Text("Chat text size") },
                    supportingContent = {
                        Slider(
                            value = fontScale,
                            onValueChange = { fontScale = it },
                            onValueChangeFinished = { onChange { it.copy(chatFontScale = fontScale) } },
                            valueRange = 0.75f..1.6f,
                            steps = 16,
                            modifier = Modifier.semantics { contentDescription = "Chat text size" },
                        )
                    },
                    trailingContent = { Text("${(fontScale * 100).toInt()}%") },
                )
            }
            item {
                ChoiceRow(
                    "Chat history",
                    "Messages kept while you're at the bottom of chat. Scrolled up, chat pauses and keeps up to " +
                        "${java.text.NumberFormat.getIntegerInstance().format(AppSettings.CHAT_LINES_UNPINNED)} so nothing moves while you read",
                    AppSettings.chatLineChoices.map { it.toString() },
                    settings.chatLines.toString(),
                ) { v -> onChange { it.copy(chatLines = v.toInt()) } }
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

    when (notificationHelp) {
        NotificationHelp.Rationale -> AlertDialog(
            onDismissRequest = { notificationHelp = null; pendingNotify = null },
            title = { Text("Allow notifications?") },
            text = { Text("Tinyscreen needs permission to tell you about whispers and mentions while it's in the background.") },
            confirmButton = {
                TextButton(onClick = { notificationHelp = null; permission.launch(notifications) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { notificationHelp = null; pendingNotify = null }) { Text("Not now") } },
        )
        NotificationHelp.Blocked -> AlertDialog(
            onDismissRequest = { notificationHelp = null },
            title = { Text("Notifications are off") },
            text = { Text("Notifications for Tinyscreen are turned off. You can turn them on in system settings.") },
            confirmButton = {
                TextButton(onClick = {
                    notificationHelp = null
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    runCatching { context.startActivity(intent) }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { notificationHelp = null }) { Text("Cancel") } },
        )
        null -> Unit
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

private enum class NotificationHelp { Rationale, Blocked }

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so TalkBack reads its title and state together
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
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
private fun QualityRow(title: String, subtitle: String, value: String, onSelect: (String) -> Unit) {
    // A remembered pick can be a resolution that isn't one of the usual choices, such as 160p
    val current = if (value in Quality.choices || value.toIntOrNull() != null) value else Quality.AUTO
    val choices = if (current in Quality.choices) Quality.choices else Quality.choices + current
    ChoiceRow(title, subtitle, choices.map(Quality::label), Quality.label(current)) { label ->
        onSelect(choices.first { Quality.label(it) == label })
    }
}

@Composable
private fun WordsRow(title: String, words: Set<String>, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(if (words.isEmpty()) "None" else words.sorted().joinToString(", ")) },
        modifier = Modifier.clickable(onClickLabel = "Edit", role = Role.Button, onClick = onEdit),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun WordListDialog(title: String, initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
    var words by remember { mutableStateOf(initial) }
    var input by remember { mutableStateOf("") }
    // Text typed but not yet added still counts when you press Save
    val withInput = { input.trim().takeIf { it.isNotEmpty() }?.let { words + it } ?: words }
    val add = {
        words = withInput()
        input = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Add to the list") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = add) { Text("Add") }
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
        confirmButton = { TextButton(onClick = { onSave(withInput()); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
