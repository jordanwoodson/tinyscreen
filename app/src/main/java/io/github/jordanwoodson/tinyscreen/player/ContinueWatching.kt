package io.github.jordanwoodson.tinyscreen.player

import android.content.Context
import androidx.core.content.edit
import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class PlaybackCheckpoint(val positionMs: Long?, val playWhenReady: Boolean)

@Serializable
data class RecentVod(val platform: String, val id: String, val title: String, val positionMs: Long) {
    val embed get() = Embed(platform, id, (positionMs / 1000).toInt())
}

/** Only stable provider IDs are stored. Playback URLs can expire and may contain credentials. */
class ContinueWatching(context: Context) {
    private val prefs = context.getSharedPreferences("recent_vods", Context.MODE_PRIVATE)
    private val items = MutableStateFlow(runCatching {
        Json.decodeFromString<List<RecentVod>>(prefs.getString("items", null) ?: "[]")
    }.getOrDefault(emptyList()))
    val recent = items.asStateFlow()

    fun record(embed: Embed, title: String, positionMs: Long, ended: Boolean) {
        if (!ended && positionMs <= 0) return
        val others = items.value.filterNot { it.platform == embed.platform && it.id == embed.id }
        val resolvedTitle = if (title == embed.displayId) {
            items.value.firstOrNull { it.platform == embed.platform && it.id == embed.id }?.title ?: title
        } else title
        val next = if (ended) others else listOf(RecentVod(embed.platform, embed.id, resolvedTitle, positionMs)) + others
        save(next.take(12))
    }

    fun clear() = save(emptyList())

    private fun save(next: List<RecentVod>) {
        if (next == items.value) return
        items.value = next
        prefs.edit { putString("items", Json.encodeToString(next)) }
    }
}
