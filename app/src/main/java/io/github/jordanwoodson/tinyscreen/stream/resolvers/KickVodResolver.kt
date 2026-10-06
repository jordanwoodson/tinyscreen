package io.github.jordanwoodson.tinyscreen.stream.resolvers

import androidx.media3.common.C
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed

/** `#kick-vod/Channel/uuid` — destiny.gg resolves these itself (Cloudflare-proof); Kick's API is the fallback. */
class KickVodResolver : StreamResolver {
    override val platforms = setOf("kick-vod")

    override suspend fun resolve(embed: Embed): ResolvedStream {
        val parts = embed.id.split('/', limit = 2)
        val (channel, uuid) = if (parts.size == 2) parts[0] to parts[1] else "destiny" to parts[0]
        var title: String? = null
        val source = runCatching {
            val body = Http.getString("https://www.destiny.gg/api/kick/vod/$channel/$uuid")
            Http.json.parseToJsonElement(body)["data"]["source"].string
        }.getOrNull() ?: run {
            val body = Http.getString("https://kick.com/api/v1/video/$uuid", KickResolver.ACCEPT_JSON)
            checkNotChallenge(body)
            val json = Http.json.parseToJsonElement(body)
            title = json["livestream"]["session_title"].string
            json["source"].string
        } ?: error("VOD not found")
        return ResolvedStream(
            url = source,
            kind = StreamKind.Hls,
            isLive = false,
            title = title,
            subtitle = "$channel · Kick VOD",
            startPositionMs = embed.startSeconds?.let { it * 1000L } ?: C.TIME_UNSET,
            refresh = { resolve(embed) },
        )
    }
}
