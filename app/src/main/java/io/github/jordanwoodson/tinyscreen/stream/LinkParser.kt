package io.github.jordanwoodson.tinyscreen.stream

import android.net.Uri

/** Turns shared/deep-linked URLs (bigscreen hashes or plain platform links) into embeds. */
object LinkParser {
    private val urlInText = Regex("""https?://\S+""")

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
        return when {
            host == "destiny.gg" -> uri.fragment?.let { Embed.fromHash("#$it") }
            host == "youtu.be" -> segments.firstOrNull()?.let { Embed("youtube", it) }
            host.endsWith("youtube.com") -> when {
                uri.getQueryParameter("v") != null -> Embed("youtube", uri.getQueryParameter("v")!!)
                segments.size >= 2 && segments[0] in setOf("live", "embed", "shorts", "v") -> Embed("youtube", segments[1])
                else -> null
            }
            host == "kick.com" || host == "player.kick.com" -> when {
                segments.size >= 3 && segments[1] == "videos" -> Embed("kick-vod", "${segments[0]}/${segments[2]}")
                segments.size >= 2 && segments[0] == "video" -> Embed("kick-vod", segments[1])
                segments.size >= 3 && segments[1] == "clips" -> Embed("kick-clip", segments[2])
                segments.size == 1 -> Embed("kick", segments[0].lowercase())
                else -> null
            }
            host == "twitch.tv" || host == "player.twitch.tv" -> when {
                uri.getQueryParameter("channel") != null -> Embed("twitch", uri.getQueryParameter("channel")!!.lowercase())
                uri.getQueryParameter("video") != null -> Embed("twitch-vod", uri.getQueryParameter("video")!!.removePrefix("v"))
                segments.size >= 2 && segments[0] == "videos" -> Embed("twitch-vod", segments[1])
                segments.size >= 3 && segments[1] == "clip" -> Embed("twitch-clip", segments[2])
                segments.size == 1 -> Embed("twitch", segments[0].lowercase())
                else -> null
            }
            host == "clips.twitch.tv" -> segments.firstOrNull()?.let { Embed("twitch-clip", it) }
            host == "rumble.com" -> when {
                segments.size >= 2 && segments[0] == "embed" -> Embed("rumble", segments[1])
                else -> null
            }
            else -> null
        }
    }
}
