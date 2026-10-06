package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LinkParserTest {
    @Test
    fun parsesPlatformLinks() {
        assertEquals(Embed("kick", "destiny"), LinkParser.parse("https://kick.com/Destiny"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ"), LinkParser.parse("watch https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Embed("twitch-vod", "123456"), LinkParser.parse("https://www.twitch.tv/videos/123456"))
        assertEquals(Embed("kick", "destiny"), LinkParser.parse("https://www.destiny.gg/bigscreen#kick/destiny"))
    }

    @Test
    fun keepsTimestamps() {
        assertEquals(Embed("youtube", "dQw4w9WgXcQ", 42), LinkParser.parse("https://youtu.be/dQw4w9WgXcQ?t=42"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ", 90), LinkParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1m30s"))
        assertEquals(Embed("youtube", "dQw4w9WgXcQ", 15), LinkParser.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=15"))
        assertEquals(Embed("twitch-vod", "123456", 3723), LinkParser.parse("https://www.twitch.tv/videos/123456?t=01h02m03s"))
        assertEquals(
            Embed("kick-vod", "destiny/489b8b26-8a97-400f-9845-13d8f6fa6d1b", 90),
            LinkParser.parse("https://kick.com/destiny/videos/489b8b26-8a97-400f-9845-13d8f6fa6d1b?t=90"),
        )
        assertEquals(Embed("rumble", "v5qi58n", 30), LinkParser.parse("https://rumble.com/embed/v5qi58n/?start=30"))
        // Channels play live
        assertEquals(Embed("twitch", "destiny"), LinkParser.parse("https://www.twitch.tv/destiny?t=30"))
        assertEquals("#youtube/dQw4w9WgXcQ?t=42s", LinkParser.parse("https://youtu.be/dQw4w9WgXcQ?t=42")!!.hash)
    }

    @Test
    fun readsTimeFormats() {
        assertEquals(90, LinkParser.parseTime("90"))
        assertEquals(90, LinkParser.parseTime("90s"))
        assertEquals(3723, LinkParser.parseTime("1h2m3s"))
        assertEquals(120, LinkParser.parseTime("2m"))
        assertNull(LinkParser.parseTime("0"))
        assertNull(LinkParser.parseTime(""))
        assertNull(LinkParser.parseTime("soon"))
    }

    @Test
    fun rumbleLinks() {
        // Longer ids aren't cut to 7 characters
        assertEquals(Embed("rumble", "v1abcdefg"), LinkParser.parse("https://rumble.com/embed/v1abcdefg/"))
        // A page link's id isn't the embed id: it needs a lookup instead of a guess
        val page = "https://rumble.com/v5sraaz-myron-triggered-rubi-rose-over-this.html"
        assertNull(LinkParser.parse(page))
        assertEquals(page to null, LinkParser.rumblePage("look $page"))
        assertEquals(page to 75, LinkParser.rumblePage("$page?start=75"))
        assertNull(LinkParser.rumblePage("https://rumble.com/c/freshandfit"))
        assertNull(LinkParser.rumblePage("https://kick.com/v5sraaz-x.html"))
    }
}
