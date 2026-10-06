package io.github.jordanwoodson.tinyscreen

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import io.github.jordanwoodson.tinyscreen.player.ChainedRewriter
import io.github.jordanwoodson.tinyscreen.player.LiveHoldBack
import io.github.jordanwoodson.tinyscreen.player.PlaylistRewriter
import io.github.jordanwoodson.tinyscreen.player.PlaylistRewritingDataSource
import io.github.jordanwoodson.tinyscreen.player.liveEdgeThresholdMs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@OptIn(UnstableApi::class)
class PlaylistRewriterTest {
    private val uri = Uri.parse("https://example.com/live/index.m3u8")

    private fun live(target: Int, vararg durations: Double) = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:3")
        appendLine("#EXT-X-TARGETDURATION:$target")
        appendLine("#EXT-X-MEDIA-SEQUENCE:10")
        durations.forEachIndexed { i, d ->
            appendLine("#EXTINF:$d,live")
            appendLine("seg$i.ts")
        }
    }

    @Test
    fun holdBackCoversAReloadIntervalAndASegmentAndAHalf() {
        // Kick and Twitch: 6 s target, 2 s segments
        assertEquals(9.0, LiveHoldBack.holdBackSeconds(6.0, 2.0, 4_000), 0.0)
        // Kick with 4.24 s segments, the case that used to sit inside a single segment
        assertEquals(11.5, LiveHoldBack.holdBackSeconds(5.0, 4.24, 4_000), 0.0)
        // YouTube: 5 s segments
        assertEquals(12.5, LiveHoldBack.holdBackSeconds(5.0, 5.0, 10_000), 0.0)
        // Never below the platform's own offset
        assertEquals(20.0, LiveHoldBack.holdBackSeconds(2.0, 2.0, 20_000), 0.0)
    }

    @Test
    fun declaresHoldBackOnLiveMediaPlaylists() {
        val out = LiveHoldBack(4_000).rewrite(uri, live(6, 2.0, 2.0, 2.002))!!
        val lines = out.lines()
        val target = lines.indexOf("#EXT-X-TARGETDURATION:6")
        assertEquals("#EXT-X-SERVER-CONTROL:HOLD-BACK=9.5", lines[target + 1])
        assertEquals(6, lines.count { it.endsWith(".ts") || it.startsWith("#EXTINF") })
    }

    @Test
    fun leavesVodMastersAndServerControlledPlaylistsAlone() {
        val holdBack = LiveHoldBack(4_000)
        assertNull(holdBack.rewrite(uri, live(6, 2.0) + "#EXT-X-ENDLIST\n"))
        assertNull(holdBack.rewrite(uri, "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow.m3u8\n"))
        assertNull(holdBack.rewrite(uri, live(6, 2.0).replace("#EXT-X-VERSION:3", "#EXT-X-SERVER-CONTROL:HOLD-BACK=3")))
    }

    @Test
    fun chainAppliesEachRewriterThatHandlesTheUri() {
        val twitchOnly = object : PlaylistRewriter {
            override fun handles(uri: Uri) = uri.host == "twitch.example"
            override fun rewrite(uri: Uri, playlist: String) = playlist + "#TWITCH\n"
        }
        val everywhere = PlaylistRewriter { _, playlist -> playlist + "#ALL\n" }
        val chain = ChainedRewriter(listOf(twitchOnly, everywhere))
        assertEquals("x\n#TWITCH\n#ALL\n", chain.rewrite(Uri.parse("https://twitch.example/a.m3u8"), "x\n"))
        assertEquals("x\n#ALL\n", chain.rewrite(uri, "x\n"))
    }

    private fun readThrough(url: String, data: ByteArray, rewriter: PlaylistRewriter): ByteArray {
        val source = PlaylistRewritingDataSource(ByteArrayDataSource(data)) { rewriter }
        source.open(DataSpec(Uri.parse(url)))
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        source.close()
        return out.toByteArray()
    }

    @Test
    fun rewritesPlaylistsButPassesSegmentsThroughUntouched() {
        val marker = PlaylistRewriter { _, playlist -> playlist + "#MARKED\n" }
        val playlist = live(6, 2.0).toByteArray()
        assertTrue(String(readThrough("https://example.com/v1/playlist/abc.m3u8", playlist, marker)).endsWith("#MARKED\n"))

        // Not valid UTF-8: a round trip through String would corrupt it
        val segment = ByteArray(4096) { (it * 31 + 0x80).toByte() }
        // YouTube's segment URLs carry ".m3u8" in the middle of the path
        val youtubeSegment = "https://rr2.googlevideo.com/videoplayback/id/x/playlist/index.m3u8/sq/85296/file/seg.ts"
        assertArrayEquals(segment, readThrough(youtubeSegment, segment, marker))
        // And something binary at a playlist-looking URL still isn't touched
        assertArrayEquals(segment, readThrough("https://example.com/odd.m3u8", segment, marker))
    }

    @Test
    fun liveEdgeFollowsThePlayersTarget() {
        assertEquals(12_000, liveEdgeThresholdMs(C.TIME_UNSET))
        // Low latency off on Twitch: Media3 aims for 18 s, which must still count as live
        assertTrue(18_000 < liveEdgeThresholdMs(18_000))
    }
}
