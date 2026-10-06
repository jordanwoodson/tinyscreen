package io.github.jordanwoodson.tinyscreen.chat

import android.net.Uri
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import java.security.MessageDigest

data class Formatted(
    val segments: List<Segment>,
    val action: Boolean,
    val greentext: Boolean,
    val sus: Boolean,
    val linkTag: LinkTag?,
    val mentions: Set<String>,
)

/** Turns raw chat text into display segments, following chat-gui's formatter order. */
class MessageFormatter(
    private val emotes: () -> EmoteSet,
    private val isConnectedUser: (String) -> Boolean,
) {
    fun format(raw: String, author: ChatUser?): Formatted {
        var text = raw
        val action = ME.containsMatchIn(text)
        if (action) text = text.replaceFirst(ME, "")

        val linkTag = when {
            NSFL.containsMatchIn(raw) -> LinkTag.Nsfl
            NSFW.containsMatchIn(raw) -> LinkTag.Nsfw
            SPOILERS.containsMatchIn(raw) -> LinkTag.Spoilers
            else -> null
        }
        val eligibleForStyles = author == null || author.isSubscriber || author.isPrivileged
        val greentext = eligibleForStyles && text.trimStart().startsWith(">")
        val sus = eligibleForStyles && text.trimStart().startsWith("ඞ")

        val segments = mutableListOf<Segment>()
        // 1. links
        var last = 0
        for (m in URL.findAll(text)) {
            if (m.range.first > last) segments += Segment.Text(text.substring(last, m.range.first))
            val rawUrl = m.value
            val url = withAmazonTag(if (rawUrl.startsWith("http", ignoreCase = true)) rawUrl else "https://$rawUrl")
            segments += Segment.Link(url, displayUrl(url), LinkParser.fromUrl(url)?.takeIf { it.webEmbedUrl() != null || it.platform == "kick-vod" })
            last = m.range.last + 1
        }
        if (last < text.length) segments += Segment.Text(text.substring(last))

        // 2. #platform/id hash links, 3. emotes, 4. mentions — applied to the remaining text
        val withHashes = segments.flatMap { s -> if (s is Segment.Text) splitHashes(s.text) else listOf(s) }
        val set = emotes()
        val withEmotes = withHashes.flatMap { s -> if (s is Segment.Text) splitEmotes(s.text, set, author) else listOf(s) }
        val mentions = mutableSetOf<String>()
        val finalSegments = withEmotes.flatMap { s -> if (s is Segment.Text) splitMentions(s.text, mentions) else listOf(s) }

        // ඞ shouts, but only the words: links and emotes (many are lowercase) keep their case
        val styled = if (sus) {
            finalSegments.map {
                when (it) {
                    is Segment.Text -> Segment.Text(it.text.uppercase())
                    is Segment.Mention -> it.copy(text = it.text.uppercase())
                    else -> it
                }
            }
        } else {
            finalSegments
        }
        return Formatted(merge(styled), action, greentext, sus, linkTag, mentions)
    }

    private fun splitHashes(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        var last = 0
        for (m in HASH.findAll(text)) {
            val start = m.groups[2]!!.range.first
            val embed = Embed.fromHash(m.groupValues[2]) ?: continue
            if (start > last) out += Segment.Text(text.substring(last, start))
            out += Segment.HashLink(embed, m.groupValues[2])
            last = m.range.last + 1
        }
        if (last < text.length) out += Segment.Text(text.substring(last))
        return out
    }

    private fun splitEmotes(text: String, set: EmoteSet, author: ChatUser?): List<Segment> {
        if (set.byPrefix.isEmpty()) return listOf(Segment.Text(text))
        val out = mutableListOf<Segment>()
        var last = 0
        // Emotes must be whole whitespace-delimited tokens
        for (m in TOKEN.findAll(text)) {
            val emote = set.byPrefix[m.value] ?: continue
            if (author != null && !author.canUse(emote)) continue
            if (m.range.first > last) out += Segment.Text(text.substring(last, m.range.first))
            out += Segment.EmoteSeg(emote)
            last = m.range.last + 1
        }
        if (last < text.length) out += Segment.Text(text.substring(last))
        return out
    }

    private fun splitMentions(text: String, mentions: MutableSet<String>): List<Segment> {
        val out = mutableListOf<Segment>()
        var last = 0
        for (m in WORD.findAll(text)) {
            val word = m.groupValues[1]
            val hasAt = text[m.range.first] == '@'
            val connected = isConnectedUser(word.lowercase())
            val next = text.getOrNull(m.range.last + 1)
            val atMention = hasAt && (next == null || next.isWhitespace() || next in ".?!,'")
            if (!connected && !atMention) continue
            if (connected) mentions += word.lowercase()
            if (m.range.first > last) out += Segment.Text(text.substring(last, m.range.first))
            out += Segment.Mention(word, m.value)
            last = m.range.last + 1
        }
        if (last < text.length) out += Segment.Text(text.substring(last))
        return out
    }

    private fun merge(segments: List<Segment>): List<Segment> {
        val out = ArrayList<Segment>(segments.size)
        for (s in segments) {
            val prev = out.lastOrNull()
            if (s is Segment.Text && prev is Segment.Text) out[out.lastIndex] = Segment.Text(prev.text + s.text) else if (!(s is Segment.Text && s.text.isEmpty())) out += s
        }
        return out
    }

    companion object {
        private val ME = Regex("^/me ", RegexOption.IGNORE_CASE)
        private val TOKEN = Regex("\\S+")
        private val NSFL = Regex("\\bNSFL\\b", RegexOption.IGNORE_CASE)
        private val NSFW = Regex("\\bNSFW\\b", RegexOption.IGNORE_CASE)
        private val SPOILERS = Regex("\\bSPOILERS\\b", RegexOption.IGNORE_CASE)
        private val WORD = Regex("(?<![\\w@])@?(\\w{3,20})(?!\\w)")
        private val URL = Regex(
            """(?i)\b(?:https?://|www\.)[^\s<>"]+[^\s<>".,:;'!?)\]]|\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|gg|tv|io|be|co|me|ly|app|dev|uk|ca|de|xyz|info|us|link|to|fm|gl|gd|cc|news|live|social|online|site)\b(?:/[^\s<>"]*[^\s<>".,:;'!?)\]])?""",
        )
        private val HASH = Regex(
            """(^|\s)(#(kick|kick-vod|twitch|twitch-vod|twitch-clip|youtube|youtube-live|facebook|rumble|vimeo|angelthump)/""" +
                """([\w\d]{3,64}/videos/\d{10,20}|[\w-]{3,64}/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}|[\w-]{3,64}|\w{7}/\?pub=\w{5})(?:\?t=(\d+)s?)?)\b""",
        )
        private val AMAZON_TAGS = mapOf(
            "www.amazon.com" to "destinygg-20",
            "www.amazon.ca" to "destiny0f7a-20",
            "www.amazon.co.uk" to "destiny0f7-21",
            "www.amazon.de" to "destiny0f-21",
        )

        private val HEX = "0123456789abcdef".toCharArray()

        /** chat-gui's message hash: md5 of timestamp, user id and raw text, as lowercase hex. */
        fun hash(timestamp: Long, userId: Long?, raw: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest("$timestamp${userId ?: ""}$raw".toByteArray())
            val out = CharArray(bytes.size * 2)
            bytes.forEachIndexed { i, b ->
                out[i * 2] = HEX[(b.toInt() shr 4) and 0xF]
                out[i * 2 + 1] = HEX[b.toInt() and 0xF]
            }
            return String(out)
        }

        fun withAmazonTag(url: String): String {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return url
            val tag = AMAZON_TAGS[uri.host?.lowercase()] ?: return url
            return uri.buildUpon().clearQuery().apply {
                uri.queryParameterNames.filter { it != "tag" }.forEach { k -> uri.getQueryParameters(k).forEach { appendQueryParameter(k, it) } }
                appendQueryParameter("tag", tag)
            }.build().toString()
        }

        /** Mirrors chat-gui's normalizeUrl + truncation for long links. */
        fun displayUrl(url: String): String {
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            var shown = url
            if (uri != null) {
                val host = uri.host?.lowercase().orEmpty()
                val drop: Set<String>? = when {
                    (host.endsWith("x.com") || host.endsWith("twitter.com")) && uri.path?.contains("/status/") == true -> null
                    host.endsWith("youtube.com") || host == "youtu.be" -> setOf("si", "is")
                    host.endsWith("instagram.com") -> setOf("igsh", "igshid", "igsi", "utm_source")
                    else -> emptySet()
                }
                shown = when {
                    drop == null -> uri.buildUpon().clearQuery().build().toString()
                    drop.isEmpty() -> url
                    else -> uri.buildUpon().clearQuery().apply {
                        uri.queryParameterNames.filter { it !in drop }.forEach { k -> uri.getQueryParameters(k).forEach { appendQueryParameter(k, it) } }
                    }.build().toString()
                }
            }
            return if (shown.length > 90) shown.take(40) + "..." + shown.takeLast(40) else shown
        }
    }
}
