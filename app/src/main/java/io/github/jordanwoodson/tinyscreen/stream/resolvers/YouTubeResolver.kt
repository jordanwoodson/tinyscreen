package io.github.jordanwoodson.tinyscreen.stream.resolvers

import android.util.Log
import androidx.media3.common.C
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.HttpException
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `#youtube/VIDEO_ID` — InnerTube app clients that still hand out playable streams without a PO
 * token, tried in turn so that YouTube shutting one down doesn't stop playback: visionOS (HLS, live
 * and VOD), iOS (HLS, VODs only) and Android VR (direct files, VODs only). When none of them works,
 * the bigscreen falls back to the web embed, as it does for members-only, age-restricted and kids
 * videos. TV clients would need YouTube's player JavaScript, so they aren't in the chain.
 */
class YouTubeResolver : StreamResolver {
    override val platforms = setOf("youtube")

    @Volatile private var visitorData: String? = null

    private class Client(
        val name: String,
        val version: String,
        val userAgent: String,
        /** X-YouTube-Client-Name; visionOS works without. */
        val id: Int?,
        /** Its live HLS plays; Android VR's needs a PO token for every segment. */
        val live: Boolean,
        /** Falls back to direct video and audio files when there's no HLS. */
        val files: Boolean = false,
        val device: JsonObjectBuilder.() -> Unit,
    )

    override suspend fun resolve(embed: Embed): ResolvedStream = withContext(Dispatchers.IO) {
        val vd = visitorData ?: fetchVisitorData().also { visitorData = it }
        var firstError: Exception? = null
        for (client in CLIENTS) {
            try {
                return@withContext resolveWith(client, embed, vd)
            } catch (e: Exception) {
                Log.d(TAG, "${client.name} can't play ${embed.id}: ${e.message}")
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: IllegalStateException("YouTube didn't answer")
    }

    private fun resolveWith(client: Client, embed: Embed, vd: String?): ResolvedStream {
        val body = buildJsonObject {
            put("context", context(client, vd))
            put("videoId", embed.id)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val response = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, client, vd)
        val json = Http.json.parseToJsonElement(response)
        val status = json["playabilityStatus"]["status"].string
        if (status != "OK") {
            if (status == "LOGIN_REQUIRED") visitorData = null
            error(json["playabilityStatus"]["reason"].string ?: "YouTube says: $status")
        }
        val details = json["videoDetails"]
        val isLive = details["isLive"].string == "true" || details["isLiveContent"].string == "true" && details["lengthSeconds"].string == "0"
        if (isLive && !client.live) error("${client.name} can't play live streams")
        val streaming = json["streamingData"]
        val hls = streaming["hlsManifestUrl"].string
        val files = if (hls == null && client.files) files(streaming) else null
        if (hls == null && files == null) error("No HLS manifest for this video")
        return ResolvedStream(
            url = hls ?: files!!.first,
            kind = if (hls != null) StreamKind.Hls else StreamKind.Progressive,
            isLive = isLive,
            title = details["title"].string,
            subtitle = (details["author"].string ?: "YouTube") + " · YouTube",
            artworkUrl = "https://i.ytimg.com/vi/${embed.id}/hqdefault.jpg",
            audioUrl = files?.second,
            startPositionMs = embed.startSeconds?.let { it * 1000L } ?: C.TIME_UNSET,
            liveTargetOffsetMs = 10_000,
            refresh = { visitorData = null; resolve(embed) },
        )
    }

    /** The best H.264 video up to 1080p and the best AAC audio, or the single muxed 360p file. */
    private fun files(streaming: JsonElement?): Pair<String, String?>? {
        val adaptive = streaming["adaptiveFormats"].array.orEmpty().filter { it["url"].string != null }
        val video = adaptive
            .filter { it["mimeType"].string.orEmpty().let { m -> m.startsWith("video/mp4") && "avc1" in m } && (it["height"].long ?: 0) <= 1080 }
            .maxByOrNull { (it["height"].long ?: 0) * 1_000_000_000 + (it["bitrate"].long ?: 0) }
        val audio = adaptive
            .filter { it["mimeType"].string.orEmpty().startsWith("audio/mp4") }
            .maxByOrNull { it["bitrate"].long ?: 0 }
        if (video != null && audio != null) return video["url"].string!! to audio["url"].string
        val muxed = streaming["formats"].array.orEmpty().filter { it["url"].string != null }.maxByOrNull { it["height"].long ?: 0 }
        return muxed?.let { it["url"].string!! to null }
    }

    private fun context(client: Client, vd: String?): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", client.name)
            put("clientVersion", client.version)
            client.device(this)
            put("hl", "en")
            put("gl", "US")
            vd?.let { put("visitorData", it) }
        }
    }

    private fun fetchVisitorData(): String? = runCatching {
        val body = buildJsonObject { put("context", context(VISIONOS, null)) }
        Http.json.parseToJsonElement(post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body, VISIONOS, null))["responseContext"]["visitorData"].string
    }.getOrNull()

    private fun post(url: String, body: JsonObject, client: Client, vd: String?): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", client.userAgent)
            .header("Content-Type", "application/json")
            .apply {
                vd?.let { header("X-Goog-Visitor-Id", it) }
                client.id?.let {
                    header("X-YouTube-Client-Name", it.toString())
                    header("X-YouTube-Client-Version", client.version)
                }
            }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, url)
            return resp.body!!.string()
        }
    }

    private companion object {
        const val TAG = "YouTubeResolver"

        val VISIONOS = Client(
            name = "VISIONOS",
            version = "1.02",
            userAgent = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)",
            id = null,
            live = true,
        ) {
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice14,1")
            put("osName", "visionOS")
            put("osVersion", "25.6.0.23O471")
        }

        val IOS = Client(
            name = "IOS",
            version = "20.10.4",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            id = 5,
            live = false,
        ) {
            put("deviceMake", "Apple")
            put("deviceModel", "iPhone16,2")
            put("osName", "iPhone")
            put("osVersion", "18.3.2.22D82")
        }

        val ANDROID_VR = Client(
            name = "ANDROID_VR",
            version = "1.65.10",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            id = 28,
            live = false,
            files = true,
        ) {
            put("deviceMake", "Oculus")
            put("deviceModel", "Quest 3")
            put("androidSdkVersion", 32)
            put("osName", "Android")
            put("osVersion", "12L")
        }

        val CLIENTS = listOf(VISIONOS, IOS, ANDROID_VR)
    }
}
