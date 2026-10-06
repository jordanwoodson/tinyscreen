package io.github.jordanwoodson.tinyscreen

import android.net.Uri
import io.github.jordanwoodson.tinyscreen.adblock.AdBlocker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdBlockerTest {
    private fun blocks(url: String) = AdBlocker.shouldBlock(Uri.parse(url))

    @Test
    fun blocksAdHostsAndTheirSubdomains() {
        assertTrue(blocks("https://doubleclick.net/x"))
        assertTrue(blocks("https://securepubads.g.doubleclick.net/tag/js/gpt.js"))
        assertTrue(blocks("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(blocks("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
        assertTrue(blocks("https://aax.amazon-adsystem.com/e/dtb/bid"))
        assertTrue(blocks("https://spade.twitch.tv/track"))
        assertTrue(blocks("https://a.ads.rmbl.ws/v"))
        assertTrue(blocks("https://WWW.Google-Analytics.com/analytics.js"))
    }

    @Test
    fun doesNotBlockLookalikeHosts() {
        assertFalse(blocks("https://notdoubleclick.net/"))
        assertFalse(blocks("https://doubleclick.net.example.com/"))
        assertFalse(blocks("https://myads.twitch.tv.example.com/"))
    }

    @Test
    fun blocksAdPaths() {
        assertTrue(blocks("https://www.youtube.com/pagead/viewthroughconversion/1"))
        assertTrue(blocks("https://www.youtube.com/api/stats/ads?ver=2"))
        assertTrue(blocks("https://www.youtube.com/ptracking?pltype=content"))
        assertTrue(blocks("https://www.youtube.com/get_midroll_info?ei=1"))
        assertTrue(blocks("https://rumble.com/ads/vast.php"))
        assertTrue(blocks("https://ads.example.com/serve/vast?id=1"))
        assertTrue(blocks("https://example.com/vmap.xml"))
        assertTrue(blocks("https://example.com/request?slot=1&ad_type=video"))
    }

    @Test
    fun leavesPlayersChatAndLoginAlone() {
        assertFalse(blocks("https://www.destiny.gg/embed/chat?follow=%2Fbigscreen"))
        assertFalse(blocks("https://www.destiny.gg/login"))
        assertFalse(blocks("https://player.twitch.tv/?channel=destiny&parent=www.destiny.gg"))
        assertFalse(blocks("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1"))
        assertFalse(blocks("https://player.kick.com/destiny?autoplay=true"))
        assertFalse(blocks("https://rumble.com/embed/v5qi58n"))
        assertFalse(blocks("https://rumble.com/v5qi58n-some-title.html"))
        assertFalse(blocks("https://id.twitch.tv/oauth2/authorize?client_id=x"))
        assertFalse(blocks("https://accounts.google.com/o/oauth2/v2/auth?client_id=x"))
    }

    @Test
    fun matchesPathsNotMentionsElsewhereInTheUrl() {
        // The old substring rules matched these
        assertFalse(blocks("https://accounts.google.com/signin?continue=https://example.com/ads?x=1"))
        assertFalse(blocks("https://example.com/uploads?file=1"))
        assertFalse(blocks("https://example.com/vmaps-of-the-world"))
        assertFalse(blocks("https://example.com/watch#/pagead/"))
        assertFalse(blocks("https://doubleclick-news.example.com/"))
        assertFalse(blocks("https://example.com/ads"))
    }

    @Test
    fun ignoresUrlsWithoutHosts() {
        assertFalse(blocks("about:blank"))
        assertFalse(blocks("data:text/html,hello"))
    }
}
