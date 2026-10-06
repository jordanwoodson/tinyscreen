package io.github.jordanwoodson.tinyscreen.chat

import android.content.Context
import android.util.Log
import io.github.jordanwoodson.tinyscreen.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import java.io.File

data class EmoteSet(
    val byPrefix: Map<String, Emote> = emptyMap(),
) {
    val all: List<Emote> get() = byPrefix.values.toList()
}

/** Emotes and flairs from r2cdn.destiny.gg, cached on disk for instant startup. */
class ChatAssets(context: Context) {
    private val dir = File(context.filesDir, "chat-assets").apply { mkdirs() }

    private val _emotes = MutableStateFlow(EmoteSet())
    val emotes: StateFlow<EmoteSet> = _emotes.asStateFlow()

    private val _flairs = MutableStateFlow<Map<String, Flair>>(emptyMap())
    val flairs: StateFlow<Map<String, Flair>> = _flairs.asStateFlow()

    private var cacheKey: String = (System.currentTimeMillis() / 3_600_000).toString()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        runCatching { buildEmotes(read("emotes.json"), read("emotes.css")) }.onFailure { Log.w(TAG, "no cached emotes: ${it.message}") }
        runCatching { buildFlairs(read("flairs.json")) }.onFailure { Log.w(TAG, "no cached flairs: ${it.message}") }
    }

    /** Fetches emotes and flairs. Returns whether both loaded, so a failure can be retried. */
    suspend fun refresh(newKey: String? = null): Boolean {
        if (newKey != null) cacheKey = newKey
        return coroutineScope {
            val e = async { runCatching { Http.getString("$CDN/emotes/emotes.json?_=$cacheKey") } }
            val c = async { runCatching { Http.getString("$CDN/emotes/emotes.css?_=$cacheKey") } }
            val f = async { runCatching { Http.getString("$CDN/flairs/flairs.json?_=$cacheKey") } }
            val (emotesJson, emotesCss, flairsJson) = Triple(e.await(), c.await(), f.await())
            withContext(Dispatchers.IO) {
                val emotesOk = runCatching {
                    buildEmotes(emotesJson.getOrThrow(), emotesCss.getOrThrow())
                    write("emotes.json", emotesJson.getOrThrow())
                    write("emotes.css", emotesCss.getOrThrow())
                }.onFailure { Log.w(TAG, "emote refresh failed", it) }.isSuccess
                val flairsOk = runCatching {
                    buildFlairs(flairsJson.getOrThrow())
                    write("flairs.json", flairsJson.getOrThrow())
                }.onFailure { Log.w(TAG, "flair refresh failed", it) }.isSuccess
                emotesOk && flairsOk
            }
        }
    }

    private fun read(name: String): String = File(dir, name).readText()
    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    private fun buildEmotes(emotesJson: String, emotesCss: String) {
        val emotes = parseEmotes(emotesJson, emotesCss)
        if (emotes.isEmpty()) error("no emotes in emotes.json")
        _emotes.value = EmoteSet(emotes.associateBy { it.prefix })
    }

    private fun buildFlairs(flairsJson: String) {
        val flairs = parseFlairs(flairsJson)
        if (flairs.isEmpty()) error("no flairs in flairs.json")
        _flairs.value = flairs
    }

    companion object {
        private const val TAG = "ChatAssets"
        const val CDN = "https://r2cdn.destiny.gg"
        private val json = Http.json

        /** Emotes with their CSS box; a malformed entry is skipped rather than losing them all. */
        fun parseEmotes(emotesJson: String, emotesCss: String): List<Emote> {
            val css = EmoteCss.parse(emotesCss)
            return entries(emotesJson, EmoteJson.serializer()).mapNotNull { e ->
                val img = e.image.firstOrNull() ?: return@mapNotNull null
                if (img.url.isBlank() || img.width <= 0 || img.height <= 0) return@mapNotNull null
                val style = css[e.prefix]
                // The CSS box can be narrower than the image (sprite sheets, dggL's single heart)
                val width = style?.width?.takeIf { it in 1 until img.width } ?: img.width
                Emote(
                    prefix = e.prefix,
                    url = img.url,
                    mime = img.mime ?: "image/png",
                    width = width,
                    height = style?.height ?: img.height,
                    sheetWidth = img.width,
                    minimumSubTier = e.minimumSubTier,
                    twitch = e.twitch,
                    creator = e.creator?.takeIf { it.isNotBlank() },
                    sprite = style?.sprite?.takeIf { width < img.width },
                    restOffset = if (width < img.width) style?.restOffset ?: 0 else 0,
                )
            }
        }

        fun parseFlairs(flairsJson: String): Map<String, Flair> = entries(flairsJson, FlairJson.serializer()).associate { f ->
            val img = f.image.firstOrNull()
            f.name to Flair(
                name = f.name,
                label = f.label,
                hidden = f.hidden,
                priority = f.priority,
                color = f.color?.takeIf { it.startsWith("#") }?.let { parseColor(it) },
                rainbow = f.rainbowColor,
                imageUrl = img?.url?.takeIf { it.isNotBlank() },
                width = img?.width ?: 0,
                height = img?.height ?: 0,
            )
        }

        private fun <T> entries(text: String, serializer: KSerializer<T>): List<T> {
            val array = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
            return array.mapNotNull { el ->
                runCatching { json.decodeFromJsonElement(serializer, el) }
                    .onFailure { Log.w(TAG, "skipping a malformed CDN entry: ${it.message}") }
                    .getOrNull()
            }
        }

        fun parseColor(hex: String): Long? {
            val h = hex.removePrefix("#")
            val v = h.toLongOrNull(16) ?: return null
            return when (h.length) {
                6 -> 0xFF000000L or v
                8 -> v
                3 -> {
                    val r = (v shr 8) and 0xF; val g = (v shr 4) and 0xF; val b = v and 0xF
                    0xFF000000L or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                }
                else -> null
            }
        }
    }
}
