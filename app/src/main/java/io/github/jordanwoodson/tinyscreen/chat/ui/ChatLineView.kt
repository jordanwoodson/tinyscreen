package io.github.jordanwoodson.tinyscreen.chat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.jordanwoodson.tinyscreen.chat.ChatLine
import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import io.github.jordanwoodson.tinyscreen.chat.ChatRow
import io.github.jordanwoodson.tinyscreen.chat.ChatUser
import io.github.jordanwoodson.tinyscreen.chat.Emote
import io.github.jordanwoodson.tinyscreen.chat.Flair
import io.github.jordanwoodson.tinyscreen.chat.InfoKind
import io.github.jordanwoodson.tinyscreen.chat.LinkTag
import io.github.jordanwoodson.tinyscreen.chat.PaidEvent
import io.github.jordanwoodson.tinyscreen.chat.Segment
import io.github.jordanwoodson.tinyscreen.stream.Embed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatColors {
    val Background = Color(0xFF080808)
    val Text = Color(0xFFB9B9B9)
    val Bright = Color(0xFFDEDEDE)
    val Username = Color(0xFFDCDCDC)
    val Time = Color(0xFF5A5A5A)
    val Link = Color(0xFF02C2FF)
    val Green = Color(0xFF6CA528)
    val Own = Color(0xFF151515)
    val Highlight = Color(0xFF06263E)
    val Censored = Color(0xFF4A8ECC)
    val Historical = Color(0xFF999999)
    val Nsfw = Color(0xFFFF0000)
    val Nsfl = Color(0xFFFFF000)
    val Spoilers = Color(0xFFFF80CE)
    val Broadcast = Color(0xFFEDEA12)
    val Error = Color(0xFFFF8A80)
    val Rainbow = Brush.linearGradient(
        listOf(0, 45, 90, 135, 180, 225, 270, 315, 360).map { Color.hsl(it.toFloat(), 1f, 0.65f) },
    )
}

/** Everything a line needs from the panel, bundled so rows recompose cheaply. */
@Immutable
data class LineContext(
    val me: ChatUser?,
    val flairs: Map<String, Flair>,
    val fontSize: TextUnit,
    val emoteScale: Float,
    val showTime: Boolean,
    val hideFlairs: Boolean,
    val animate: Boolean,
    val greentext: Boolean,
    val formatEmbeds: Boolean,
    val tags: Map<String, Color>,
    val spotlights: Set<String>,
    val focused: String?,
)

data class LineActions(
    val onUser: (String) -> Unit,
    val onEmbed: (Embed) -> Unit,
    val onUrl: (String) -> Unit,
    val onReveal: (String) -> Unit,
    val onEvent: (PaidEvent) -> Unit,
)

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

fun usernameStyle(user: ChatUser, flairs: Map<String, Flair>): SpanStyle {
    val decider = user.features.mapNotNull { flairs[it] }
        .sortedBy { it.priority }
        .firstOrNull { it.rainbow || it.color != null }
    return when {
        decider == null -> SpanStyle(color = ChatColors.Username, fontWeight = FontWeight.Medium)
        decider.rainbow -> SpanStyle(brush = ChatColors.Rainbow, fontWeight = FontWeight.Medium)
        else -> SpanStyle(color = Color(decider.color!!), fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ChatLineView(row: ChatRow, ctx: LineContext, actions: LineActions, inlineContent: Map<String, InlineTextContent>) {
    ChatLineView(row.line, ctx, actions, inlineContent, row.continued, row.censored, row.highlighted)
}

@Composable
fun ChatLineView(
    line: ChatLine,
    ctx: LineContext,
    actions: LineActions,
    inlineContent: Map<String, InlineTextContent>,
    continued: Boolean = false,
    censored: Boolean = false,
    highlighted: Boolean = false,
) {
    when (line) {
        is ChatLine.User -> UserLine(line, ctx, actions, continued, censored, highlighted, inlineContent)
        is ChatLine.Combo -> ComboLine(line, ctx)
        is ChatLine.Info -> InfoLine(line, ctx, actions)
        is ChatLine.Broadcast -> Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .border(1.dp, ChatColors.Broadcast, RoundedCornerShape(6.dp))
                .padding(8.dp),
        ) {
            Text(
                buildAnnotatedString {
                    line.user?.let { u -> withStyle(usernameStyle(u, ctx.flairs)) { append(u.nick) }; append(": ") }
                    appendSegments(line.segments, ctx, actions, null)
                },
                inlineContent = inlineContent,
                style = TextStyle(color = ChatColors.Broadcast, fontSize = ctx.fontSize),
            )
        }
        is ChatLine.Death -> Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("☠ ", fontSize = ctx.fontSize)
            Text(
                buildAnnotatedString {
                    withStyle(usernameStyle(line.user, ctx.flairs)) { append(line.user.nick) }
                    append(" ")
                    append(line.text)
                },
                style = TextStyle(color = ChatColors.Text, fontSize = ctx.fontSize, fontStyle = FontStyle.Italic),
            )
        }
        is ChatLine.Event -> EventCard(line.event, ctx, onClick = { actions.onEvent(line.event) })
    }
}

@Composable
private fun UserLine(
    line: ChatLine.User,
    ctx: LineContext,
    actions: LineActions,
    continued: Boolean,
    censored: Boolean,
    highlighted: Boolean,
    inline: Map<String, InlineTextContent>,
) {
    val own = ctx.me != null && line.user.key == ctx.me.key
    val tag = ctx.tags[line.user.key]
    val spotlit = line.hash.isNotEmpty() && line.hash in ctx.spotlights
    val dimmed = ctx.focused != null && line.user.key != ctx.focused && ctx.focused !in line.mentions
    val bg = when {
        line.whisper -> ChatColors.Own
        highlighted -> ChatColors.Highlight
        own -> ChatColors.Own
        else -> Color.Transparent
    }
    val text = buildAnnotatedString {
        if (ctx.showTime || line.historical) {
            withStyle(SpanStyle(color = ChatColors.Time, fontSize = ctx.fontSize * 0.8f)) { append(timeFormat.format(Date(line.timestamp)) + " ") }
        }
        if (continued && !line.whisper) {
            withStyle(SpanStyle(color = Color(0xFF414141))) { append("> ") }
        } else {
            if (!ctx.hideFlairs) {
                line.user.features.mapNotNull { ctx.flairs[it] }
                    .filter { !it.hidden && it.imageUrl != null }
                    .sortedBy { it.priority }
                    .forEach { f -> appendInlineContent("flair:${f.name}", f.label); append(" ") }
            }
            val nameStyle = if (line.whisper) {
                SpanStyle(color = Color.White, background = ChatColors.Censored)
            } else {
                usernameStyle(line.user, ctx.flairs)
            }
            withLink(LinkAnnotation.Clickable("user:${line.user.nick}", TextLinkStyles(nameStyle)) { actions.onUser(line.user.nick) }) {
                append(if (line.whisper) " ${line.user.nick} " else line.user.nick)
            }
            append(
                when {
                    line.whisper -> " whispered: "
                    line.action -> " "
                    else -> ": "
                },
            )
        }
        if (censored) {
            withLink(LinkAnnotation.Clickable("reveal:${line.user.key}", TextLinkStyles(SpanStyle(color = ChatColors.Censored))) { actions.onReveal(line.user.key) }) {
                append("<censored>")
            }
        } else {
            val base = when {
                line.historical -> SpanStyle(color = ChatColors.Historical)
                line.greentext && ctx.greentext -> SpanStyle(color = ChatColors.Green)
                own || highlighted -> SpanStyle(color = ChatColors.Bright)
                else -> SpanStyle(color = ChatColors.Text)
            }.let { if (line.action) it.copy(fontStyle = FontStyle.Italic) else it }
            withStyle(base) { appendSegments(line.segments, ctx, actions, line.linkTag) }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .then(if (spotlit) Modifier.border(1.dp, Color(0xFFFFC531), RoundedCornerShape(4.dp)) else Modifier)
            .then(
                if (tag != null) {
                    Modifier.drawBehind { drawRect(tag, size = Size(4.dp.toPx(), size.height), topLeft = Offset.Zero) }
                } else {
                    Modifier
                },
            )
            .padding(start = if (tag != null) 10.dp else 8.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Text(
            text = text,
            inlineContent = inline,
            style = TextStyle(
                fontSize = ctx.fontSize,
                lineHeight = ctx.fontSize * 1.43f,
                color = ChatColors.Text,
            ),
            modifier = Modifier.alpha(
                when {
                    dimmed -> 0.3f
                    line.pending -> 0.6f
                    else -> 1f
                },
            ),
        )
    }
}

private fun AnnotatedString.Builder.appendSegments(
    segments: List<Segment>,
    ctx: LineContext,
    actions: LineActions,
    tag: LinkTag?,
) {
    for (s in segments) {
        when (s) {
            is Segment.Text -> append(s.text)
            is Segment.EmoteSeg -> appendInlineContent("emote:${s.emote.prefix}", s.emote.prefix)
            is Segment.Mention -> withLink(
                LinkAnnotation.Clickable("mention:${s.nick}", TextLinkStyles(SpanStyle(color = ChatColors.Bright, fontWeight = FontWeight.Medium))) { actions.onUser(s.nick) },
            ) { append(s.text) }
            is Segment.HashLink -> withLink(
                LinkAnnotation.Clickable("hash:${s.embed.hash}", TextLinkStyles(SpanStyle(color = ChatColors.Link))) { actions.onEmbed(s.embed) },
            ) { append(s.text) }
            is Segment.Link -> {
                val decoration = when (tag) {
                    LinkTag.Nsfw -> SpanStyle(color = ChatColors.Link, textDecoration = TextDecoration.Underline, background = ChatColors.Nsfw.copy(alpha = 0.15f))
                    LinkTag.Nsfl -> SpanStyle(color = ChatColors.Link, textDecoration = TextDecoration.Underline, background = ChatColors.Nsfl.copy(alpha = 0.15f))
                    LinkTag.Spoilers -> SpanStyle(color = ChatColors.Link, textDecoration = TextDecoration.Underline, background = ChatColors.Spoilers.copy(alpha = 0.15f))
                    null -> SpanStyle(color = ChatColors.Link)
                }
                withLink(LinkAnnotation.Clickable("url:${s.url}", TextLinkStyles(decoration)) { actions.onUrl(s.url) }) { append(s.display) }
                if (s.embed != null && ctx.formatEmbeds) {
                    append(" ")
                    withLink(LinkAnnotation.Clickable("embed:${s.embed.hash}", TextLinkStyles(SpanStyle())) { actions.onEmbed(s.embed) }) {
                        appendInlineContent(EMBED_BUTTON, "[embed]")
                    }
                }
            }
        }
    }
}

const val EMBED_BUTTON = "embedbtn"

/** One shared inline-content map for every emote and flair, rebuilt only when assets or scale change. */
@Composable
fun rememberInlineContent(emotes: Collection<Emote>, flairs: Collection<Flair>, scale: Float, animate: Boolean): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    return remember(emotes, flairs, scale, animate, density) {
        val map = HashMap<String, InlineTextContent>(emotes.size + flairs.size + 1)
        with(density) {
            emotes.forEach { e ->
                map["emote:${e.prefix}"] = InlineTextContent(
                    Placeholder((e.width * scale).dp.toSp(), (e.height * scale).dp.toSp(), PlaceholderVerticalAlign.TextCenter),
                ) { EmoteImage(e, scale, animate) }
            }
            flairs.forEach { f ->
                val url = f.imageUrl ?: return@forEach
                val h = 16f * scale
                val w = if (f.height > 0) h * f.width / f.height else h
                map["flair:${f.name}"] = InlineTextContent(
                    Placeholder(w.dp.toSp(), h.dp.toSp(), PlaceholderVerticalAlign.TextCenter),
                ) { AsyncImage(model = url, contentDescription = f.label, modifier = Modifier.size(w.dp, h.dp)) }
            }
            map[EMBED_BUTTON] = InlineTextContent(Placeholder((18 * scale).dp.toSp(), (14 * scale).dp.toSp(), PlaceholderVerticalAlign.TextCenter)) {
                Icon(Icons.Filled.SmartDisplay, "Watch on bigscreen", tint = ChatColors.Link)
            }
        }
        map
    }
}

@Composable
private fun ComboLine(line: ChatLine.Combo, ctx: LineContext) {
    val (size, bold) = when {
        line.count >= 50 -> 5f to true
        line.count >= 30 -> 4f to true
        line.count >= 20 -> 3f to true
        line.count >= 10 -> 2f to true
        line.count >= 5 -> 1.5f to false
        else -> 1.15f to false
    }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        EmoteImage(line.emote, ctx.emoteScale, ctx.animate)
        Spacer(Modifier.width(6.dp))
        Text("${line.count}", color = ChatColors.Bright, fontSize = ctx.fontSize * size, fontWeight = FontWeight.Bold)
        Text(" × ", color = ChatColors.Bright, fontSize = ctx.fontSize)
        Text(
            if (line.completed) "C-C-C-COMBO" else "HITS",
            color = if (line.completed) ChatColors.Bright else Color(0xFFB91010),
            fontSize = ctx.fontSize,
            fontStyle = FontStyle.Italic,
            fontWeight = if (bold || !line.completed) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoLine(line: ChatLine.Info, ctx: LineContext, actions: LineActions) {
    val (icon, color) = when (line.kind) {
        InfoKind.Error -> Icons.Filled.ErrorOutline to ChatColors.Error
        InfoKind.Status -> Icons.Outlined.Wifi to ChatColors.Bright
        InfoKind.Command -> Icons.Filled.Campaign to ChatColors.Bright
        InfoKind.Historical -> Icons.Filled.Info to ChatColors.Historical
        InfoKind.Info -> Icons.Filled.Info to ChatColors.Bright
    }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(line.text, color = color, fontSize = ctx.fontSize, lineHeight = ctx.fontSize * 1.43f)
            if (line.link != null) {
                Row(Modifier.clickable { actions.onUrl(line.link) }, verticalAlignment = Alignment.CenterVertically) {
                    Text(line.linkLabel ?: line.link, color = ChatColors.Link, fontSize = ctx.fontSize)
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = ChatColors.Link, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

fun eventColor(event: PaidEvent): Color = when {
    event.amountCents != null -> when {
        event.amountCents >= 100_00 -> Color(0xFFE62117)
        event.amountCents >= 50_00 -> Color(0xFFDD29D2)
        event.amountCents >= 25_00 -> Color(0xFF4DB524)
        event.amountCents >= 10_00 -> Color(0xFF2ADDC8)
        event.amountCents >= 5_00 -> Color(0xFF59AEEA)
        else -> Color(0xFF1C5CDB)
    }
    event.tier != null -> when (event.tier) {
        5 -> Color(0xFFEB79DA)
        4 -> Color(0xFFDD29D2)
        3 -> Color(0xFF4DB524)
        2 -> Color(0xFF2ADDC8)
        else -> Color(0xFF59AEEA)
    }
    event.type == "XPOST" -> Color(0xFFDEDEDE)
    else -> Color(0xFFFFC531)
}

@Composable
fun EventCard(event: PaidEvent, ctx: LineContext, onClick: () -> Unit) {
    val color = eventColor(event)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .border(2.dp, color, RoundedCornerShape(10.dp))
            .background(ChatColors.Background, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().background(ChatColors.Own, RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(if (event.type == "DONATION") Icons.Filled.Star else Icons.Filled.Tv, null, tint = color, modifier = Modifier.size(16.dp))
            Text(event.title, color = ChatColors.Bright, fontSize = ctx.fontSize, fontWeight = FontWeight.Medium)
        }
        val body = listOfNotNull(event.subtitle, event.message).joinToString("\n")
        if (body.isNotBlank()) {
            Text(body, color = ChatColors.Text, fontSize = ctx.fontSize, modifier = Modifier.padding(8.dp))
        }
    }
}

fun tagColors(tagged: Set<String>): Map<String, Color> = tagged.mapNotNull { entry ->
    val nick = entry.substringBefore('=').lowercase()
    val color = ChatRepository.TAG_COLORS[entry.substringAfter('=', "").lowercase()] ?: return@mapNotNull null
    nick to Color(color)
}.toMap()
