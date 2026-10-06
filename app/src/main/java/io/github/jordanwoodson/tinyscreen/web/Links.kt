package io.github.jordanwoodson.tinyscreen.web

import android.net.Uri
import io.github.jordanwoodson.tinyscreen.net.isDestinyHost

private val urlInText = Regex("""https?://\S+""")

/** The first web link in some shared text, if there is one. */
fun firstUrl(text: String): String? = urlInText.find(text)?.value

/**
 * A destiny.gg/bigscreen link. Tinyscreen claims these itself, so one without a `#platform/id` embed
 * means "show Destiny", and it must never be handed back to Android, which could give it straight back.
 */
fun isBigscreenLink(url: String): Boolean {
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
    return uri.scheme?.lowercase() in setOf("http", "https") &&
        isDestinyHost(uri.host) &&
        uri.path.orEmpty().startsWith("/bigscreen")
}
