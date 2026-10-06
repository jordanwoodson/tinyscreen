package io.github.jordanwoodson.tinyscreen.stream.resolvers

import androidx.media3.common.C
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed

/** `#rumble/v5qi58n` — Rumble's embed API. A browser User-Agent trips Cloudflare, so send a plain one. */
class RumbleResolver : StreamResolver {
    override val platforms = setOf("rumble")

    override suspend fun resolve(embed: Embed): ResolvedStream {
        val id = embed.id.substringBefore('/').take(7)
        val body = Http.getString(
            "https://rumble.com/embedJS/u3/?request=video&ver=2&v=$id",
            mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 16)", "Accept" to "application/json"),
        )
        checkNotChallenge(body)
        val json = Http.json.parseToJsonElement(body)
        if (json.obj == null) error("Rumble video not found")
        val live = (json["live"].long ?: 0L) == 2L
        val ua = json["ua"]
        val hls = ua["hls"]["auto"]["url"].string ?: json["u"]["hls"]["url"].string
        val tar = ua["tar"].obj?.values?.mapNotNull { it["url"].string }?.firstOrNull()
        val mp4 = ua["mp4"].obj?.values
            ?.maxByOrNull { (it["meta"]["h"].long ?: 0) * 100_000 + (it["meta"]["bitrate"].long ?: 0) }
            ?.get("url").string
        val (url, kind) = when {
            hls != null -> hls to StreamKind.Hls
            tar != null -> tar to StreamKind.Hls
            mp4 != null -> mp4 to StreamKind.Progressive
            else -> error("No playable Rumble stream")
        }
        return ResolvedStream(
            url = url,
            kind = kind,
            isLive = live,
            title = json["title"].string,
            subtitle = (json["author"]["name"].string ?: "Rumble") + " · Rumble",
            artworkUrl = json["i"].string,
            startPositionMs = embed.startSeconds?.let { it * 1000L } ?: C.TIME_UNSET,
            liveTargetOffsetMs = 5_000,
            refresh = { resolve(embed) },
        )
    }
}
