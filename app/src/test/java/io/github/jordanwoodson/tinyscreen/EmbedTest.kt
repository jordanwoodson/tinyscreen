package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.stream.Embed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbedTest {
    @Test
    fun parsesChannelHashes() {
        assertEquals(Embed("kick", "destiny"), Embed.fromHash("#kick/destiny"))
        assertEquals(Embed("twitch", "moonmoon"), Embed.fromHash("#twitch/moonmoon"))
        assertEquals(Embed("rumble", "v5qi58n"), Embed.fromHash("#rumble/v5qi58n"))
    }

    @Test
    fun parsesKickVodWithChannelAndUuid() {
        val e = Embed.fromHash("#kick-vod/Destiny/489b8b26-8a97-400f-9845-13d8f6fa6d1b")
        assertEquals(Embed("kick-vod", "Destiny/489b8b26-8a97-400f-9845-13d8f6fa6d1b"), e)
        assertEquals("Destiny", e!!.displayId)
    }

    @Test
    fun parsesStartTimeAndYoutubeLive() {
        assertEquals(Embed("youtube", "dQw4w9WgXcQ", 42), Embed.fromHash("#youtube/dQw4w9WgXcQ?t=42s"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ"), Embed.fromHash("#youtube-live/dQw4w9WgXcQ"))
    }

    @Test
    fun parsesFacebookAndRumblePub() {
        assertEquals(Embed("facebook", "somepage/videos/1234567890123"), Embed.fromHash("#facebook/somepage/videos/1234567890123"))
        assertEquals(Embed("rumble", "v5qi58n/?pub=4abcd"), Embed.fromHash("#rumble/v5qi58n/?pub=4abcd"))
    }

    @Test
    fun rejectsUnknownOrShortHashes() {
        assertNull(Embed.fromHash("#notaplatform/whatever"))
        assertNull(Embed.fromHash("#kick/ab"))
        assertNull(Embed.fromHash("kick/destiny"))
    }

    @Test
    fun matchingIsCaseInsensitiveExceptYoutube() {
        assertEquals(true, Embed("kick", "Destiny").matches("kick", "destiny"))
        assertEquals(false, Embed("youtube", "AbCdEfGhIjK").matches("youtube", "abcdefghijk"))
    }
}
