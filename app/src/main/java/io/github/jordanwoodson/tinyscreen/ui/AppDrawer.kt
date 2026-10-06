package io.github.jordanwoodson.tinyscreen.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Link(val label: String, val url: String)

private val navigationLinks = listOf(
    Link("Home", "https://www.destiny.gg/"),
    Link("Merch", "https://www.destiny.gg/merch"),
    Link("TTS Queue", "https://www.destiny.gg/tts"),
    Link("Vestaboard", "https://www.destiny.gg/vestaboard"),
    Link("The Vault", "https://www.destiny.gg/vault"),
    Link("VODs", "https://www.destiny.gg/vods"),
    Link("Events", "https://www.destiny.gg/events"),
)

private val externalLinks = listOf(
    Link("Pragmatic Papers", "https://pragmaticpapers.com/"),
    Link("Schedule", "https://calendar.google.com/calendar/u/0/embed?src=i54j4cu9pl4270asok3mqgdrhk@group.calendar.google.com"),
    Link("Wiki", "https://wiki.destiny.gg/view/Main_Page"),
    Link("Destiny's Notes", "https://publish.obsidian.md/destiny/About"),
    Link("DGGWare", "https://store.steampowered.com/app/2891600/DGGWare/"),
    Link("YouTube", "https://www.destiny.gg/youtube"),
    Link("Kick", "https://kick.com/destiny"),
    Link("Twitter", "https://www.destiny.gg/twitter"),
    Link("Bluesky", "https://bsky.app/profile/destiny.gg"),
    Link("Instagram", "https://www.destiny.gg/instagram"),
    Link("TikTok", "https://www.tiktok.com/@officialdestiny.gg"),
    Link("Reddit", "https://www.destiny.gg/reddit"),
    Link("Discord", "https://www.destiny.gg/discord"),
    Link("Podcast (Spotify)", "https://open.spotify.com/show/6VoCbsDYJYolh3HISMMvni"),
    Link("Podcast (Apple)", "https://podcasts.apple.com/us/podcast/destiny-debates-and-discussions/id1800671132"),
)

@Composable
fun AppDrawer(
    nick: String?,
    unreadWhispers: Int,
    onOpenUrl: (String) -> Unit,
    onBigscreen: () -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onWhispers: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(Modifier.width(300.dp)) {
        LazyColumn(Modifier.fillMaxHeight()) {
            item {
                Row(Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LiveTv, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Tinyscreen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            item {
                if (nick != null) {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Person, null) },
                        label = { Text(nick, fontWeight = FontWeight.Bold) },
                        selected = false,
                        onClick = { onOpenUrl("https://www.destiny.gg/profile") },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        icon = { Icon(Icons.Filled.Mail, null) },
                        label = { Text("Whispers") },
                        badge = { if (unreadWhispers > 0) Text("$unreadWhispers") },
                        selected = false,
                        onClick = onWhispers,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Login, null) },
                        label = { Text("Log in") },
                        selected = false,
                        onClick = onLogin,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
            item {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.LiveTv, null) },
                    label = { Text("Big Screen Live") },
                    selected = true,
                    onClick = onBigscreen,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Star, null) },
                    label = { Text("Subscribe") },
                    selected = false,
                    onClick = { onOpenUrl("https://www.destiny.gg/subscribe") },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.AttachMoney, null) },
                    label = { Text("Donate") },
                    selected = false,
                    onClick = { onOpenUrl("https://www.destiny.gg/donate") },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item { Section("Navigation") }
            items(navigationLinks) { link ->
                NavigationDrawerItem(
                    icon = { Icon(if (link.label == "Home") Icons.Filled.Home else Icons.AutoMirrored.Filled.OpenInNew, null) },
                    label = { Text(link.label) },
                    selected = false,
                    onClick = { onOpenUrl(link.url) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item { Section("Links") }
            items(externalLinks) { link ->
                NavigationDrawerItem(
                    label = { Text(link.label) },
                    selected = false,
                    onClick = { onOpenUrl(link.url) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = onSettings,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            if (nick != null) {
                item {
                    NavigationDrawerItem(
                        icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                        label = { Text("Log out") },
                        selected = false,
                        onClick = onLogout,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 0.dp).padding(bottom = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 4.dp),
    )
}
