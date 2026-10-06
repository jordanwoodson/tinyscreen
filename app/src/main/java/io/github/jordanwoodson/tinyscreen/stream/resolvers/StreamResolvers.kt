package io.github.jordanwoodson.tinyscreen.stream.resolvers

import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.stream.Embed

/** Turns an embed into something ExoPlayer can play directly. */
interface StreamResolver {
    val platforms: Set<String>
    suspend fun resolve(embed: Embed): ResolvedStream
}

class StreamResolvers(private val resolvers: List<StreamResolver>) {
    fun supports(embed: Embed): Boolean = resolvers.any { embed.platform in it.platforms }

    /** Null when no native resolver handles the platform; throws when resolving fails. */
    suspend fun resolve(embed: Embed): ResolvedStream? =
        resolvers.firstOrNull { embed.platform in it.platforms }?.resolve(embed)
}
