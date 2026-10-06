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
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import kotlin.math.roundToInt

data class EmoteSet(
    val byPrefix: Map<String, Emote> = emptyMap(),
) {
    val all: List<Emote> get() = byPrefix.values.toList()
}

/** Emotes and flairs from r2cdn.destiny.gg, cached on disk for instant startup. */
class ChatAssets(context: Context) {
    private val dir = File(context.filesDir, "chat-assets").apply { mkdirs() }
    private val json = Http.json

    private val _emotes = MutableStateFlow(EmoteSet())
    val emotes: StateFlow<EmoteSet> = _emotes.asStateFlow()

    private val _flairs = MutableStateFlow<Map<String, Flair>>(emptyMap())
    val flairs: StateFlow<Map<String, Flair>> = _flairs.asStateFlow()

    private var cacheKey: String = (System.currentTimeMillis() / 3_600_000).toString()

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        runCatching { build(read("emotes.json"), read("emotes.css"), read("flairs.json")) }
            .onFailure { Log.w(TAG, "no cached assets: ${it.message}") }
    }

    suspend fun refresh(newKey: String? = null) {
        if (newKey != null) cacheKey = newKey
        runCatching {
            coroutineScope {
                val e = async { Http.getString("$CDN/emotes/emotes.json?_=$cacheKey") }
                val c = async { Http.getString("$CDN/emotes/emotes.css?_=$cacheKey") }
                val f = async { Http.getString("$CDN/flairs/flairs.json?_=$cacheKey") }
                val (emotesJson, emotesCss, flairsJson) = Triple(e.await(), c.await(), f.await())
                withContext(Dispatchers.IO) {
                    build(emotesJson, emotesCss, flairsJson)
                    write("emotes.json", emotesJson)
                    write("emotes.css", emotesCss)
                    write("flairs.json", flairsJson)
                }
            }
        }.onFailure { Log.w(TAG, "asset refresh failed", it) }
    }

    private fun read(name: String): String = File(dir, name).readText()
    private fun write(name: String, text: String) = File(dir, name).writeText(text)

    private fun build(emotesJson: String, emotesCss: String, flairsJson: String) {
        val css = parseEmoteCss(emotesCss)
        val emotes = json.decodeFromString(ListSerializer(EmoteJson.serializer()), emotesJson).mapNotNull { e ->
            val img = e.image.firstOrNull() ?: return@mapNotNull null
            val rule = css[e.prefix]
            val frameWidth = rule?.width ?: img.width
            val height = rule?.height ?: img.height
            val sprite = if (rule != null && frameWidth in 1 until img.width && img.width.toFloat() / frameWidth >= 1.5f) {
                val frames = (img.width.toFloat() / frameWidth).roundToInt().coerceAtLeast(1)
                SpriteAnimation(
                    frameWidth = frameWidth,
                    frames = frames,
                    durationMs = rule.durationMs ?: (frames * 50),
                    iterations = rule.iterations ?: 1,
                    holdLastFrame = rule.holdLastFrame,
                )
            } else {
                null
            }
            Emote(
                prefix = e.prefix,
                url = img.url,
                mime = img.mime ?: "image/png",
                width = if (sprite != null) frameWidth else img.width,
                height = height,
                sheetWidth = img.width,
                minimumSubTier = e.minimumSubTier,
                twitch = e.twitch,
                creator = e.creator?.takeIf { it.isNotBlank() },
                sprite = sprite,
            )
        }
        _emotes.value = EmoteSet(emotes.associateBy { it.prefix })

        _flairs.value = json.decodeFromString(ListSerializer(FlairJson.serializer()), flairsJson).associate { f ->
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
    }

    private data class CssRule(
        var width: Int? = null,
        var height: Int? = null,
        var durationMs: Int? = null,
        var iterations: Int? = null,
        var holdLastFrame: Boolean = false,
    )

    private fun parseEmoteCss(css: String): Map<String, CssRule> {
        val rules = HashMap<String, CssRule>()
        val block = Regex("""(^|[\s,}])\.emote\.([A-Za-z0-9_]+)\s*\{([^{}]*)\}""")
        for (m in block.findAll(css)) {
            val prefix = m.groupValues[2]
            // ".msg-chat .emote.X" only shifts the emote; the size lives on the bare selector
            val start = m.range.first
            val lineStart = css.lastIndexOf('\n', start).coerceAtLeast(0)
            if (css.substring(lineStart, start + 1).contains(".msg-chat")) continue
            val body = m.groupValues[3]
            val rule = rules.getOrPut(prefix) { CssRule() }
            Regex("""(?<![-\w])width:\s*([\d.]+)px""").find(body)?.let { rule.width = it.groupValues[1].toFloat().roundToInt() }
            Regex("""(?<![-\w])height:\s*([\d.]+)px""").find(body)?.let { rule.height = it.groupValues[1].toFloat().roundToInt() }
            Regex("""(?<![-\w])animation:\s*([^;]+)""").find(body)?.let { parseAnimation(it.groupValues[1], rule) }
        }
        return rules
    }

    private fun parseAnimation(value: String, rule: CssRule) {
        if (value.trim() == "none") return
        val tokens = value.trim().split(Regex("\\s+"))
        var duration: Int? = null
        var iterations: Int? = null
        tokens.drop(1).forEach { t ->
            when {
                t.startsWith("steps(") -> Unit
                t.endsWith("ms") && t.dropLast(2).toFloatOrNull() != null -> if (duration == null) duration = t.dropLast(2).toFloat().roundToInt()
                t.endsWith("s") && t.dropLast(1).toFloatOrNull() != null -> if (duration == null) duration = (t.dropLast(1).toFloat() * 1000).roundToInt()
                t == "infinite" -> iterations = 0
                t.toIntOrNull() != null -> iterations = t.toInt()
                t == "forwards" || t == "both" -> rule.holdLastFrame = true
            }
        }
        rule.durationMs = duration ?: rule.durationMs
        rule.iterations = iterations ?: rule.iterations ?: 1
    }

    companion object {
        private const val TAG = "ChatAssets"
        const val CDN = "https://r2cdn.destiny.gg"

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
