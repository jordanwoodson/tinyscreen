package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import org.junit.Assert.assertEquals
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
}
