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
        val isPlaylist = uri.path?.endsWith(".m3u8") == true || uri.toString().contains(".m3u8")
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
        val original = out.toString(Charsets.UTF_8.name())
        val rewritten = runCatching { active.rewrite(uri, original) }.getOrNull() ?: original
        val bytes = rewritten.toByteArray(Charsets.UTF_8)
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

    override fun getUri(): Uri? = if (passthrough) upstream.uri else bufferedUri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (passthrough) upstream.responseHeaders else emptyMap()

    override fun close() {
        if (passthrough) upstream.close()
        buffer = null
        position = 0
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val rewriter: () -> PlaylistRewriter?,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            PlaylistRewritingDataSource(upstream.createDataSource(), rewriter)
    }
}
