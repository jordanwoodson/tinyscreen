package io.github.jordanwoodson.tinyscreen.stream

import android.content.Context
import android.util.Log
import io.github.jordanwoodson.tinyscreen.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.random.Random

/**
 * Client for `wss://live.destiny.gg`, the socket that feeds bigscreen: Destiny's streams,
 * hosting, the top-embeds list, banned embeds and the latest VOD. The last value of each
 * message type is cached so the UI has data before the socket connects.
 */
class LiveRepository(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("live_cache", Context.MODE_PRIVATE)
    private val json = Http.json

    private val _streamInfo = MutableStateFlow(StreamInfo())
    val streamInfo: StateFlow<StreamInfo> = _streamInfo.asStateFlow()

    private val _hosting = MutableStateFlow<Hosting?>(null)
    val hosting: StateFlow<Hosting?> = _hosting.asStateFlow()

    private val _embeds = MutableStateFlow<List<TopEmbed>>(emptyList())
    val embeds: StateFlow<List<TopEmbed>> = _embeds.asStateFlow()

    private val _banned = MutableStateFlow<List<BannedEmbed>>(emptyList())
    val banned: StateFlow<List<BannedEmbed>> = _banned.asStateFlow()

    private val _latestVod = MutableStateFlow<LatestVod?>(null)
    val latestVod: StateFlow<LatestVod?> = _latestVod.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    private var canReportWatching = false
    private var watching: Embed? = null
    private var started = false

    init {
        if (prefs.getString("cacheVersion", null) == CACHE_VERSION) {
            listOf(T_STREAM_INFO, T_HOSTING, T_EMBEDS, T_BANNED, T_VODS).forEach { type ->
                prefs.getString(type, null)?.let { raw -> runCatching { apply(type, json.parseToJsonElement(raw)) } }
            }
        } else {
            prefs.edit().clear().putString("cacheVersion", CACHE_VERSION).apply()
        }
    }

    fun start() {
        if (started) return
        started = true
        connect()
    }

    fun stop() {
        started = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    /** Called with the bigscreen's current content; the server tallies these into the embeds list. */
    fun reportWatching(embed: Embed?) {
        watching = embed
        sendWatching()
    }

    private fun sendWatching() {
        if (!canReportWatching) return
        val e = watching
        val payload = buildJsonObject {
            put("type", "watching")
            if (e == null) {
                put("data", JsonNull)
            } else {
                put("data", buildJsonObject { put("platform", e.platform); put("id", e.id) })
            }
        }
        socket?.send(payload.toString())
    }

    private fun connect() {
        val request = Request.Builder()
            .url(URL)
            .header("Origin", "https://www.destiny.gg")
            .header("User-Agent", Http.MOBILE_UA)
            .build()
        socket = Http.client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                _connected.value = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handle(text) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnect(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "live socket failed: ${t.message}")
                onDisconnect(webSocket)
            }
        })
    }

    private fun onDisconnect(webSocket: WebSocket) {
        if (socket !== webSocket) return
        _connected.value = false
        canReportWatching = false
        socket = null
        if (!started) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            attempts++
            delay((1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000) + Random.nextLong(0, 2_000))
            if (started) connect()
        }
    }

    private fun handle(text: String) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = message["type"]?.jsonPrimitive?.content ?: return
        if (type == "me") {
            canReportWatching = true
            sendWatching()
            return
        }
        val data = message["data"] ?: return
        if (apply(type, data)) {
            prefs.edit().putString(type, data.toString()).apply()
        }
    }

    private fun apply(type: String, data: JsonElement): Boolean = runCatching {
        when (type) {
            T_STREAM_INFO -> _streamInfo.value = json.decodeFromJsonElement(StreamInfo.serializer(), data)
            T_HOSTING -> _hosting.value = (data as? JsonObject)?.let { json.decodeFromJsonElement(Hosting.serializer(), it) }
            T_EMBEDS -> _embeds.value = json.decodeFromJsonElement(ListSerializer(TopEmbed.serializer()), data)
                .sortedByDescending { it.count }
            T_BANNED -> _banned.value = json.decodeFromJsonElement(ListSerializer(BannedEmbed.serializer()), data)
            T_VODS -> _latestVod.value = json.decodeFromJsonElement(ListSerializer(LatestVod.serializer()), data).firstOrNull()
            else -> return@runCatching false
        }
        true
    }.getOrElse {
        Log.w(TAG, "bad $type payload", it)
        false
    }

    fun bannedReason(platform: String, id: String): String? {
        val hit = _banned.value.firstOrNull { b ->
            b.platform == platform && if (platform == "youtube") b.name == id else b.name.equals(id, ignoreCase = true)
        }
        return hit?.let { it.reason ?: "" }
    }

    companion object {
        private const val TAG = "LiveRepository"
        private const val URL = "wss://live.destiny.gg"
        private const val CACHE_VERSION = "5"
        const val T_STREAM_INFO = "dggApi:streamInfo"
        const val T_HOSTING = "dggApi:hosting"
        const val T_EMBEDS = "dggApi:embeds"
        const val T_BANNED = "dggApi:bannedEmbeds"
        const val T_VODS = "dggApi:youtubeVods"
    }
}
