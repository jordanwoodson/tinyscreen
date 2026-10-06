package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.stream.resolvers.TwitchAds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchAdsTest {
    private fun playlist(vararg segments: Pair<String, String>, sequence: Long = 100) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:6")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$sequence")
        segments.forEach { (title, url) ->
            appendLine("#EXTINF:2.000,$title")
            appendLine(url)
        }
    }

    /** Shaped like a real preroll playlist (Oct 2026): a DCM ad marked only by its date ranges and source. */
    private val preroll = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:5
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-START:TIME-OFFSET=0.000
        #EXT-X-DATERANGE:ID="playlist-creation-1",CLASS="timestamp",START-DATE="2026-10-06T16:19:44.075Z",END-ON-NEXT=YES
        #EXT-X-DATERANGE:ID="stitched-ad-1791303578-15223999992",CLASS="twitch-stitched-ad",START-DATE="2026-10-06T16:19:38.615Z",DURATION=6.006,X-TV-TWITCH-AD-POD-FILLED-DURATION="6"
        #EXT-X-DATERANGE:ID="source-1791303578",CLASS="twitch-stream-source",START-DATE="2026-10-06T16:19:38.615Z",END-ON-NEXT=YES,X-TV-TWITCH-STREAM-SOURCE="DCM|259852374"
        #EXT-X-DATERANGE:ID="quartile-1791303578-0",CLASS="twitch-ad-quartile",START-DATE="2026-10-06T16:19:38.615Z",DURATION=2.002,X-TV-TWITCH-AD-QUARTILE="0"
        #EXT-X-DISCONTINUITY
        #EXT-X-PROGRAM-DATE-TIME:2026-10-06T16:19:38.615Z
        #EXTINF:2.002,DCM|259852374
        https://example.ttvnw.net/v1/segment/ad1.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-10-06T16:19:40.617Z
        #EXTINF:2.002,DCM|259852374
        https://example.ttvnw.net/v1/segment/ad2.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-10-06T16:19:42.619Z
        #EXTINF:2.002,DCM|259852374
        https://example.ttvnw.net/v1/segment/ad3.ts
        #EXT-X-DATERANGE:ID="source-1791303584",CLASS="twitch-stream-source",START-DATE="2026-10-06T16:19:44.621Z",END-ON-NEXT=YES,X-TV-TWITCH-STREAM-SOURCE="live"
        #EXT-X-DISCONTINUITY
        #EXT-X-TWITCH-LIVE-SEQUENCE:33021
        #EXT-X-PROGRAM-DATE-TIME:2026-10-06T16:19:44.621Z
        #EXTINF:2.000,live
        https://example.ttvnw.net/v1/segment/live1.ts
        #EXT-X-PROGRAM-DATE-TIME:2026-10-06T16:19:46.621Z
        #EXTINF:2.000,live
        https://example.ttvnw.net/v1/segment/live2.ts
        #EXT-X-TWITCH-PREFETCH:https://example.ttvnw.net/v1/segment/next.ts
    """.trimIndent() + "\n"

    @Test
    fun cleanPlaylistHasNoAds() {
        assertFalse(TwitchAds.hasAds(playlist("live" to "a.ts", "live" to "b.ts")))
    }

    @Test
    fun detectsStitchedAds() {
        assertTrue(TwitchAds.hasAds(playlist("Amazon|123" to "ad.ts", "live" to "b.ts")))
    }

    @Test
    fun detectsAdsFromDateRangesAndStreamSource() {
        assertEquals(listOf(true, true, true, false, false), TwitchAds.adSegments(preroll))
        assertFalse("the break is over at the live edge", TwitchAds.inBreak(preroll))
    }

    @Test
    fun detectsAdsByTimeWhenTheTitleIsUnknown() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:500
            #EXT-X-DATERANGE:ID="stitched-ad-1",CLASS="twitch-stitched-ad",START-DATE="2026-10-06T12:00:02.000Z",DURATION=4.000
            #EXT-X-PROGRAM-DATE-TIME:2026-10-06T12:00:00.000Z
            #EXTINF:2.000,live
            a.ts
            #EXTINF:2.000,mystery
            b.ts
            #EXTINF:2.000,mystery
            c.ts
            #EXTINF:2.000,mystery
            d.ts
        """.trimIndent()
        assertEquals(listOf(false, true, true, false), TwitchAds.adSegments(m3u8))
        assertFalse(TwitchAds.inBreak(m3u8))
    }

    @Test
    fun renamedLiveSegmentsStillPlay() {
        // Only positive markers count: unfamiliar titles without any ad markup aren't ads
        assertFalse(TwitchAds.hasAds(playlist("Live" to "a.ts", "" to "b.ts", "broadcast" to "c.ts")))
    }

    @Test
    fun aBreakOnAirIsAtTheLiveEdge() {
        assertTrue(TwitchAds.inBreak(playlist("live" to "a.ts", "Amazon|1" to "ad.ts")))
        assertFalse(TwitchAds.inBreak(playlist("Amazon|1" to "ad.ts", "live" to "a.ts")))
    }

    @Test
    fun stripTurnsAdsIntoGapsAndKeepsTheTimeline() {
        val stripped = TwitchAds.strip(playlist("Amazon|1" to "ad1.ts", "Amazon|1" to "ad2.ts", "live" to "a.ts", "live" to "b.ts"))
        // MEDIA-SEQUENCE and every segment stay, so the player never falls behind the live window
        assertTrue(stripped.contains("#EXT-X-MEDIA-SEQUENCE:100"))
        assertEquals(4, stripped.lines().count { it.endsWith(".ts") })
        val lines = stripped.lines()
        assertEquals("#EXT-X-GAP", lines[lines.indexOf("ad1.ts") - 1])
        assertEquals("#EXT-X-GAP", lines[lines.indexOf("ad2.ts") - 1])
        assertFalse(lines[lines.indexOf("a.ts") - 1] == "#EXT-X-GAP")
        assertEquals(2, lines.count { it == "#EXT-X-GAP" })
    }

    @Test
    fun stripMarksMidRollAdsWithoutTruncating() {
        val stripped = TwitchAds.strip(playlist("live" to "a.ts", "Amazon|1" to "ad.ts", "live" to "c.ts"))
        val lines = stripped.lines()
        assertTrue(lines.contains("c.ts"))
        assertEquals("#EXT-X-GAP", lines[lines.indexOf("ad.ts") - 1])
        assertEquals(1, lines.count { it == "#EXT-X-GAP" })
    }

    @Test
    fun stripDropsPrerollStartAndAdMarkup() {
        val stripped = TwitchAds.strip(preroll)
        assertFalse(stripped.contains("#EXT-X-START"))
        assertFalse(stripped.contains("PREFETCH"))
        assertFalse(stripped.contains("twitch-stitched-ad"))
        assertFalse(stripped.contains("twitch-ad-quartile"))
        assertTrue(stripped.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertEquals(3, stripped.lines().count { it == "#EXT-X-GAP" })
        assertTrue(stripped.contains("live2.ts"))
    }

    @Test
    fun cleanPlaylistsPassThroughStrip() {
        val clean = playlist("live" to "a.ts", "live" to "b.ts")
        assertEquals(clean, TwitchAds.strip(clean))
    }

    @Test
    fun readsMediaSequence() {
        assertEquals(100L, TwitchAds.mediaSequence(playlist("live" to "a.ts")))
        assertEquals(0L, TwitchAds.mediaSequence(preroll))
    }
}
