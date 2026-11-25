package io.github.jordanwoodson.tinyscreen.web

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

import android.util.Base64

/** Serve the two fixture iframes locally under their normal player origin, including the media bridge. */
internal fun playerRequestInterceptor(context: Context): (WebResourceRequest) -> WebResourceResponse? {
    val video = context.assets.open("playback.mp4").use { Base64.encodeToString(it.readBytes(), Base64.NO_WRAP) }
    val page = """
        <!doctype html><meta name="viewport" content="width=device-width">
        <body style="background:black;color:white"><div id="status"></div>
        <video autoplay muted playsinline style="width:100%;height:65vh" src="data:video/mp4;base64,$video"></video>
        <script>
        const load = Number(sessionStorage.loads || 0) + 1;
        sessionStorage.loads = load;
        const media = document.querySelector('video');
        function report() {
          document.getElementById('status').textContent = 'Fixture ' + load + ' ' +
            (media.paused ? 'paused' : 'playing') + ' at ' + Math.floor(media.currentTime);
        }
        ['loadedmetadata','playing','pause','timeupdate','seeked'].forEach(e => media.addEventListener(e, report));
        </script></body>
    """.trimIndent().toByteArray()
    return { request ->
        if (request.url.host == "www.youtube.com" && request.url.path in setOf("/embed/TinyFixture1", "/embed/TinyFixture2")) {
            WebResourceResponse("text/html", "utf-8", page.inputStream())
        } else null
    }
}
