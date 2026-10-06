package io.github.jordanwoodson.tinyscreen.stream

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder

/** `dggApi:streamInfo` — Destiny's own streams, keyed twitch, youtube, facebook, rumble, kick. */
@Serializable
data class StreamInfo(val streams: Map<String, StreamEntry?> = emptyMap()) {
    /** Live streams in server key order (which decides the fallback platform). */
    val live: List<StreamEntry> get() = streams.entries.mapNotNull { (k, v) -> v?.takeIf { it.live }?.copy(platform = v.platform ?: k) }
}

@Serializable
data class StreamEntry(
    val live: Boolean = false,
    val game: String? = null,
    val preview: String? = null,
    @SerialName("status_text") val statusText: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val duration: Long? = null,
    val viewers: Int? = null,
    val id: String? = null,
    @SerialName("chat_url") val chatUrl: String? = null,
    val platform: String? = null,
    val type: String? = null,
)

/** `dggApi:hosting` — who Destiny is hosting while offline. */
@Serializable
data class Hosting(
    val id: String? = null,
    val platform: String? = null,
    val displayName: String? = null,
    val title: String? = null,
    val url: String? = null,
    val preview: String? = null,
    val embedUrl: String? = null,
    val live: Boolean = false,
    val viewers: Int? = null,
    val streamStartedAt: String? = null,
    val hostStartTime: String? = null,
)

/** `dggApi:embeds` — what chat is watching, most-watched first. */
@Serializable
data class TopEmbed(
    val platform: String,
    val id: String,
    val count: Int = 0,
    val mediaItem: EmbedMediaItem? = null,
) {
    val metadata: EmbedMetadata? get() = mediaItem?.metadata
    val displayName: String get() = metadata?.displayName ?: mediaItem?.identifier?.mediaId ?: id
    val embed: Embed get() = Embed(platform, mediaItem?.identifier?.mediaId ?: id)
}

@Serializable
data class EmbedMediaItem(val identifier: EmbedIdentifier? = null, val metadata: EmbedMetadata? = null)

@Serializable
data class EmbedIdentifier(val platform: String? = null, val mediaId: String? = null)

@Serializable
data class EmbedMetadata(
    val previewUrl: String? = null,
    val displayName: String? = null,
    val title: String? = null,
    val createdDate: String? = null,
    val live: Boolean? = null,
    val viewers: Int? = null,
)

/** `dggApi:bannedEmbeds` */
@Serializable
data class BannedEmbed(val platform: String, val name: String, val reason: String? = null)

/** `dggApi:youtubeVods` — only the first (latest) entry is shown. */
@Serializable
data class LatestVod(
    val id: String? = null,
    val platform: String? = null,
    val title: String? = null,
    val mediumThumbnailUrl: String? = null,
    val highThumbnailUrl: String? = null,
    val streamStartTime: String? = null,
    val streamEndTime: String? = null,
    val url: String? = null,
    val embedUrl: String? = null,
)

object Times {
    private val offsetNoColon: DateTimeFormatter = DateTimeFormatterBuilder()
        .append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        .appendPattern("[XXX][XX][X]")
        .toFormatter()

    fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value, offsetNoColon).toInstant() }.getOrNull()
            ?: runCatching { Instant.parse(value) }.getOrNull()
    }
}
