package io.github.jordanwoodson.tinyscreen

import android.webkit.CookieManager
import io.github.jordanwoodson.tinyscreen.net.WebViewCookieJar
import io.github.jordanwoodson.tinyscreen.net.isDestinyHost
import io.github.jordanwoodson.tinyscreen.web.firstUrl
import io.github.jordanwoodson.tinyscreen.web.isBigscreenLink
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HostChecksTest {
    @Test
    fun destinyHostIsExactOrASubdomain() {
        assertTrue(isDestinyHost("destiny.gg"))
        assertTrue(isDestinyHost("www.destiny.gg"))
        assertTrue(isDestinyHost("WWW.Destiny.GG"))
        assertTrue(isDestinyHost("cdn.destiny.gg."))
        assertFalse(isDestinyHost("xdestiny.gg"))
        assertFalse(isDestinyHost("evil-destiny.gg"))
        assertFalse(isDestinyHost("destiny.gg.example.com"))
        assertFalse(isDestinyHost("gg"))
        assertFalse(isDestinyHost(""))
        assertFalse(isDestinyHost(null))
    }

    @Test
    fun recognisesBigscreenLinksWithOrWithoutAnEmbed() {
        assertTrue(isBigscreenLink("https://www.destiny.gg/bigscreen"))
        assertTrue(isBigscreenLink("https://destiny.gg/bigscreen#kick/destiny"))
        assertTrue(isBigscreenLink("http://www.destiny.gg/bigscreen?x=1"))
        assertFalse(isBigscreenLink("https://www.destiny.gg/embed/chat"))
        assertFalse(isBigscreenLink("https://xdestiny.gg/bigscreen"))
        assertFalse(isBigscreenLink("https://example.com/bigscreen"))
        assertFalse(isBigscreenLink("javascript:alert(1)//www.destiny.gg/bigscreen"))
    }

    @Test
    fun findsTheLinkInSharedText() {
        assertEquals("https://www.destiny.gg/bigscreen", firstUrl("watch this https://www.destiny.gg/bigscreen now"))
        assertNull(firstUrl("no link here"))
    }

    // One test, because the jar keeps the first CookieManager it sees and Robolectric makes a new one per test
    @Test
    fun cookieJarOnlySharesDestinyGgCookies() {
        val manager = CookieManager.getInstance()
        manager.setCookie("https://xdestiny.gg", "sid=lookalike")
        manager.setCookie("https://www.destiny.gg", "sid=real")

        assertTrue(WebViewCookieJar.loadForRequest("https://xdestiny.gg/embed/chat".toHttpUrl()).isEmpty())
        val sent = WebViewCookieJar.loadForRequest("https://www.destiny.gg/api/chat/me".toHttpUrl())
        assertEquals(listOf("sid" to "real"), sent.map { it.name to it.value })

        val evil = "https://evil-destiny.gg/".toHttpUrl()
        WebViewCookieJar.saveFromResponse(evil, listOf(Cookie.parse(evil, "rememberme=stolen")!!))
        assertNull(manager.getCookie("https://evil-destiny.gg"))

        val dgg = "https://www.destiny.gg/".toHttpUrl()
        WebViewCookieJar.saveFromResponse(dgg, listOf(Cookie.parse(dgg, "rememberme=ok")!!))
        assertTrue(manager.getCookie("https://www.destiny.gg").orEmpty().contains("rememberme=ok"))
    }
}
