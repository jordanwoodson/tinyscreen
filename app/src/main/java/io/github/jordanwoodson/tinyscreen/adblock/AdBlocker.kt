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
    /** Blocked along with all of their subdomains. */
    private val blockedHosts = setOf(
        // Google ads / IMA / analytics
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "imasdk.googleapis.com", "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "fundingchoicesmessages.google.com",
        // Amazon / Twitch ads and telemetry
        "amazon-adsystem.com", "ads.twitch.tv", "spade.twitch.tv", "countess.twitch.tv",
        "client-event-reporter.twitch.tv", "scorecardresearch.com",
        // Kick and Rumble ads
        "adsystem.kick.com", "ads.kick.com", "ads.rmbl.ws",
        // Common ad exchanges / trackers
        "adnxs.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com", "openx.net", "criteo.com",
        "criteo.net", "casalemedia.com", "taboola.com", "outbrain.com", "moatads.com", "adsafeprotected.com",
        "doubleverify.com", "quantserve.com", "quantcount.com", "media.net", "advertising.com",
        "yieldmo.com", "sharethrough.com", "33across.com", "smartadserver.com", "teads.tv", "spotxchange.com",
        "springserve.com", "lijit.com", "sovrn.com", "indexww.com", "bidswitch.net", "contextweb.com",
        "adform.net", "3lift.com", "triplelift.com", "gumgum.com", "zemanta.com", "revcontent.com",
        "mgid.com", "hotjar.com", "mixpanel.com", "segment.io", "segment.com", "branch.io", "amplitude.com",
        "facebook.net", "ads-twitter.com", "analytics.twitter.com", "ads.reddit.com",
        "bat.bing.com", "clarity.ms",
    )

    /** Ad paths on hosts that also serve the players themselves. */
    private val blockedPathPrefixes = mapOf(
        "rumble.com" to listOf("/ads"),
    )

    /**
     * Ad endpoints on any host (YouTube's in particular). Matched against the path alone, so a host
     * or a query parameter that merely mentions one isn't blocked.
     */
    private val blockedPathParts = listOf(
        "/pagead/", "/api/stats/ads", "/ptracking", "/get_midroll_", "/ad_break", "/adserver", "/ad_status",
    )

    fun shouldBlock(url: Uri): Boolean {
        val host = url.host?.lowercase()?.removeSuffix(".") ?: return false
        fun under(domain: String) = host == domain || host.endsWith(".$domain")
        if (blockedHosts.any(::under)) return true

        val path = url.path.orEmpty().lowercase()
        if (blockedPathPrefixes.any { (domain, prefixes) -> under(domain) && prefixes.any { path.startsWith(it) } }) return true
        if (blockedPathParts.any { path.contains(it) }) return true

        // VAST/VMAP ad requests and ad calls: .../ads?…, .../vast?…, .../vmap(.xml)
        val last = url.lastPathSegment.orEmpty().lowercase()
        val hasQuery = !url.encodedQuery.isNullOrEmpty()
        if ((last == "ads" || last == "vast") && hasQuery) return true
        if (last == "vmap" || last.startsWith("vmap.")) return true
        return runCatching { url.getQueryParameter("ad_type") != null }.getOrDefault(false)
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

    /**
     * Adds [COSMETIC_CSS] and skips YouTube ads. Safe to run more than once, and at document start
     * (inside player iframes), before the page has an `<html>` element to add the style to.
     */
    val cosmeticScript: String = """
        (function(){
          function addStyle(){
            if (document.getElementById('tinyscreen-adblock')) return;
            var s = document.createElement('style'); s.id = 'tinyscreen-adblock';
            s.textContent = ${"`"}${COSMETIC_CSS.trimIndent()}${"`"};
            (document.head || document.documentElement).appendChild(s);
          }
          if (document.documentElement) addStyle();
          else new MutationObserver(function(_, o){
            if (document.documentElement) { o.disconnect(); addStyle(); }
          }).observe(document, { childList: true });
          if (window.__tinyscreenAdblock) return;
          window.__tinyscreenAdblock = true;
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
