package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.platform.ChatLookup
import io.github.jordanwoodson.tinyscreen.chat.platform.ChatLookupResult
import io.github.jordanwoodson.tinyscreen.chat.platform.ChatTab
import io.github.jordanwoodson.tinyscreen.chat.platform.ChatTabOptions
import io.github.jordanwoodson.tinyscreen.chat.platform.ChatUrls
import io.github.jordanwoodson.tinyscreen.chat.platform.ChatUrls.Navigation
import io.github.jordanwoodson.tinyscreen.chat.platform.PlatformChats
import io.github.jordanwoodson.tinyscreen.stream.EmbedMediaItem
import io.github.jordanwoodson.tinyscreen.stream.EmbedMetadata
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Hosting
import io.github.jordanwoodson.tinyscreen.stream.StreamEntry
import io.github.jordanwoodson.tinyscreen.stream.StreamInfo
import io.github.jordanwoodson.tinyscreen.stream.TopEmbed
import io.github.jordanwoodson.tinyscreen.ui.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformChatTest {
    private val kickChat = "https://kick.com/popout/destiny/chat"

    /** What live.destiny.gg sends while Destiny is offline: a stale YouTube entry and nulls. */
    private val offline = StreamInfo(
        mapOf(
            "twitch" to null,
            "youtube" to StreamEntry(live = false, statusText = "old title", platform = "youtube", type = "livestream"),
            "facebook" to null,
            "rumble" to null,
            "kick" to null,
        ),
    )

    private fun keys(tabs: List<ChatTab>) = tabs.map { it.key }

    private fun platform(tabs: List<ChatTab>, i: Int) = tabs[i] as ChatTab.Platform

    @Test
    fun offlineShowsDggAndDestinysKickChat() {
        val result = PlatformChats.tabs(offline, Content.Offline, emptyMap())
        assertEquals(listOf("dgg", kickChat), keys(result.tabs))
        assertEquals("Kick", platform(result.tabs, 1).label)
        assertEquals("https://kick.com/destiny", platform(result.tabs, 1).watchUrl)
        assertTrue(result.lookups.isEmpty())
    }

    @Test
    fun liveOnKickAndYoutubeAddsYoutubeChat() {
        // A live Kick entry's id is the IVS playback URL, not the channel
        val kick = StreamEntry(live = true, id = "https://abc.us-west-2.playback.live-video.net/api/video/v1/x.m3u8", platform = "kick")
        val youtube = StreamEntry(live = true, id = "wIKpoCsSA7E", platform = "youtube")
        val info = StreamInfo(mapOf("youtube" to youtube, "kick" to kick))
        val content = Content.Live(kick, listOf(kick, youtube), Embed("kick", kick.id!!))
        val tabs = PlatformChats.tabs(info, content, emptyMap()).tabs
        assertEquals(
            listOf("dgg", kickChat, "https://www.youtube.com/live_chat?is_popout=1&dark_theme=1&v=wIKpoCsSA7E"),
            keys(tabs),
        )
        assertEquals("https://www.youtube.com/watch?v=wIKpoCsSA7E", platform(tabs, 2).watchUrl)
    }

    @Test
    fun liveYoutubeWithoutIdFallsBackToChatUrlOrNothing() {
        val noId = StreamInfo(mapOf("youtube" to StreamEntry(live = true, platform = "youtube")))
        assertEquals(listOf("dgg", kickChat), keys(PlatformChats.tabs(noId, Content.Offline, emptyMap()).tabs))
        val withChatUrl = StreamInfo(mapOf("youtube" to StreamEntry(live = true, chatUrl = "https://www.youtube.com/live_chat?v=x", platform = "youtube")))
        assertEquals("https://www.youtube.com/live_chat?v=x", keys(PlatformChats.tabs(withChatUrl, Content.Offline, emptyMap()).tabs)[2])
    }

    @Test
    fun liveTwitchUsesTheChannelOrDestiny() {
        val named = StreamInfo(mapOf("twitch" to StreamEntry(live = true, id = "destiny", platform = "twitch")))
        assertEquals("https://www.twitch.tv/popout/destiny/chat?darkpopout", keys(PlatformChats.tabs(named, Content.Offline, emptyMap()).tabs)[2])
        val odd = StreamInfo(mapOf("twitch" to StreamEntry(live = true, id = "https://example.com/x", platform = "twitch")))
        assertEquals("https://www.twitch.tv/popout/destiny/chat?darkpopout", keys(PlatformChats.tabs(odd, Content.Offline, emptyMap()).tabs)[2])
    }

    @Test
    fun liveRumbleNeedsTheNumericVideoId() {
        val info = StreamInfo(mapOf("rumble" to StreamEntry(live = true, id = "v6abcde", platform = "rumble")))
        val pending = PlatformChats.tabs(info, Content.Offline, emptyMap())
        assertEquals(listOf("dgg", kickChat), keys(pending.tabs))
        assertEquals(setOf(ChatLookup("rumble", "v6abcde")), pending.lookups)

        val found = mapOf(ChatLookup("rumble", "v6abcde") to ChatLookupResult("https://rumble.com/chat/popup/359281535", "https://rumble.com/v60552h-x.html"))
        val done = PlatformChats.tabs(info, Content.Offline, found)
        assertEquals(listOf("dgg", kickChat, "https://rumble.com/chat/popup/359281535"), keys(done.tabs))
        assertEquals("https://rumble.com/v60552h-x.html", platform(done.tabs, 2).watchUrl)
        assertTrue(done.lookups.isEmpty())

        val numeric = StreamInfo(mapOf("rumble" to StreamEntry(live = true, id = "359281535", platform = "rumble")))
        assertEquals("https://rumble.com/chat/popup/359281535", keys(PlatformChats.tabs(numeric, Content.Offline, emptyMap()).tabs)[2])
    }

    @Test
    fun liveFacebookShowsTheVideoComments() {
        val info = StreamInfo(mapOf("facebook" to StreamEntry(live = true, id = "destiny/videos/1234567890123", platform = "facebook")))
        assertEquals("https://www.facebook.com/destiny/videos/1234567890123", keys(PlatformChats.tabs(info, Content.Offline, emptyMap()).tabs)[2])
        val noId = StreamInfo(mapOf("facebook" to StreamEntry(live = true, platform = "facebook")))
        assertEquals(2, PlatformChats.tabs(noId, Content.Offline, emptyMap()).tabs.size)
    }

    @Test
    fun hostedKickStreamGetsALabelledTab() {
        val hosting = Hosting(id = "anythingelse", platform = "kick", displayName = "anythingelse", live = true)
        val tabs = PlatformChats.tabs(offline, Content.Host(hosting, Embed("kick", "anythingelse")), emptyMap()).tabs
        assertEquals(listOf("dgg", kickChat, "https://kick.com/popout/anythingelse/chat"), keys(tabs))
        assertEquals("Kick · anythingelse", platform(tabs, 2).label)
    }

    @Test
    fun embeddedTwitchChannelGetsPopoutChat() {
        val tabs = PlatformChats.tabs(offline, Content.Embedded(Embed("twitch", "xQc"), null), emptyMap()).tabs
        assertEquals("https://www.twitch.tv/popout/xqc/chat?darkpopout", platform(tabs, 2).chatUrl)
        assertEquals("Twitch · xQc", platform(tabs, 2).label)
        assertEquals("https://www.twitch.tv/xqc", platform(tabs, 2).watchUrl)
    }

    @Test
    fun embeddingDestinysOwnKickDoesNotDuplicateTheTab() {
        val tabs = PlatformChats.tabs(offline, Content.Embedded(Embed("kick", "Destiny"), null), emptyMap()).tabs
        assertEquals(listOf("dgg", kickChat), keys(tabs))
    }

    @Test
    fun vodsAndUnsupportedEmbedsHaveNoChat() {
        listOf(Embed("kick-vod", "Destiny/489b8b26-8a97-400f-9845-13d8f6fa6d1b"), Embed("twitch-vod", "123"), Embed("vimeo", "1234"))
            .forEach { embed ->
                val result = PlatformChats.tabs(offline, Content.Embedded(embed, null), emptyMap())
                assertEquals(listOf("dgg", kickChat), keys(result.tabs))
                assertTrue(result.lookups.isEmpty())
            }
    }

    @Test
    fun embeddedYoutubeIsLookedUpUnlessKnownLive() {
        val unknown = PlatformChats.tabs(offline, Content.Embedded(Embed("youtube", "fgASZeqF_cQ"), null), emptyMap())
        assertEquals(setOf(ChatLookup("youtube", "fgASZeqF_cQ")), unknown.lookups)
        assertEquals(2, unknown.tabs.size)

        val notLive = mapOf(ChatLookup("youtube", "fgASZeqF_cQ") to ChatLookupResult(null))
        assertEquals(2, PlatformChats.tabs(offline, Content.Embedded(Embed("youtube", "fgASZeqF_cQ"), null), notLive).tabs.size)

        val meta = TopEmbed("youtube", "fgASZeqF_cQ", mediaItem = EmbedMediaItem(metadata = EmbedMetadata(displayName = "Sky News", live = true)))
        val known = PlatformChats.tabs(offline, Content.Embedded(Embed("youtube", "fgASZeqF_cQ"), meta), emptyMap())
        assertTrue(known.lookups.isEmpty())
        assertEquals("YouTube · Sky News", platform(known.tabs, 2).label)
        assertEquals(ChatUrls.youtube("fgASZeqF_cQ"), platform(known.tabs, 2).chatUrl)
    }

    @Test
    fun embeddedRumbleUsesTheLookedUpChannel() {
        val content = Content.Embedded(Embed("rumble", "v5qi58n/?pub=4abcd"), null)
        assertEquals(setOf(ChatLookup("rumble", "v5qi58n")), PlatformChats.tabs(offline, content, emptyMap()).lookups)
        val found = mapOf(ChatLookup("rumble", "v5qi58n") to ChatLookupResult("https://rumble.com/chat/popup/1", null, "Newsmax"))
        assertEquals("Rumble · Newsmax", platform(PlatformChats.tabs(offline, content, found).tabs, 2).label)
    }

    @Test
    fun settingsHidePlatformsAndOtherStreams() {
        val hosting = Hosting(id = "anythingelse", platform = "kick", displayName = "anythingelse", live = true)
        val content = Content.Host(hosting, Embed("kick", "anythingelse"))
        assertEquals(listOf("dgg"), keys(PlatformChats.tabs(offline, content, emptyMap(), ChatTabOptions(hidden = setOf("kick"))).tabs))
        assertEquals(listOf("dgg", kickChat), keys(PlatformChats.tabs(offline, content, emptyMap(), ChatTabOptions(otherStreams = false)).tabs))
        val youtube = PlatformChats.tabs(offline, Content.Embedded(Embed("youtube", "abcdefghijk"), null), emptyMap(), ChatTabOptions(hidden = setOf("youtube")))
        assertTrue(youtube.lookups.isEmpty())
    }

    @Test
    fun buildsPopoutAndSignInUrls() {
        assertEquals("https://kick.com/popout/anythingelse/chat", ChatUrls.kick("AnythingElse"))
        assertEquals("https://www.twitch.tv/popout/xqc/chat?darkpopout", ChatUrls.twitch("xqc"))
        assertEquals("https://www.youtube.com/live_chat?is_popout=1&dark_theme=1&v=fgASZeqF_cQ", ChatUrls.youtube("fgASZeqF_cQ"))
        assertEquals("https://rumble.com/chat/popup/359281535", ChatUrls.rumble("359281535"))
        assertEquals("https://kick.com/login", ChatUrls.signIn("kick"))
        assertTrue(ChatUrls.signIn("youtube")!!.startsWith("https://accounts.google.com/"))
    }

    @Test
    fun linksInsideAChatOpenOutside() {
        val kick = ChatUrls.kick("destiny")
        assertEquals(Navigation.Stay, ChatUrls.navigation(kick, "https://kick.com/popout/destiny/chat?x=1"))
        assertEquals(Navigation.Stay, ChatUrls.navigation(kick, "https://kick.com/cdn-cgi/challenge-platform/h/b"))
        assertEquals(Navigation.Open("https://kick.com/xqc"), ChatUrls.navigation(kick, "https://kick.com/xqc"))
        assertEquals(Navigation.Open("https://example.com/a"), ChatUrls.navigation(kick, "https://example.com/a"))
        assertEquals(Navigation.SignIn("https://kick.com/login"), ChatUrls.navigation(kick, "https://kick.com/login"))
        assertEquals(Navigation.Ignore, ChatUrls.navigation(kick, "intent://kick.com#Intent;end"))

        val yt = ChatUrls.youtube("fgASZeqF_cQ")
        assertEquals(Navigation.Stay, ChatUrls.navigation(yt, "https://www.youtube.com/live_chat?continuation=abc"))
        assertEquals(
            Navigation.Open("https://example.com/a?b=c"),
            ChatUrls.navigation(yt, "https://www.youtube.com/redirect?event=live_chat&q=https%3A%2F%2Fexample.com%2Fa%3Fb%3Dc"),
        )
        val signIn = "https://accounts.google.com/ServiceLogin?service=youtube&continue=x"
        assertEquals(Navigation.SignIn(signIn), ChatUrls.navigation(yt, signIn))
        assertEquals(Navigation.Open("https://www.youtube.com/watch?v=abc"), ChatUrls.navigation(yt, "https://www.youtube.com/watch?v=abc"))

        val twitch = ChatUrls.twitch("xqc")
        assertEquals(Navigation.Stay, ChatUrls.navigation(twitch, "https://m.twitch.tv/popout/xqc/chat"))
        assertEquals(Navigation.SignIn("https://www.twitch.tv/login?popup=true"), ChatUrls.navigation(twitch, "https://www.twitch.tv/login?popup=true"))
    }
}
