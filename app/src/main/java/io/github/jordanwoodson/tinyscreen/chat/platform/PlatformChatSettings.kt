package io.github.jordanwoodson.tinyscreen.chat.platform

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.ui.platformIcon

/** Settings row: which platforms get a chat tab. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlatformChatChoices(hidden: Set<String>, onChange: (Set<String>) -> Unit) {
    ListItem(
        headlineContent = { Text("Chat tabs for") },
        supportingContent = {
            Column {
                Text("Kick is always there; the others appear while Destiny is live on them")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PlatformChats.PLATFORMS.forEach { platform ->
                        val shown = platform !in hidden
                        FilterChip(
                            selected = shown,
                            onClick = { onChange(if (shown) hidden + platform else hidden - platform) },
                            label = { Text(Embed.platformName(platform)) },
                            leadingIcon = platformIcon(platform)?.let { icon ->
                                { Icon(painterResource(icon), null, modifier = Modifier.size(16.dp)) }
                            },
                        )
                    }
                }
            }
        },
    )
}
