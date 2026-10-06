package io.github.jordanwoodson.tinyscreen.stream.resolvers

import androidx.media3.common.C
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.net.HttpException
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `#youtube/VIDEO_ID` — InnerTube's visionOS client still hands out an HLS manifest (live and VOD)
 * without a PO token. Members-only, age-restricted and kids videos fall back to the web embed.
 */
class YouTubeResolver : StreamResolver {
    override val platforms = setOf("youtube")

    @Volatile private var visitorData: String? = null

    override suspend fun resolve(embed: Embed): ResolvedStream = withContext(Dispatchers.IO) {
        val vd = visitorData ?: fetchVisitorData().also { visitorData = it }
        val body = buildJsonObject {
            put("context", context(vd))
            put("videoId", embed.id)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val response = post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", body, vd)
        val json = Http.json.parseToJsonElement(response)
        val status = json["playabilityStatus"]["status"].string
        if (status != "OK") {
            if (status == "LOGIN_REQUIRED") visitorData = null
            error(json["playabilityStatus"]["reason"].string ?: "YouTube says: $status")
        }
        val details = json["videoDetails"]
        val isLive = details["isLive"].string == "true" || details["isLiveContent"].string == "true" && details["lengthSeconds"].string == "0"
        val hls = json["streamingData"]["hlsManifestUrl"].string ?: error("No HLS manifest for this video")
        ResolvedStream(
            url = hls,
            kind = StreamKind.Hls,
            isLive = isLive,
            title = details["title"].string,
            subtitle = (details["author"].string ?: "YouTube") + " · YouTube",
            artworkUrl = "https://i.ytimg.com/vi/${embed.id}/hqdefault.jpg",
            startPositionMs = embed.startSeconds?.let { it * 1000L } ?: C.TIME_UNSET,
            liveTargetOffsetMs = 10_000,
            refresh = { visitorData = null; resolve(embed) },
        )
    }

    private fun context(vd: String?): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "VISIONOS")
            put("clientVersion", "1.02")
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice14,1")
            put("osName", "visionOS")
            put("osVersion", "25.6.0.23O471")
            put("hl", "en")
            put("gl", "US")
            vd?.let { put("visitorData", it) }
        }
    }

    private fun fetchVisitorData(): String? = runCatching {
        val body = buildJsonObject { put("context", context(null)) }
        Http.json.parseToJsonElement(post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body, null))["responseContext"]["visitorData"].string
    }.getOrNull()

    private fun post(url: String, body: JsonObject, vd: String?): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .apply { vd?.let { header("X-Goog-Visitor-Id", it) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, url)
            return resp.body!!.string()
        }
    }

    companion object {
        const val UA = "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; US)"
    }
}
