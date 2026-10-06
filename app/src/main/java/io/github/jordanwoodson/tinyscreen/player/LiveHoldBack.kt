package io.github.jordanwoodson.tinyscreen.player

import android.net.Uri
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Low latency without running dry. Media3 reloads a live playlist only once per TARGETDURATION, so
 * sitting closer to the newest segment than that (Kick's 4 s against its 6 s reloads and 2–4 s
 * segments) empties the buffer between reloads. Declares a HOLD-BACK of one target duration plus a
 * segment and a half, and at least the platform's own offset, which Media3 uses as its live target.
 */
class LiveHoldBack(private val minimumMs: Long) : PlaylistRewriter {
    override fun rewrite(uri: Uri, playlist: String): String? {
        // Media playlists of live streams only, and never over the server's own low-latency settings
        if ("#EXTINF" !in playlist || "#EXT-X-ENDLIST" in playlist || "#EXT-X-SERVER-CONTROL" in playlist) return null
        val target = TARGET_DURATION.find(playlist) ?: return null
        val targetSeconds = target.groupValues[1].toDoubleOrNull() ?: return null
        val longest = SEGMENT_DURATION.findAll(playlist).mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: targetSeconds
        val holdBack = holdBackSeconds(targetSeconds, longest, minimumMs)
        return playlist.replaceRange(target.range, "${target.value}\n#EXT-X-SERVER-CONTROL:HOLD-BACK=${"%.1f".format(Locale.US, holdBack)}")
    }

    companion object {
        private val TARGET_DURATION = Regex("""^#EXT-X-TARGETDURATION:(\d+(?:\.\d+)?)""", RegexOption.MULTILINE)
        private val SEGMENT_DURATION = Regex("""^#EXTINF:(\d+(?:\.\d+)?)""", RegexOption.MULTILINE)

        /** Rounded up to half seconds, so small differences between segments don't move the target. */
        fun holdBackSeconds(targetDuration: Double, longestSegment: Double, minimumMs: Long): Double =
            max(minimumMs / 1000.0, ceil((targetDuration + 1.5 * longestSegment) * 2) / 2)
    }
}
