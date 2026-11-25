package io.github.jordanwoodson.tinyscreen.stream.resolvers

fun buildStreamResolvers() = StreamResolvers(listOf(KickResolver(), KickVodResolver(), TwitchResolver(), YouTubeResolver(), RumbleResolver()))
