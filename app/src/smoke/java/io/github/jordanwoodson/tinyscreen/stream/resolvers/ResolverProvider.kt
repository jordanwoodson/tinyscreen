package io.github.jordanwoodson.tinyscreen.stream.resolvers

import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.stream.Embed
import java.util.concurrent.atomic.AtomicBoolean

/** Local media and a reproducible first-attempt failure for the minified emulator build. */
fun buildStreamResolvers() = StreamResolvers(listOf(object : StreamResolver {
    override val platforms = setOf("youtube")
    private val first = AtomicBoolean(true)

    override suspend fun resolve(embed: Embed): ResolvedStream {
        require(embed.id in setOf("TinyFixture1", "TinyFixture2"))
        if (embed.id == "TinyFixture1" && embed.startSeconds == 30 && first.getAndSet(false)) error("Fixture provider temporarily unavailable")
        return ResolvedStream(
            "asset:///playback.mp4", StreamKind.Progressive, isLive = false,
            title = "Playback fixture ${embed.id.last()}", startPositionMs = (embed.startSeconds ?: 0) * 1000L,
        )
    }
}))
