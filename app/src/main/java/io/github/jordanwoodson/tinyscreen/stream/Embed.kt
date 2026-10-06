package io.github.jordanwoodson.tinyscreen.stream

import android.net.Uri

/** Something bigscreen can show: `#platform/id` in the page hash. */
data class Embed(val platform: String, val id: String, val startSeconds: Int? = null) {
    val hash: String get() = "#$platform/$id" + (startSeconds?.let { "?t=${it}s" } ?: "")
    val bigscreenUrl: String get() = "https://www.destiny.gg/bigscreen$hash"

    /** Kick VOD ids are `Channel/uuid`; everything else is a single id. */
    val displayId: String get() = if (platform == "kick-vod") id.substringBefore('/') else id

    val platformName: String get() = platformName(platform)

    fun matches(platform: String?, id: String?): Boolean {
        if (platform == null || id == null || this.platform != platform) return false
        return if (this.platform == "youtube") this.id == id else this.id.equals(id, ignoreCase = true)
    }

    /** iframe URL the bigscreen page would load; null for kick-vod, which plays as HLS. */
    fun webEmbedUrl(): String? {
        val parents = "&parent=www.destiny.gg&parent=stage.destiny.gg"
        val e = Uri.encode(id)
        return when (platform) {
            "twitch" -> "https://player.twitch.tv/?channel=$e$parents"
            "twitch-vod" -> "https://player.twitch.tv/?video=$e$parents" +
                (startSeconds?.let { "&time=${it / 3600}h${it % 3600 / 60}m${it % 60}s" } ?: "")
            "twitch-clip" -> "https://clips.twitch.tv/embed?clip=$e$parents"
            "youtube" -> "https://www.youtube.com/embed/$e?autoplay=1&playsinline=1&start=${startSeconds ?: ""}"
            "kick" -> "https://player.kick.com/$e?autoplay=true"
            "facebook" -> "https://www.facebook.com/plugins/video.php?href=" +
                Uri.encode("https://www.facebook.com/$id") + "&width=1920&height=1080"
            "rumble" -> "https://rumble.com/embed/$id"
            "vimeo" -> "https://player.vimeo.com/video/$e"
            "angelthump" -> "https://player.angelthump.com/?channel=$e"
            else -> null
        }
    }

    companion object {
        // Same pattern as the bigscreen page, plus youtube-live which chat also links
        private val HASH = Regex(
            """^#(kick|kick-vod|twitch|twitch-vod|twitch-clip|youtube|youtube-live|facebook|rumble|vimeo|angelthump)/""" +
                """([\w\d]{3,64}/videos/\d{10,20}|[\w-]{3,64}/[\da-f-]{36}|[\w-]{3,64}|\w{7,12}/\?pub=\w{3,10})""" +
                """(?:\?t=(\d+)s?)?(?:$|\?|&)""",
        )

        fun fromHash(hash: String): Embed? {
            val m = HASH.find(hash.trim()) ?: return null
            val platform = m.groupValues[1].let { if (it == "youtube-live") "youtube" else it }
            return Embed(platform, m.groupValues[2], m.groupValues[3].toIntOrNull())
        }

        fun basePlatform(platform: String): String = when (platform) {
            "twitch-vod", "twitch-clip" -> "twitch"
            "kick-vod", "kick-clip" -> "kick"
            "youtube-live" -> "youtube"
            else -> platform
        }

        fun platformName(platform: String): String = when (basePlatform(platform)) {
            "twitch" -> "Twitch"
            "kick" -> "Kick"
            "youtube" -> "YouTube"
            "facebook" -> "Facebook"
            "rumble" -> "Rumble"
            "vimeo" -> "Vimeo"
            "angelthump" -> "AngelThump"
            else -> platform.replaceFirstChar { it.uppercase() }
        }
    }
}
