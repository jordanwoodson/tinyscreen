package io.github.jordanwoodson.tinyscreen.adblock

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/**
 * Host-based blocker for WebViews (web players, login, web chat). Native playback never
 * loads ads in the first place; Twitch's stitched ads are stripped in [io.github.jordanwoodson.tinyscreen.player.TwitchAdFilter].
 */
object AdBlocker {
    private val blockedHosts = setOf(
        // Google ads / IMA / analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "pagead2.googlesyndication.com", "imasdk.googleapis.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "partnerad.l.doubleclick.net",
        "securepubads.g.doubleclick.net", "tpc.googlesyndication.com", "fundingchoicesmessages.google.com",
        // Amazon / Twitch ads
        "amazon-adsystem.com", "aax.amazon-adsystem.com", "c.amazon-adsystem.com",
        "ads.twitch.tv", "pubads.g.doubleclick.net", "spade.twitch.tv", "countess.twitch.tv",
        "client-event-reporter.twitch.tv", "sb.scorecardresearch.com", "scorecardresearch.com",
        // Common ad exchanges / trackers
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "casalemedia.com", "taboola.com", "outbrain.com", "moatads.com", "adsafeprotected.com",
        "doubleverify.com", "quantserve.com", "quantcount.com", "media.net", "advertising.com",
        "yieldmo.com", "sharethrough.com", "33across.com", "smartadserver.com", "teads.tv", "spotxchange.com",
        "springserve.com", "lijit.com", "sovrn.com", "indexww.com", "bidswitch.net", "contextweb.com",
        "adform.net", "3lift.com", "triplelift.com", "gumgum.com", "zemanta.com", "revcontent.com",
        "mgid.com", "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "branch.io", "amplitude.com",
        "facebook.net", "connect.facebook.net", "ads-twitter.com", "static.ads-twitter.com", "analytics.twitter.com",
        "ads.reddit.com", "rumble.com/ads", "a.ads.rmbl.ws", "ads.rmbl.ws", "imasdk.googleapis.com",
        "adsystem.kick.com", "ads.kick.com", "kick-ads", "ad.doubleclick.net", "stats.g.doubleclick.net",
        "bat.bing.com", "clarity.ms", "cdn.taboola.com", "s.ytimg.com/yts/jsbin/www-pagead",
        "youtube.com/api/stats/ads", "youtube.com/pagead", "youtube.com/ptracking", "googleads.g.doubleclick.net",
    )

    private val blockedPathMarkers = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/ad_break", "doubleclick",
        "/ads?", "/adserver", "/ad_status", "&ad_type=", "/vast?", "/vmap",
    )

    fun shouldBlock(url: Uri): Boolean {
        val host = url.host?.lowercase() ?: return false
        if (blockedHosts.any { blocked ->
                if (blocked.contains('/')) url.toString().contains(blocked) else host == blocked || host.endsWith(".$blocked")
            }
        ) return true
        val full = url.toString()
        return blockedPathMarkers.any { full.contains(it) }
    }

    fun intercept(request: WebResourceRequest, enabled: Boolean): WebResourceResponse? {
        if (!enabled || !shouldBlock(request.url)) return null
        return WebResourceResponse("text/plain", "utf-8", 204, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    /** Cosmetic rules injected into embedded pages to hide leftover ad containers. */
    const val COSMETIC_CSS = """
        .ad-showing .video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay,
        #player-ads, .ad-container, .ad-div, .ad-banner, [id^="google_ads"], [class*="AdBanner"],
        [data-a-target="video-ad-label"], [data-test-selector="sad-overlay"], .stream-display-ad__wrapper,
        .ytp-paid-content-overlay, .ytp-suggested-action, .ytp-ce-element { display: none !important; }
    """

    val cosmeticScript: String = """
        (function(){
          if (document.getElementById('tinyscreen-adblock')) return;
          var s = document.createElement('style'); s.id = 'tinyscreen-adblock';
          s.textContent = ${"`"}${COSMETIC_CSS.trimIndent()}${"`"};
          (document.head || document.documentElement).appendChild(s);
          // Skip YouTube ads that slip through: jump to the end and click skip
          setInterval(function(){
            var v = document.querySelector('.ad-showing video');
            if (v && isFinite(v.duration)) { v.currentTime = v.duration; }
            var b = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
            if (b) b.click();
          }, 500);
        })();
    """.trimIndent()
}
