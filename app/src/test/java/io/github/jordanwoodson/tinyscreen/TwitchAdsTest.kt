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

    @Test
    fun cleanPlaylistHasNoAds() {
        assertFalse(TwitchAds.hasAds(playlist("live" to "a.ts", "live" to "b.ts")))
    }

    @Test
    fun detectsStitchedAds() {
        assertTrue(TwitchAds.hasAds(playlist("Amazon|123" to "ad.ts", "live" to "b.ts")))
    }

    @Test
    fun stripsLeadingAdsAndAdvancesSequence() {
        val stripped = TwitchAds.strip(playlist("Amazon|1" to "ad1.ts", "Amazon|1" to "ad2.ts", "live" to "a.ts", "live" to "b.ts"))
        assertTrue(stripped.contains("#EXT-X-MEDIA-SEQUENCE:102"))
        assertFalse(stripped.contains("ad1.ts"))
        assertTrue(stripped.contains("a.ts") && stripped.contains("b.ts"))
        assertFalse(TwitchAds.hasAds(stripped))
    }

    @Test
    fun truncatesAtFirstTrailingAd() {
        val stripped = TwitchAds.strip(playlist("live" to "a.ts", "live" to "b.ts", "Amazon|1" to "ad.ts", "live" to "c.ts"))
        assertTrue(stripped.contains("#EXT-X-MEDIA-SEQUENCE:100"))
        assertTrue(stripped.contains("b.ts"))
        assertFalse(stripped.contains("ad.ts"))
        // Never keep segments after an ad: that would renumber them
        assertFalse(stripped.contains("c.ts"))
    }

    @Test
    fun allAdsLeavesOnlyTheHeader() {
        val stripped = TwitchAds.strip(playlist("Amazon|1" to "ad1.ts", "Amazon|1" to "ad2.ts"))
        assertEquals(0, stripped.lines().count { it.endsWith(".ts") })
        assertTrue(stripped.startsWith("#EXTM3U"))
    }
}
