package io.github.jordanwoodson.tinyscreen.stream.resolvers

import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.HttpException
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed

/** `#kick/slug` — Kick's AWS IVS playlist. Ad-free; the token in the master lasts 10 minutes. */
class KickResolver : StreamResolver {
    override val platforms = setOf("kick")

    override suspend fun resolve(embed: Embed): ResolvedStream {
        val slug = embed.id.lowercase()
        val body = try {
            Http.getString("https://kick.com/api/v2/channels/$slug/livestream", ACCEPT_JSON)
        } catch (e: HttpException) {
            if (e.code == 403) throw CloudflareBlockedException() else throw e
        }
        checkNotChallenge(body)
        val data = Http.json.parseToJsonElement(body)["data"]
        val url = data["playback_url"].string ?: throw IllegalStateException("$slug is offline")
        return ResolvedStream(
            url = url,
            kind = StreamKind.Hls,
            isLive = true,
            title = data["session_title"].string,
            subtitle = "$slug · Kick",
            artworkUrl = data["thumbnail"]["src"].string ?: data["thumbnail"]["url"].string,
            liveTargetOffsetMs = 4_000,
            refresh = { resolve(embed) },
        )
    }

    companion object {
        val ACCEPT_JSON = mapOf("Accept" to "application/json")
    }
}
