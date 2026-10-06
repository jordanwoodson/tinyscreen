package io.github.jordanwoodson.tinyscreen.stream

import android.net.Uri
import androidx.core.net.toUri

/** Turns shared/deep-linked URLs (bigscreen hashes or plain platform links) into embeds. */
object LinkParser {
    private val urlInText = Regex("""https?://\S+""")
    private val rumblePage = Regex("""v[0-9a-z]+-.*\.html""")
    private val duration = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""")

    fun parse(text: String): Embed? {
        val trimmed = text.trim()
        if (trimmed.startsWith("#")) return Embed.fromHash(trimmed)
        val url = urlInText.find(trimmed)?.value ?: return null
        return fromUrl(url)
    }

    fun fromUrl(url: String): Embed? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        // Where a VOD link starts (t=90, t=1h2m3s, start=90); channels play live
        val start = startSeconds(uri)
        return when {
            host == "destiny.gg" -> uri.fragment?.let { Embed.fromHash("#$it") }
            host == "youtu.be" -> segments.firstOrNull()?.let { Embed("youtube", it, start) }
            host.endsWith("youtube.com") -> when {
                uri.getQueryParameter("v") != null -> Embed("youtube", uri.getQueryParameter("v")!!, start)
                segments.size >= 2 && segments[0] in setOf("live", "embed", "shorts", "v") -> Embed("youtube", segments[1], start)
                else -> null
            }
            host == "kick.com" || host == "player.kick.com" -> when {
                segments.size >= 3 && segments[1] == "videos" -> Embed("kick-vod", "${segments[0]}/${segments[2]}", start)
                segments.size >= 2 && segments[0] == "video" -> Embed("kick-vod", segments[1], start)
                segments.size >= 3 && segments[1] == "clips" -> Embed("kick-clip", segments[2])
                segments.size == 1 -> Embed("kick", segments[0].lowercase())
                else -> null
            }
            host == "twitch.tv" || host == "player.twitch.tv" -> when {
                uri.getQueryParameter("channel") != null -> Embed("twitch", uri.getQueryParameter("channel")!!.lowercase())
                uri.getQueryParameter("video") != null -> Embed("twitch-vod", uri.getQueryParameter("video")!!.removePrefix("v"), start)
                segments.size >= 2 && segments[0] == "videos" -> Embed("twitch-vod", segments[1], start)
                segments.size >= 3 && segments[1] == "clip" -> Embed("twitch-clip", segments[2])
                segments.size == 1 -> Embed("twitch", segments[0].lowercase())
                else -> null
            }
            host == "clips.twitch.tv" -> segments.firstOrNull()?.let { Embed("twitch-clip", it) }
            host == "rumble.com" -> when {
                segments.size >= 2 && segments[0] == "embed" -> Embed("rumble", segments[1], start)
                else -> null
            }
            else -> null
        }
    }

    /**
     * An ordinary `rumble.com/v…-title.html` page link, as (page URL, start seconds). Its id isn't the
     * embed id, so it takes a lookup ([io.github.jordanwoodson.tinyscreen.stream.resolvers.RumbleResolver.embedIdForPage]).
     */
    fun rumblePage(text: String): Pair<String, Int?>? {
        val url = urlInText.find(text.trim())?.value ?: return null
        val uri = runCatching { url.toUri() }.getOrNull() ?: return null
        if (uri.host?.lowercase()?.removePrefix("www.") != "rumble.com") return null
        val page = uri.pathSegments.singleOrNull()?.takeIf { rumblePage.matches(it) } ?: return null
        return "https://rumble.com/$page" to startSeconds(uri)
    }

    private fun startSeconds(uri: Uri): Int? {
        val raw = uri.getQueryParameter("t") ?: uri.getQueryParameter("start")
            ?: uri.fragment?.takeIf { it.startsWith("t=") }?.removePrefix("t=")
            ?: return null
        return parseTime(raw)
    }

    /** `90`, `90s`, `1m30s` or `1h2m3s` as seconds; null for anything else or zero. */
    fun parseTime(raw: String): Int? {
        val text = raw.trim().lowercase()
        text.toIntOrNull()?.let { return it.takeIf { s -> s > 0 } }
        if (text.isEmpty()) return null
        val m = duration.matchEntire(text) ?: return null
        val (h, min, s) = m.destructured
        val total = (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }
}
