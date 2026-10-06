package io.github.jordanwoodson.tinyscreen.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/** Rewrites text playlists before ExoPlayer parses them (used to strip stitched ads). */
fun interface PlaylistRewriter {
    /** Returns the rewritten playlist, or null when [uri] isn't a playlist this rewriter handles. */
    fun rewrite(uri: Uri, playlist: String): String?

    fun handles(uri: Uri): Boolean = true
}

/** Applies [rewriters] in order, each to the playlists it handles. */
class ChainedRewriter(private val rewriters: List<PlaylistRewriter>) : PlaylistRewriter {
    override fun handles(uri: Uri): Boolean = rewriters.any { it.handles(uri) }

    override fun rewrite(uri: Uri, playlist: String): String =
        rewriters.fold(playlist) { text, r -> if (r.handles(uri)) r.rewrite(uri, text) ?: text else text }
}

@UnstableApi
class PlaylistRewritingDataSource(
    private val upstream: DataSource,
    private val rewriter: () -> PlaylistRewriter?,
) : DataSource {
    private var buffer: ByteArray? = null
    private var position = 0
    private var bufferedUri: Uri? = null
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val active = rewriter()
        val uri = dataSpec.uri
        // By path: YouTube's segment URLs have ".../index.m3u8/sq/..." in the middle
        val isPlaylist = uri.path?.endsWith(".m3u8") == true
        if (active == null || !isPlaylist || !active.handles(uri)) {
            passthrough = true
            return upstream.open(dataSpec)
        }
        passthrough = false
        upstream.open(dataSpec)
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        try {
            while (true) {
                val read = upstream.read(chunk, 0, chunk.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(chunk, 0, read)
            }
        } finally {
            bufferedUri = upstream.uri
            upstream.close()
        }
        val raw = out.toByteArray()
        // Only text playlists are rewritten; anything else goes through byte for byte
        val bytes = if (isTextPlaylist(raw)) {
            val original = String(raw, Charsets.UTF_8)
            (runCatching { active.rewrite(uri, original) }.getOrNull() ?: original).toByteArray(Charsets.UTF_8)
        } else {
            raw
        }
        val skip = dataSpec.position.coerceIn(0, bytes.size.toLong()).toInt()
        buffer = bytes
        position = skip
        return (bytes.size - skip).toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(target, offset, length)
        val data = buffer ?: return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (position >= data.size) return C.RESULT_END_OF_INPUT
        val count = minOf(length, data.size - position)
        System.arraycopy(data, position, target, offset, count)
        position += count
        return count
    }

    private fun isTextPlaylist(data: ByteArray): Boolean {
        var i = 0
        // A UTF-8 byte order mark and leading whitespace are tolerated
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) i = 3
        while (i < data.size && data[i].toInt().toChar().isWhitespace()) i++
        return data.size - i >= MAGIC.size && MAGIC.indices.all { data[i + it] == MAGIC[it] }
    }

    override fun getUri(): Uri? = if (passthrough) upstream.uri else bufferedUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (passthrough) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (passthrough) upstream.close()
        buffer = null
        position = 0
    }

    private companion object {
        val MAGIC = "#EXTM3U".toByteArray(Charsets.US_ASCII)
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val rewriter: () -> PlaylistRewriter?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PlaylistRewritingDataSource(upstream.createDataSource(), rewriter)
    }
}
