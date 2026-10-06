package io.github.jordanwoodson.tinyscreen.chat

import io.github.jordanwoodson.tinyscreen.stream.Embed
import kotlinx.serialization.Serializable

@Serializable
data class ChatUser(
    val id: Long? = null,
    val nick: String = "",
    val roles: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val createdDate: String? = null,
    val watching: Watching? = null,
    val subscription: Subscription? = null,
) {
    val key: String get() = nick.lowercase()
    val isModerator: Boolean get() = "ADMIN" in roles || "MODERATOR" in roles
    val isPrivileged: Boolean get() = roles.any { it == "MODERATOR" || it == "PROTECTED" || it == "ADMIN" }
    val isBot: Boolean get() = "BOT" in roles || "bot" in features || "flair11" in features
    val canPoll: Boolean get() = roles.any { it == "ADMIN" || it == "MODERATOR" || it == "POLLS" }
    val canHost: Boolean get() = roles.any { it == "ADMIN" || it == "MODERATOR" || it == "HOST" }
    val isSubscriber: Boolean get() = "subscriber" in features
    val subTier: Int
        get() = when {
            "flair42" in features -> 5
            "flair8" in features -> 4
            "flair3" in features -> 3
            "flair1" in features -> 2
            "flair13" in features -> 1
            else -> 0
        }

    fun canUse(emote: Emote): Boolean =
        isPrivileged || (emote.minimumSubTier <= subTier && (!emote.twitch || "flair9" in features))
}

@Serializable
data class Watching(val platform: String? = null, val id: String? = null) {
    fun matches(embed: Embed?): Boolean = embed != null && platform == embed.platform && id.equals(embed.id, ignoreCase = true)
}

@Serializable
data class Subscription(val tier: Int? = null, val source: String? = null)

// --- Emotes & flairs (r2cdn.destiny.gg) ---

@Serializable
data class AssetImage(val url: String = "", val name: String? = null, val mime: String? = null, val height: Int = 0, val width: Int = 0)

@Serializable
data class EmoteJson(
    val prefix: String,
    val creator: String? = null,
    val twitch: Boolean = false,
    val minimumSubTier: Int = 0,
    val image: List<AssetImage> = emptyList(),
)

@Serializable
data class FlairJson(
    val label: String = "",
    val name: String,
    val description: String? = null,
    val hidden: Boolean = false,
    val priority: Int = 100,
    val color: String? = null,
    val rainbowColor: Boolean = false,
    val image: List<AssetImage> = emptyList(),
)

/** Horizontal sprite-strip animation parsed from emotes.css. */
data class SpriteAnimation(
    val frameWidth: Int,
    val frames: Int,
    val durationMs: Int,
    /** 0 = infinite. */
    val iterations: Int,
    val holdLastFrame: Boolean,
)

data class Emote(
    val prefix: String,
    val url: String,
    val mime: String,
    /** Size of one frame as displayed (CSS px ≈ dp). */
    val width: Int,
    val height: Int,
    val sheetWidth: Int,
    val minimumSubTier: Int,
    val twitch: Boolean,
    val creator: String?,
    val sprite: SpriteAnimation?,
) {
    val animated: Boolean get() = sprite != null || mime.contains("gif") || mime.contains("avif") || mime.contains("webp")
}

data class Flair(
    val name: String,
    val label: String,
    val hidden: Boolean,
    val priority: Int,
    val color: Long?,
    val rainbow: Boolean,
    val imageUrl: String?,
    val width: Int,
    val height: Int,
)

// --- Lines ---

enum class LinkTag { Nsfw, Nsfl, Spoilers }

sealed interface Segment {
    data class Text(val text: String) : Segment
    data class EmoteSeg(val emote: Emote) : Segment
    data class Link(val url: String, val display: String, val embed: Embed?) : Segment
    data class HashLink(val embed: Embed, val text: String) : Segment
    data class Mention(val nick: String, val text: String) : Segment
}

enum class InfoKind { Status, Info, Error, Command, Historical }

sealed interface ChatLine {
    val key: Long
    val timestamp: Long

    data class User(
        override val key: Long,
        override val timestamp: Long,
        val user: ChatUser,
        val raw: String,
        val segments: List<Segment>,
        val action: Boolean,
        val greentext: Boolean,
        val sus: Boolean,
        val linkTag: LinkTag?,
        val mentions: Set<String>,
        val hash: String,
        val pending: Boolean = false,
        val whisper: Boolean = false,
        val historical: Boolean = false,
    ) : ChatLine

    data class Combo(
        override val key: Long,
        override val timestamp: Long,
        val emote: Emote,
        val count: Int,
        val completed: Boolean,
        val nicks: List<String>,
    ) : ChatLine

    data class Info(
        override val key: Long,
        override val timestamp: Long,
        val kind: InfoKind,
        val text: String,
        val link: String? = null,
        val linkLabel: String? = null,
    ) : ChatLine

    data class Broadcast(
        override val key: Long,
        override val timestamp: Long,
        val user: ChatUser?,
        val segments: List<Segment>,
    ) : ChatLine

    data class Death(
        override val key: Long,
        override val timestamp: Long,
        val user: ChatUser,
        val text: String,
    ) : ChatLine

    data class Event(
        override val key: Long,
        override val timestamp: Long,
        val event: PaidEvent,
    ) : ChatLine
}

/** Subscriptions, gifts, donations, spotlights and X posts (the event bar). */
data class PaidEvent(
    val uuid: String,
    val type: String,
    val user: ChatUser?,
    val timestamp: Long,
    val expiresAt: Long,
    val title: String,
    val subtitle: String?,
    val message: String?,
    val tier: Int?,
    val amountCents: Long?,
    val audioUrl: String?,
    val url: String?,
)

data class PinnedMessage(val uuid: String, val nick: String, val text: String, val timestamp: Long, val user: ChatUser?)

data class PollState(
    val question: String,
    val options: List<String>,
    val totals: List<Long>,
    val totalVotes: Long,
    val weighted: Boolean,
    val startedBy: String,
    val startMs: Long,
    val durationMs: Long,
    val serverOffsetMs: Long,
    val canVote: Boolean,
    val myVote: Int?,
    val ended: Boolean = false,
)

data class Conversation(
    val userId: Long?,
    val username: String,
    val lastMessage: String,
    val timestamp: Long,
    val unread: Int,
    val lastMessageFromMe: Boolean,
)

data class WhisperMessage(
    val id: Long?,
    val from: String,
    val to: String,
    val text: String,
    val timestamp: Long,
)
