package io.github.jordanwoodson.tinyscreen.stream.resolvers

import android.net.Uri
import android.util.Log
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.player.AdBreakMonitor
import io.github.jordanwoodson.tinyscreen.player.PlaylistRewriter
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** Twitch live (with ad avoidance), VODs and clips via GQL access tokens + usher. */
class TwitchResolver : StreamResolver {
    override val platforms = setOf("twitch", "twitch-vod", "twitch-clip")

    override suspend fun resolve(embed: Embed): ResolvedStream = withContext(Dispatchers.IO) {
        when (embed.platform) {
            "twitch" -> resolveLive(embed)
            "twitch-vod" -> resolveVod(embed)
            else -> resolveClip(embed.id)
        }
    }

    private fun resolveLive(embed: Embed): ResolvedStream {
        val login = embed.id.lowercase()
        // Pre-flight: start on the first player type whose playlist has no preroll
        var chosen: TwitchMaster? = null
        var fallback: TwitchMaster? = null
        for (type in PLAYER_TYPES) {
            val master = runCatching { Twitch.liveMaster(login, type) }.getOrElse {
                if (it is TwitchOfflineException) throw it
                null
            } ?: continue
            if (fallback == null) fallback = master
            val variant = master.variants.maxByOrNull { it.pixels }?.url ?: continue
            val media = runCatching { Twitch.fetch(variant) }.getOrNull() ?: continue
            if (!TwitchAds.hasAds(media)) {
                chosen = master
                break
            }
        }
        val master = chosen ?: fallback ?: error("Couldn't load $login's stream")
        val filter = TwitchAdFilter(login)
        filter.learnMaster(master.text)
        return ResolvedStream(
            url = master.url,
            kind = StreamKind.Hls,
            isLive = true,
            subtitle = "$login · Twitch",
            artworkUrl = "https://static-cdn.jtvnw.net/previews-ttv/live_user_$login-640x360.jpg",
            rewriter = filter,
            liveTargetOffsetMs = 5_000,
            refresh = { resolve(embed) },
        )
    }

    private fun resolveVod(embed: Embed): ResolvedStream {
        val id = embed.id.removePrefix("v")
        val token = Twitch.accessToken(login = null, vodId = id, playerType = "embed", platform = "web")
        val url = "https://usher.ttvnw.net/vod/v2/$id.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("sig", token.second).addQueryParameter("token", token.first)
            .addQueryParameter("allow_source", "true").addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("p", Random.nextInt(9_999_999).toString()).addQueryParameter("platform", "web")
            .addQueryParameter("playlist_include_framerate", "true").addQueryParameter("supported_codecs", "h264")
            .addQueryParameter("player_backend", "mediaplayer").build().toString()
        return ResolvedStream(
            url = url,
            kind = StreamKind.Hls,
            isLive = false,
            subtitle = "Twitch VOD",
            startPositionMs = embed.startSeconds?.let { it * 1000L } ?: androidx.media3.common.C.TIME_UNSET,
            refresh = { resolve(embed) },
        )
    }

    private fun resolveClip(slug: String): ResolvedStream {
        val body = buildJsonObject {
            put("operationName", "VideoAccessToken_Clip")
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", Twitch.CLIP_HASH) } }
            putJsonObject("variables") { put("slug", slug); put("platform", "web") }
        }
        val json = Http.json.parseToJsonElement(Twitch.gql(body.toString()))
        val clip = json["data"]["clip"]
        val sig = clip["playbackAccessToken"]["signature"].string ?: error("Clip not found")
        val value = clip["playbackAccessToken"]["value"].string ?: error("Clip not found")
        val source = clip["videoQualities"].array
            ?.maxByOrNull { (it["quality"].string?.toIntOrNull() ?: 0) * 100 + (it["frameRate"].long ?: 0) }
            ?.get("sourceURL").string ?: error("Clip has no video")
        return ResolvedStream(
            url = "$source?sig=$sig&token=${Uri.encode(value)}",
            kind = StreamKind.Progressive,
            isLive = false,
            subtitle = "Twitch clip",
        )
    }

    companion object {
        /** In order of preference: full quality first, the 360p "autoplay" type as a last resort. */
        val PLAYER_TYPES = listOf("popout", "embed", "autoplay", "site")
    }
}

class TwitchOfflineException(login: String) : StreamOfflineException(login)

data class TwitchVariant(val url: String, val width: Int, val height: Int, val frameRate: Float) {
    val pixels get() = width * height
}

/** A live master playlist and the URL it came from (its token and playlist session). */
class TwitchMaster(val playerType: String, val url: String, val text: String) {
    val variants: List<TwitchVariant> by lazy { Twitch.variants(text) }
}

internal object Twitch {
    const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"
    const val PAT_HASH = "ed230aa1e33e07eebb8928504583da78a5173989fadfb1ac94be06a04f3cdbe9"
    const val CLIP_HASH = "993d9a5131f15a37bd16f32342c44ed1e0b1a9b968c6afdb662d2cddd595f6c5"

    fun gql(body: String): String {
        val req = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", CLIENT_ID)
            .header("User-Agent", Http.DESKTOP_UA)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Twitch GQL HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    /** Returns (token value, signature). */
    fun accessToken(login: String?, vodId: String?, playerType: String, platform: String): Pair<String, String> {
        val body = buildJsonObject {
            put("operationName", "PlaybackAccessToken")
            putJsonObject("extensions") { putJsonObject("persistedQuery") { put("version", 1); put("sha256Hash", PAT_HASH) } }
            putJsonObject("variables") {
                put("isLive", vodId == null)
                put("login", login ?: "")
                put("isVod", vodId != null)
                put("vodID", vodId ?: "")
                put("playerType", playerType)
                put("platform", platform)
            }
        }
        val json = Http.json.parseToJsonElement(gql(body.toString()))
        val token = if (vodId == null) json["data"]["streamPlaybackAccessToken"] else json["data"]["videoPlaybackAccessToken"]
        val value = token["value"].string ?: error("No Twitch access token")
        val sig = token["signature"].string ?: error("No Twitch access token")
        return value to sig
    }

    fun liveMasterUrl(login: String, playerType: String): String {
        val platform = if (playerType == "autoplay") "android" else "web"
        val (value, sig) = accessToken(login, null, playerType, platform)
        return "https://usher.ttvnw.net/api/v2/channel/hls/$login.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("sig", sig).addQueryParameter("token", value)
            .addQueryParameter("allow_source", "true").addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("fast_bread", "true").addQueryParameter("p", Random.nextInt(9_999_999).toString())
            .addQueryParameter("platform", "web").addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("playlist_include_framerate", "true").addQueryParameter("reassignments_supported", "true")
            .addQueryParameter("supported_codecs", "h264").build().toString()
    }

    /** Fetches a live master playlist for a player type. */
    fun liveMaster(login: String, playerType: String): TwitchMaster {
        val url = liveMasterUrl(login, playerType)
        val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA).build()
        Http.client.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw TwitchOfflineException(login)
            if (!resp.isSuccessful) error("usher HTTP ${resp.code}")
            return TwitchMaster(playerType, url, resp.body!!.string())
        }
    }

    fun fetch(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    fun variants(master: String): List<TwitchVariant> {
        val out = mutableListOf<TwitchVariant>()
        val lines = master.lines()
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val url = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: continue
            val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)
            val fps = Regex("FRAME-RATE=([\\d.]+)").find(l)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            out += TwitchVariant(url.trim(), res?.groupValues?.get(1)?.toInt() ?: 0, res?.groupValues?.get(2)?.toInt() ?: 0, fps)
        }
        return out
    }
}

object TwitchAds {
    fun hasAds(m3u8: String): Boolean =
        m3u8.contains("stitched") || m3u8.contains("X-TV-TWITCH-AD-") ||
            m3u8.lineSequence().any { it.startsWith("#EXTINF") && !it.substringAfter(',').startsWith("live") }

    /** Removes only leading/trailing ad segments so media-sequence numbering stays valid. */
    fun strip(m3u8: String): String {
        val lines = m3u8.replace("\r", "").split('\n')
        val header = ArrayList<String>()
        val blocks = ArrayList<Pair<List<String>, Boolean>>()
        var cur = ArrayList<String>()
        var inSegments = false
        var curIsAd = false
        for (l in lines) {
            if (l.startsWith("#EXT-X-TWITCH-PREFETCH")) continue
            if (l.startsWith("#EXT-X-DATERANGE") && (l.contains("stitched") || l.contains("X-TV-TWITCH-AD-"))) continue
            if (!inSegments && (l.startsWith("#EXTINF") || l.startsWith("#EXT-X-PROGRAM-DATE-TIME") || l == "#EXT-X-DISCONTINUITY")) inSegments = true
            if (!inSegments) {
                header += l
                continue
            }
            cur += l
            if (l.startsWith("#EXTINF")) curIsAd = !l.substringAfter(',').startsWith("live")
            if (l.isNotBlank() && !l.startsWith("#")) {
                blocks += cur to curIsAd
                cur = ArrayList()
                curIsAd = false
            }
        }
        val firstLive = blocks.indexOfFirst { !it.second }
        if (firstLive < 0) return header.joinToString("\n") + "\n"
        val firstAdAfter = blocks.drop(firstLive).indexOfFirst { it.second }.let { if (it < 0) blocks.size else firstLive + it }
        val kept = blocks.subList(firstLive, firstAdAfter)
        val out = header.map { h ->
            if (h.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                "#EXT-X-MEDIA-SEQUENCE:${(h.substringAfter(':').trim().toLongOrNull() ?: 0) + firstLive}"
            } else {
                h
            }
        }.toMutableList()
        kept.forEach { out += it.first }
        return out.joinToString("\n") + "\n"
    }
}

/**
 * Runs on ExoPlayer's loader threads. During a mid-roll, swaps in the same-resolution playlist from
 * an ad-free backup player type (Twitch's sequence numbers are shared, so the swap is seamless);
 * if every backup has ads too, strips the ad segments and the player waits out the break.
 */
class TwitchAdFilter(private val login: String) : PlaylistRewriter {
    private val variantInfo = ConcurrentHashMap<String, TwitchVariant>()
    private val backupMasters = ConcurrentHashMap<String, Pair<Long, TwitchMaster>>()

    fun learnMaster(master: String) {
        Twitch.variants(master).forEach { variantInfo[it.url] = it }
    }

    override fun handles(uri: Uri): Boolean = uri.host?.endsWith("ttvnw.net") == true

    override fun rewrite(uri: Uri, playlist: String): String {
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            learnMaster(playlist)
            return playlist
        }
        if (!TwitchAds.hasAds(playlist)) {
            AdBreakMonitor.set(false)
            return playlist
        }
        val wanted = variantInfo[uri.toString()]
        for (type in BACKUP_TYPES) {
            val swapped = runCatching { backupPlaylist(type, wanted) }.onFailure { Log.d(TAG, "backup $type failed: ${it.message}") }.getOrNull()
            if (swapped != null && !TwitchAds.hasAds(swapped)) {
                AdBreakMonitor.set(false)
                return swapped
            }
        }
        AdBreakMonitor.set(true)
        return TwitchAds.strip(playlist)
    }

    private fun backupPlaylist(type: String, wanted: TwitchVariant?): String? {
        val now = System.currentTimeMillis()
        val cached = backupMasters[type]?.takeIf { now - it.first < 90_000 }?.second
        val master = cached ?: Twitch.liveMaster(login, type).also { backupMasters[type] = now to it }
        val variants = master.variants
        val pick = if (wanted == null) {
            variants.maxByOrNull { it.pixels }
        } else {
            variants.firstOrNull { it.width == wanted.width && it.height == wanted.height && it.frameRate == wanted.frameRate }
                ?: variants.minByOrNull { kotlin.math.abs(it.pixels - wanted.pixels) }
        } ?: return null
        return Twitch.fetch(pick.url)
    }

    companion object {
        private const val TAG = "TwitchAdFilter"
        val BACKUP_TYPES = listOf("embed", "popout", "autoplay")
    }
}
