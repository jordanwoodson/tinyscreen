package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.web.iframeHtml
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IframeHtmlTest {
    private fun src(html: String) = Regex("""<iframe src="([^"]*)"""").find(html)!!.groupValues[1]

    @Test
    fun keepsPlayerUrlsIntactOnceUnescaped() {
        val url = "https://player.twitch.tv/?channel=destiny&parent=www.destiny.gg&parent=stage.destiny.gg"
        val html = iframeHtml(url)
        assertTrue(src(html) == url.replace("&", "&amp;"))
    }

    @Test
    fun cannotBreakOutOfTheSrcAttribute() {
        val html = iframeHtml("https://example.com/\" onload=\"alert(1)\"><script>alert(2)</script>'")
        val value = src(html)
        assertFalse(value.contains('"'))
        assertFalse(value.contains('<'))
        assertFalse(value.contains('>'))
        assertFalse(value.contains('\''))
        assertFalse(html.contains("<script>"))
        assertFalse(html.contains("onload=\""))
    }

    @Test
    fun onlyLoadsHttpsPlayers() {
        assertTrue(src(iframeHtml("javascript:alert(1)")) == "about:blank")
        assertTrue(src(iframeHtml("http://player.vimeo.com/video/1")) == "about:blank")
        assertTrue(src(iframeHtml("data:text/html,<b>hi</b>")) == "about:blank")
        assertTrue(src(iframeHtml("HTTPS://player.vimeo.com/video/1")).startsWith("HTTPS://"))
    }
}
