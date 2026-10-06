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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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

    /** For the ad filter's backup fetches, which run inside a playlist load: give up quickly. */
    val quickClient: OkHttpClient by lazy { Http.client.newBuilder().callTimeout(2_500, TimeUnit.MILLISECONDS).build() }

    fun gql(body: String, client: OkHttpClient = Http.client): String {
        val req = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", CLIENT_ID)
            .header("User-Agent", Http.DESKTOP_UA)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Twitch GQL HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    /** Returns (token value, signature). */
    fun accessToken(
        login: String?,
        vodId: String?,
        playerType: String,
        platform: String,
        client: OkHttpClient = Http.client,
    ): Pair<String, String> {
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
        val json = Http.json.parseToJsonElement(gql(body.toString(), client))
        val token = if (vodId == null) json["data"]["streamPlaybackAccessToken"] else json["data"]["videoPlaybackAccessToken"]
        val value = token["value"].string ?: error("No Twitch access token")
        val sig = token["signature"].string ?: error("No Twitch access token")
        return value to sig
    }

    fun liveMasterUrl(login: String, playerType: String, client: OkHttpClient = Http.client): String {
        val platform = if (playerType == "autoplay") "android" else "web"
        val (value, sig) = accessToken(login, null, playerType, platform, client)
        return "https://usher.ttvnw.net/api/v2/channel/hls/$login.m3u8".toHttpUrl().newBuilder()
            .addQueryParameter("sig", sig).addQueryParameter("token", value)
            .addQueryParameter("allow_source", "true").addQueryParameter("allow_audio_only", "true")
            .addQueryParameter("fast_bread", "true").addQueryParameter("p", Random.nextInt(9_999_999).toString())
            .addQueryParameter("platform", "web").addQueryParameter("player_backend", "mediaplayer")
            .addQueryParameter("playlist_include_framerate", "true").addQueryParameter("reassignments_supported", "true")
            .addQueryParameter("supported_codecs", "h264").build().toString()
    }

    /** Fetches a live master playlist for a player type. */
    fun liveMaster(login: String, playerType: String, client: OkHttpClient = Http.client): TwitchMaster {
        val url = liveMasterUrl(login, playerType, client)
        val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) throw TwitchOfflineException(login)
            if (!resp.isSuccessful) error("usher HTTP ${resp.code}")
            return TwitchMaster(playerType, url, resp.body!!.string())
        }
    }

    fun fetch(url: String, client: OkHttpClient = Http.client): String {
        val req = Request.Builder().url(url).header("User-Agent", Http.DESKTOP_UA).build()
        client.newCall(req).execute().use { resp ->
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

/**
 * Twitch's stitched ads, recognised by what marks them rather than by what doesn't: segments inside a
 * `twitch-stitched-ad` date range or a non-live `twitch-stream-source`, or titled after an ad source
 * (`Amazon|…`, `DCM|…`). A segment titled `live` is never an ad, and anything unrecognised plays.
 */
object TwitchAds {
    private val START_DATE = Regex("""START-DATE="([^"]+)"""")
    private val DURATION = Regex("""[:,]DURATION=([\d.]+)""")
    private val STREAM_SOURCE = Regex("""X-TV-TWITCH-STREAM-SOURCE="([^"]*)"""")
    private val MEDIA_SEQUENCE = Regex("""^#EXT-X-MEDIA-SEQUENCE:(\d+)""", RegexOption.MULTILINE)

    /** Segment times and date ranges are rounded separately. */
    private const val SLACK_MS = 50

    fun hasAds(m3u8: String): Boolean = adSegments(m3u8).any { it }

    /** The newest segment is an ad: a break is on air right now. */
    fun inBreak(m3u8: String): Boolean = adSegments(m3u8).lastOrNull() == true

    fun mediaSequence(m3u8: String): Long? = MEDIA_SEQUENCE.find(m3u8)?.groupValues?.get(1)?.toLongOrNull()

    /** Whether each segment, in playlist order, is an ad. */
    fun adSegments(m3u8: String): List<Boolean> {
        val lines = m3u8.lineSequence().map { it.trimEnd('\r') }.toList()
        val adRanges = ArrayList<LongRange>()
        val sources = ArrayList<Pair<Long, Boolean>>() // start, is live
        val adTitles = HashSet<String>()
        for (l in lines) {
            if (!l.startsWith("#EXT-X-DATERANGE")) continue
            val start = START_DATE.find(l)?.groupValues?.get(1)?.let(::parseDate) ?: continue
            if ("twitch-stitched-ad" in l || "ID=\"stitched-ad" in l) {
                val seconds = DURATION.find(l)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                adRanges += start until start + (seconds * 1000).toLong()
            } else if ("twitch-stream-source" in l) {
                val source = STREAM_SOURCE.find(l)?.groupValues?.get(1) ?: continue
                sources += start to (source == "live")
                if (source.isNotBlank() && source != "live") adTitles += source
            }
        }
        sources.sortBy { it.first }

        val out = ArrayList<Boolean>()
        var time: Long? = null
        var title = ""
        var durationMs = 0L
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> time = parseDate(l.substringAfter(':'))
                l.startsWith("#EXTINF:") -> {
                    val info = l.substringAfter(':')
                    durationMs = ((info.substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    title = info.substringAfter(',', "").trim()
                }
                l.isNotBlank() && !l.startsWith("#") -> {
                    out += isAd(title, time, adRanges, sources, adTitles)
                    time = time?.plus(durationMs)
                    title = ""
                    durationMs = 0
                }
            }
        }
        return out
    }

    private fun isAd(title: String, time: Long?, adRanges: List<LongRange>, sources: List<Pair<Long, Boolean>>, adTitles: Set<String>): Boolean {
        if (title == "live") return false
        if (title.startsWith("Amazon|") || title in adTitles) return true
        val at = time ?: return false
        if (adRanges.any { at >= it.first - SLACK_MS && at < it.last - SLACK_MS }) return true
        return sources.lastOrNull { it.first <= at + SLACK_MS }?.second == false
    }

    private fun parseDate(text: String): Long? =
        runCatching { OffsetDateTime.parse(text.trim()).toInstant().toEpochMilli() }.getOrNull()

    /**
     * Marks ad segments as EXT-X-GAP, which the player skips without downloading. The timeline and
     * MEDIA-SEQUENCE stay continuous, so the player waits out the break on its last frame instead of
     * finding itself behind the live window afterwards. Also drops prefetch hints, ad date ranges and
     * a preroll playlist's EXT-X-START, which would pin playback to the start of the playlist.
     */
    fun strip(m3u8: String): String {
        val ads = adSegments(m3u8)
        val out = ArrayList<String>()
        var segment = 0
        for (raw in m3u8.lineSequence()) {
            val l = raw.trimEnd('\r')
            if (l.startsWith("#EXT-X-TWITCH-PREFETCH") || l.startsWith("#EXT-X-START")) continue
            if (l.startsWith("#EXT-X-DATERANGE") && (l.contains("stitched") || l.contains("X-TV-TWITCH-AD-"))) continue
            if (l.isNotBlank() && !l.startsWith("#")) {
                if (ads.getOrElse(segment) { false }) out += "#EXT-X-GAP"
                segment++
            }
            out += l
        }
        while (out.lastOrNull()?.isBlank() == true) out.removeAt(out.lastIndex)
        return out.joinToString("\n") + "\n"
    }
}

/**
 * Runs on ExoPlayer's loader threads. During an ad break, swaps in the same-resolution playlist from
 * an ad-free backup player type (live sessions share Twitch's sequence numbers, so the swap is
 * seamless); if no backup is clean, the ad segments become gaps and the player waits out the break.
 */
class TwitchAdFilter(private val login: String) : PlaylistRewriter {
    private val variantInfo = ConcurrentHashMap<String, TwitchVariant>()
    private val backupMasters = ConcurrentHashMap<String, Pair<Long, TwitchMaster>>()
    @Volatile private var lastGoodType: String? = null
    @Volatile private var backupsMismatchUntil = 0L

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
            AdBreakMonitor.set(this, false)
            return TwitchAds.strip(playlist)
        }
        val swapped = backupPlaylist(variantInfo[uri.toString()], TwitchAds.mediaSequence(playlist))
        if (swapped != null) {
            AdBreakMonitor.set(this, false)
            return TwitchAds.strip(swapped)
        }
        AdBreakMonitor.set(this, TwitchAds.inBreak(playlist))
        return TwitchAds.strip(playlist)
    }

    private sealed interface Backup {
        class Clean(val type: String, val playlist: String) : Backup
        /** Clean, but numbered from another starting point (one of the two sessions began with a preroll). */
        data object Mismatch : Backup
        data object Unusable : Backup
    }

    private fun backupPlaylist(wanted: TwitchVariant?, sequence: Long?): String? {
        if (sequence == null || System.currentTimeMillis() < backupsMismatchUntil) return null
        // The type that worked last time on its own, then the others at once
        val first = lastGoodType
        if (first != null) (tryBackup(first, wanted, sequence) as? Backup.Clean)?.let { return it.playlist }
        val others = BACKUP_TYPES.filter { it != first }
        val results = runBlocking(Dispatchers.IO) { others.map { async { tryBackup(it, wanted, sequence) } }.awaitAll() }
        val clean = results.filterIsInstance<Backup.Clean>().firstOrNull()
        if (clean == null) {
            // Every clean backup disagrees with this session's numbering: it began with a preroll, and
            // swapping would derail the player. Wait the breaks out instead of asking again each reload.
            if (results.any { it == Backup.Mismatch }) backupsMismatchUntil = System.currentTimeMillis() + 60_000
            return null
        }
        lastGoodType = clean.type
        return clean.playlist
    }

    private fun tryBackup(type: String, wanted: TwitchVariant?, sequence: Long): Backup {
        val media = try {
            backupMedia(type, wanted)
        } catch (e: Exception) {
            Log.d(TAG, "backup $type failed: ${e.message}")
            null
        } ?: return Backup.Unusable
        if (TwitchAds.hasAds(media)) return Backup.Unusable
        val theirs = TwitchAds.mediaSequence(media) ?: return Backup.Unusable
        return if (abs(theirs - sequence) > MAX_SEQUENCE_DRIFT) Backup.Mismatch else Backup.Clean(type, media)
    }

    private fun backupMedia(type: String, wanted: TwitchVariant?): String? {
        val now = System.currentTimeMillis()
        val cached = backupMasters[type]?.takeIf { now - it.first < MASTER_CACHE_MS }?.second
        val master = cached ?: Twitch.liveMaster(login, type, Twitch.quickClient).also { backupMasters[type] = now to it }
        val variants = master.variants
        val pick = if (wanted == null) {
            variants.maxByOrNull { it.pixels }
        } else {
            variants.firstOrNull { it.width == wanted.width && it.height == wanted.height && it.frameRate == wanted.frameRate }
                ?: variants.minByOrNull { abs(it.pixels - wanted.pixels) }
        } ?: return null
        return Twitch.fetch(pick.url, Twitch.quickClient)
    }

    companion object {
        private const val TAG = "TwitchAdFilter"
        val BACKUP_TYPES = listOf("embed", "popout", "autoplay")

        /** Sessions started at the same time are a segment or two apart; a preroll session starts at 0. */
        private const val MAX_SEQUENCE_DRIFT = 30

        /** An already-running session; a fresh one may begin with a preroll of its own. */
        private const val MASTER_CACHE_MS = 5 * 60_000L
    }
}
