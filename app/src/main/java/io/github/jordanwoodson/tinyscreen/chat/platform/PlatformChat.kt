package io.github.jordanwoodson.tinyscreen.chat.platform

import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.StreamEntry
import io.github.jordanwoodson.tinyscreen.stream.StreamInfo
import io.github.jordanwoodson.tinyscreen.ui.Content
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Destiny's accounts, for chats and links that exist even while he's offline. */
object DestinyChannels {
    const val KICK = "destiny"
    const val TWITCH = "destiny"
    const val YOUTUBE = "https://www.youtube.com/@destiny/live"
    const val RUMBLE = "https://rumble.com/c/Destiny"
    const val FACEBOOK = "https://www.facebook.com/profile.php?id=61571550104623"
}

/** A page of the chat pager: destiny.gg's own chat, or one platform's popout chat in a WebView. */
sealed interface ChatTab {
    val key: String

    data object Dgg : ChatTab {
        override val key = "dgg"
    }

    data class Platform(
        /** kick, youtube, twitch, rumble or facebook. */
        val platform: String,
        /** Whose chat this is when it isn't Destiny's: the channel bigscreen is showing. */
        val channel: String?,
        val chatUrl: String,
        /** The stream or channel page, for "Open in app". */
        val watchUrl: String,
    ) : ChatTab {
        override val key: String get() = chatUrl
        val label: String get() = Embed.platformName(platform) + (channel?.let { " · $it" } ?: "")
    }
}

/** A chat that needs a request first: whether a YouTube video is live, or a Rumble video's numeric id. */
data class ChatLookup(val platform: String, val id: String)

/** What a [ChatLookup] found. A null [chatUrl] means the video has no live chat. */
data class ChatLookupResult(val chatUrl: String?, val watchUrl: String? = null, val channel: String? = null)

data class ChatTabOptions(
    /** Platforms whose tabs are switched off in settings. */
    val hidden: Set<String> = emptySet(),
    /** A tab for the chat of whoever bigscreen is showing, when it isn't Destiny. */
    val otherStreams: Boolean = true,
)

/**
 * The tabs to show, and the lookups still needed before every tab can appear. [otherStream] is the key
 * of the tab for the stream bigscreen is showing, when that has its own tab.
 */
data class ChatTabs(val tabs: List<ChatTab>, val lookups: Set<ChatLookup>, val otherStream: String? = null)

object PlatformChats {
    /** Order of Destiny's own tabs, after destiny.gg chat. */
    val PLATFORMS = listOf("kick", "youtube", "twitch", "rumble", "facebook")

    private val twitchName = Regex("""^\w{2,25}$""")
    private val rumbleNumericId = Regex("""^\d+$""")

    /**
     * destiny.gg chat first, then Destiny's chat on each platform that has one right now, then the chat
     * of the stream bigscreen is showing when it's someone else's. Duplicates (bigscreen showing
     * `#kick/destiny`) collapse into Destiny's tab.
     */
    fun tabs(
        info: StreamInfo,
        content: Content,
        found: Map<ChatLookup, ChatLookupResult>,
        options: ChatTabOptions = ChatTabOptions(),
    ): ChatTabs {
        val tabs = mutableListOf<ChatTab>(ChatTab.Dgg)
        val lookups = mutableSetOf<ChatLookup>()
        fun add(tab: ChatTab.Platform?) {
            if (tab != null && tab.platform !in options.hidden && tabs.none { it.key == tab.key }) tabs += tab
        }
        fun lookUp(lookup: ChatLookup): ChatLookupResult? = found[lookup] ?: run { lookups += lookup; null }

        PLATFORMS.forEach { platform -> add(destinyTab(platform, info.streams[platform], ::lookUp)) }
        var other: String? = null
        if (options.otherStreams) {
            otherStreamTab(content, ::lookUp)?.let { tab ->
                add(tab)
                if (tabs.last() === tab) other = tab.key
            }
        }
        // Lookups for hidden platforms aren't worth the request
        return ChatTabs(tabs, lookups.filterTo(mutableSetOf()) { it.platform !in options.hidden }, other)
    }

    private fun destinyTab(platform: String, entry: StreamEntry?, lookUp: (ChatLookup) -> ChatLookupResult?): ChatTab.Platform? {
        val live = entry?.live == true
        val id = entry?.id?.takeIf { it.isNotBlank() }
        // chat_url isn't used by the site and its format is unknown, so it's only a fallback
        val serverChat = entry?.chatUrl?.takeIf { it.startsWith("https://") }
        fun tab(chatUrl: String?, watchUrl: String) = chatUrl?.let { ChatTab.Platform(platform, null, it, watchUrl) }
        return when (platform) {
            // The chatroom exists while he's offline; when live, Kick's id is the playback URL, not the slug
            "kick" -> tab(ChatUrls.kick(DestinyChannels.KICK), "https://kick.com/${DestinyChannels.KICK}")
            "youtube" -> if (!live) null else when (id) {
                null -> tab(serverChat, DestinyChannels.YOUTUBE)
                else -> tab(ChatUrls.youtube(id), ChatUrls.youtubeWatch(id))
            }
            "twitch" -> if (!live) null else {
                val channel = id?.takeIf { twitchName.matches(it) } ?: DestinyChannels.TWITCH
                tab(ChatUrls.twitch(channel), "https://www.twitch.tv/$channel")
            }
            "rumble" -> if (!live) null else when {
                id == null -> tab(serverChat, DestinyChannels.RUMBLE)
                rumbleNumericId.matches(id) -> tab(ChatUrls.rumble(id), DestinyChannels.RUMBLE)
                else -> {
                    val result = lookUp(ChatLookup("rumble", rumbleEmbedId(id)))
                    tab(result?.chatUrl ?: serverChat, result?.watchUrl ?: DestinyChannels.RUMBLE)
                }
            }
            // Facebook has no popout chat; live comments sit under the video
            "facebook" -> if (!live) null else {
                val video = id?.let { "https://www.facebook.com/$it" }
                tab(serverChat ?: video, video ?: DestinyChannels.FACEBOOK)
            }
            else -> null
        }
    }

    private fun otherStreamTab(content: Content, lookUp: (ChatLookup) -> ChatLookupResult?): ChatTab.Platform? {
        val (embed, name, live) = when (content) {
            is Content.Embedded -> Triple(content.embed, content.meta?.metadata?.displayName, content.meta?.metadata?.live)
            is Content.Host -> Triple(content.embed, content.hosting.displayName, content.hosting.live)
            // Destiny's own stream already has its tab
            is Content.Live, Content.Offline -> return null
        }
        val id = embed.id
        return when (embed.platform) {
            "kick" -> ChatTab.Platform("kick", name ?: id, ChatUrls.kick(id), "https://kick.com/${id.lowercase()}")
            "twitch" -> ChatTab.Platform("twitch", name ?: id, ChatUrls.twitch(id), "https://www.twitch.tv/${id.lowercase()}")
            // Only live videos have a chat; ask YouTube unless the live socket already said so
            "youtube" -> {
                val result = if (live == true) null else lookUp(ChatLookup("youtube", id))
                val chat = if (live == true) ChatUrls.youtube(id) else result?.chatUrl
                chat?.let { ChatTab.Platform("youtube", name ?: result?.channel ?: id, it, ChatUrls.youtubeWatch(id)) }
            }
            "rumble" -> {
                val result = lookUp(ChatLookup("rumble", rumbleEmbedId(id)))
                result?.chatUrl?.let {
                    ChatTab.Platform("rumble", name ?: result.channel ?: id, it, result.watchUrl ?: "https://rumble.com/embed/$id")
                }
            }
            else -> null
        }
    }

    /** `v5qi58n/?pub=4abcd` → `v5qi58n`. */
    fun rumbleEmbedId(id: String): String = id.substringBefore('/')
}

object ChatUrls {
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun kick(slug: String) = "https://kick.com/popout/${enc(slug.lowercase())}/chat"

    fun youtube(videoId: String) = "https://www.youtube.com/live_chat?is_popout=1&dark_theme=1&v=${enc(videoId)}"

    fun youtubeWatch(videoId: String) = "https://www.youtube.com/watch?v=${enc(videoId)}"

    fun twitch(channel: String) = "https://www.twitch.tv/popout/${enc(channel.lowercase())}/chat?darkpopout"

    fun rumble(numericId: String) = "https://rumble.com/chat/popup/${enc(numericId)}"

    /** Where "Sign in" starts; the chat pages pick the session up from the shared cookie store. */
    fun signIn(platform: String): String? = when (platform) {
        "kick" -> "https://kick.com/login"
        "youtube" -> "https://accounts.google.com/ServiceLogin?service=youtube&continue=" +
            enc("https://www.youtube.com/signin?action_handle_signin=true&app=m&next=https://m.youtube.com/")
        "twitch" -> "https://www.twitch.tv/login"
        "rumble" -> "https://rumble.com/login.php"
        "facebook" -> "https://m.facebook.com/login/"
        else -> null
    }

    /** Hosts a platform sends you to for signing in; reaching one from a chat opens the sign-in screen. */
    private val signInHosts = setOf("accounts.google.com", "auth.rumble.com", "id.twitch.tv", "passport.twitch.tv")
    private val signInPath = Regex("""(^|/)(login|signin|sign-in|log-in|ServiceLogin)(\.php)?(/|$)""", RegexOption.IGNORE_CASE)

    /** What a navigation inside a platform chat should do. */
    sealed interface Navigation {
        /** Part of the chat (its own pages, Cloudflare checks): let the WebView load it. */
        data object Stay : Navigation
        data object Ignore : Navigation
        data class SignIn(val url: String) : Navigation
        data class Open(val url: String) : Navigation
    }

    fun navigation(chatUrl: String, target: String): Navigation {
        val uri = runCatching { URI(target) }.getOrNull()
            ?: return if (target.startsWith("https://") || target.startsWith("http://")) Navigation.Open(target) else Navigation.Ignore
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Navigation.Ignore
        val host = uri.host?.lowercase()?.let(::siteOf) ?: return Navigation.Ignore
        val chat = runCatching { URI(chatUrl) }.getOrNull()
        val chatHost = chat?.host?.lowercase()?.let(::siteOf)
        val path = uri.rawPath.orEmpty()
        if (uri.host.lowercase() in signInHosts) return Navigation.SignIn(target)
        if (host == chatHost) {
            if (signInPath.containsMatchIn(path)) return Navigation.SignIn(target)
            if (path.startsWith("/cdn-cgi/") || firstSegment(path) == firstSegment(chat.rawPath.orEmpty())) return Navigation.Stay
        }
        // YouTube wraps links in chat messages in its redirect page
        if (host == "youtube.com" && path == "/redirect") {
            query(uri, "q")?.let { return Navigation.Open(it) }
        }
        return Navigation.Open(target)
    }

    private fun siteOf(host: String): String = host.removePrefix("www.").removePrefix("m.")

    private fun firstSegment(path: String): String = path.trimStart('/').substringBefore('/')

    private fun query(uri: URI, name: String): String? = uri.rawQuery?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?.substringAfter('=', "")
        ?.let { URLDecoder.decode(it, "UTF-8") }
        ?.takeIf { it.isNotBlank() }
}
