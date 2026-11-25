package io.github.jordanwoodson.tinyscreen.ui

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.player.ContinueWatching
import io.github.jordanwoodson.tinyscreen.player.PlaybackCheckpoint
import io.github.jordanwoodson.tinyscreen.player.RecentVod
import io.github.jordanwoodson.tinyscreen.player.Recovery
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.player.StreamKind
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Hosting
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import io.github.jordanwoodson.tinyscreen.stream.StreamEntry
import io.github.jordanwoodson.tinyscreen.stream.TopEmbed
import io.github.jordanwoodson.tinyscreen.stream.liveEmbed
import io.github.jordanwoodson.tinyscreen.stream.playableEntries
import io.github.jordanwoodson.tinyscreen.stream.resolvers.RumbleResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.StreamOfflineException
import io.github.jordanwoodson.tinyscreen.web.WebMediaState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the stream panel is showing, following the bigscreen page's priority rules. */
sealed interface Content {
    val embed: Embed?

    data class Embedded(override val embed: Embed, val meta: TopEmbed?) : Content
    data class Host(val hosting: Hosting, override val embed: Embed) : Content
    data class Live(val entry: StreamEntry, val allLive: List<StreamEntry>, override val embed: Embed) : Content {
        val premiere get() = entry.type == "premiere"
    }
    data object Offline : Content {
        override val embed: Embed? = null
    }
}

sealed interface Playback {
    data object Idle : Playback
    data class Resolving(val embed: Embed) : Playback
    data class Native(val embed: Embed, val stream: ResolvedStream) : Playback
    data class Web(val embed: Embed, val url: String, val note: String? = null, val generation: Long = 0, val restore: PlaybackCheckpoint? = null) : Playback
    data class Failed(val embed: Embed, val message: String) : Playback
}

/**
 * [saved] survives Android closing the app in the background, so coming back reopens the embed (and
 * VOD position) you were watching rather than Destiny's stream.
 */
class BigscreenViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val graph = (app as TinyscreenApp).graph
    private val live = graph.live
    val settings = graph.settings.settings
    val player = graph.player

    private val history = ContinueWatching(app)
    val recentVods = history.recent
    private var historyClearedFor: Embed? = null
    private val historyListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) savePosition() }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) savePosition()
        }
    }

    private val _hashEmbed = MutableStateFlow(savedEmbed())
    val hashEmbed: StateFlow<Embed?> = _hashEmbed.asStateFlow()

    /** Platform chosen for Destiny's own stream this session (Change Platform). */
    private val _currentPlatform = MutableStateFlow(saved.get<String>(KEY_PLATFORM))

    /** Embeds the user flipped to the web player (or back) by hand. */
    private val _forceWeb = MutableStateFlow(saved.get<ArrayList<String>>(KEY_FORCE_WEB)?.toSet() ?: emptySet())

    /** Where the VOD was when Android closed the app, for when that embed loads again. */
    private var resumeAfterRestore: Pair<String, PlaybackCheckpoint>? = saved.get<Bundle>(KEY_RESUME)?.let { b ->
        b.getString("embed")?.let { it to PlaybackCheckpoint(b.getLong("position"), b.getBoolean("playing", true)) }
    }

    val streamInfo = live.streamInfo
    val embeds = live.embeds
    val latestVod = live.latestVod

    private val failedAutoEmbeds = MutableStateFlow(emptySet<Embed>())
    private var linkJob: Job? = null
    private var webGeneration = 0L
    private var webMedia: WebMediaState? = null
    private val _webVod = MutableStateFlow(false)
    val webVod = _webVod.asStateFlow()

    val content: StateFlow<Content> = combine(
        _hashEmbed, live.streamInfo, live.hosting, live.embeds, combine(_currentPlatform, settings, failedAutoEmbeds) { a, b, c -> Triple(a, b, c) },
    ) { hash, info, hosting, embeds, (current, s, failed) ->
        val streams = info.playableEntries()
        when {
            hash != null -> Content.Embedded(hash, embeds.firstOrNull { hash.matches(it.platform, it.mediaItem?.identifier?.mediaId ?: it.id) })
            streams.isEmpty() && !hosting?.id.isNullOrBlank() && hosting.platform != null ->
                Content.Host(hosting, Embed(hosting.platform, hosting.id))
            streams.isNotEmpty() -> {
                val entry = selectLive(streams, current, s.preferredPlatform, failed)
                Content.Live(entry, streams, entry.liveEmbed()!!)
            }
            else -> Content.Offline
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Content.Offline)

    val blockedReason: StateFlow<String?> = combine(content, live.banned) { c, _ ->
        c.embed?.let { live.bannedReason(it.platform, it.displayId) ?: live.bannedReason(it.platform, it.id) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _playback = MutableStateFlow<Playback>(Playback.Idle)
    val playback: StateFlow<Playback> = _playback.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var resolveJob: Job? = null

    init {
        player.player.addListener(historyListener)
        viewModelScope.launch {
            combine(player.isPlaying, _playback) { playing, playback ->
                playing && playback is Playback.Native && !playback.stream.isLive
            }.distinctUntilChanged().collectLatest { recording ->
                if (recording) while (true) { delay(10_000); savePosition() }
            }
        }
        saved.setSavedStateProvider(KEY_RESUME) {
            savePosition()
            val embed = content.value.embed
            val checkpoint = checkpointFor(embed)
            if (embed == null || checkpoint?.positionMs == null) Bundle() else Bundle().apply {
                putString("embed", embed.hash)
                putLong("position", checkpoint.positionMs)
                putBoolean("playing", checkpoint.playWhenReady)
            }
        }
        live.start()
        viewModelScope.launch {
            player.liveSourceFailures.collect { source ->
                if (source === player.current.value) (_playback.value as? Playback.Native)?.let { tryNextLive(it.embed) }
            }
        }
        viewModelScope.launch {
            live.streamInfo.collect { info ->
                val available = info.live.mapNotNull { it.liveEmbed() }.toSet()
                failedAutoEmbeds.value = failedAutoEmbeds.value.intersect(available)
            }
        }
        viewModelScope.launch {
            player.recovery.collect { state ->
                if (state == Recovery.GaveUp || state == Recovery.Offline) {
                    (_playback.value as? Playback.Native)?.let { tryNextLive(it.embed) }
                }
            }
        }
        // Reload the player only when what should play actually changes
        viewModelScope.launch {
            combine(
                content.map { it.embed }.distinctUntilChanged(),
                blockedReason.map { it != null }.distinctUntilChanged(),
                settings.map { it.nativePlayer }.distinctUntilChanged(),
                _forceWeb,
            ) { embed, blocked, native, forced -> Triple(embed, blocked, native && embed?.hash !in forced) }
                .distinctUntilChanged()
                .collect { (embed, blocked, native) ->
                    live.reportWatching(if (blocked) null else embed)
                    graph.chat.currentEmbed = embed
                    load(embed?.takeUnless { blocked }, native)
                }
        }
        viewModelScope.launch {
            settings.map { it.lowLatency }.distinctUntilChanged().collect { player.lowLatency = it }
        }
        viewModelScope.launch {
            // "Block ads" off plays Twitch playlists untouched
            settings.map { it.adblock }.distinctUntilChanged().collect { player.blockAds = it }
        }
    }

    private fun tryNextLive(embed: Embed): Boolean {
        val showing = content.value as? Content.Live ?: return false
        if (showing.embed != embed || settings.value.preferredPlatform != PlatformPreference.Auto) return false
        val failed = failedAutoEmbeds.value + embed
        if (showing.allLive.none { it.liveEmbed() !in failed }) return false
        failedAutoEmbeds.value = failed
        return true
    }

    /** Preserve the active transport state when a retry or player switch replaces the source. */
    private fun load(embed: Embed?, native: Boolean) {
        val checkpoint = checkpointFor(embed)
        savePosition()
        if (historyClearedFor != embed) historyClearedFor = null
        resolveJob?.cancel()
        // Only the first embed to load after a restore; Destiny's cached stream info may come first
        val restored = if (embed != null) resumeAfterRestore?.takeIf { it.first == embed.hash }?.second else null
        if (embed != null) resumeAfterRestore = null
        val transport = checkpoint ?: restored
        val resumeAtMs = transport?.positionMs ?: C.TIME_UNSET
        val playWhenReady = transport?.playWhenReady ?: true
        if (embed == null) {
            player.stop()
            _playback.value = Playback.Idle
            return
        }
        val web = embed.webEmbedUrl()
        val webRestore = transport ?: embed.startSeconds?.let { PlaybackCheckpoint(it * 1000L, true) }
        if (!native && web != null) {
            player.stop()
            _playback.value = Playback.Web(embed, web, generation = ++webGeneration, restore = webRestore)
            return
        }
        val resolving = Playback.Resolving(embed)
        _playback.value = resolving
        resolveJob = viewModelScope.launch {
            val result = try {
                val entry = (content.value as? Content.Live)?.takeIf { it.embed == embed }?.entry
                val manifest = entry?.id?.takeIf { embed.platform == "kick" && it.startsWith("https://") }
                val stream = if (manifest == null) graph.resolvers.resolve(embed) else ResolvedStream(
                    url = manifest, kind = StreamKind.Hls, isLive = true, title = entry.statusText,
                    subtitle = "${embed.id} · Kick", artworkUrl = entry.preview,
                    refresh = { requireNotNull(graph.resolvers.resolve(embed)) },
                )
                Result.success(stream)
            } catch (e: CancellationException) {
                // Superseded by another stream: it owns the player now
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            ensureActive()
            if (_playback.value !== resolving) return@launch
            val stream = result.getOrNull()
            when {
                stream != null -> {
                    // The media notification's title, when the platform didn't give one
                    val titled = if (stream.title != null) stream else stream.copy(title = titleFor(embed))
                    val source = if (titled.isLive || resumeAtMs == C.TIME_UNSET) titled else titled.copy(startPositionMs = resumeAtMs)
                    player.play(source, playWhenReady)
                    _playback.value = Playback.Native(embed, stream)
                }
                result.isFailure && tryNextLive(embed) -> Unit
                result.exceptionOrNull() is StreamOfflineException -> {
                    // The channel's own page would only show its offline banner
                    player.stop()
                    _playback.value = Playback.Failed(embed, "${embed.displayId} is offline on ${embed.platformName}")
                }
                web != null -> {
                    result.exceptionOrNull()?.let { Log.w(TAG, "native playback failed for ${embed.hash}", it) }
                    player.stop()
                    val note = if (result.isFailure) "Native player couldn't open this stream — using the web player" else null
                    _playback.value = Playback.Web(embed, web, note, ++webGeneration, webRestore)
                }
                else -> {
                    player.stop()
                    val reason = if (embed.platform == "kick-clip") "Watch this clip on Kick"
                        else result.exceptionOrNull()?.message ?: "This stream can't be played"
                    _playback.value = Playback.Failed(embed, reason)
                }
            }
        }
    }

    private fun titleFor(embed: Embed): String {
        val c = content.value
        val title = when (c) {
            is Content.Embedded -> c.meta?.metadata?.title ?: c.meta?.metadata?.displayName
            is Content.Host -> c.hosting.title ?: c.hosting.displayName
            is Content.Live -> c.entry.statusText
            Content.Offline -> null
        }
        return title?.takeIf { c.embed == embed && it.isNotBlank() } ?: embed.displayId
    }

    // --- Actions ---

    fun setEmbed(embed: Embed?) {
        linkJob?.cancel()
        _hashEmbed.value = embed
        saved[KEY_EMBED_PLATFORM] = embed?.platform
        saved[KEY_EMBED_ID] = embed?.id
        saved[KEY_EMBED_START] = embed?.startSeconds
    }

    private fun savedEmbed(): Embed? {
        val platform = saved.get<String>(KEY_EMBED_PLATFORM) ?: return null
        val id = saved.get<String>(KEY_EMBED_ID) ?: return null
        return Embed(platform, id, saved.get<Int>(KEY_EMBED_START))
    }

    private fun setForceWeb(hashes: Set<String>) {
        _forceWeb.value = hashes
        saved[KEY_FORCE_WEB] = ArrayList(hashes)
    }

    /** Opens a link from chat, a share intent or a deep link if it names something playable. */
    fun openLink(link: String): Boolean {
        LinkParser.parse(link)?.let {
            setEmbed(it)
            return true
        }
        val (page, start) = LinkParser.rumblePage(link) ?: return false
        linkJob?.cancel()
        linkJob = viewModelScope.launch {
            try {
                val id = RumbleResolver.embedIdForPage(page)
                ensureActive()
                setEmbed(Embed("rumble", id, start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                Log.w(TAG, "no embed for $page", e)
                _message.value = "Couldn't open that Rumble video"
            }
        }
        return true
    }

    fun closeEmbed() = setEmbed(null)

    fun changePlatform() {
        linkJob?.cancel()
        failedAutoEmbeds.value = emptySet()
        val c = content.value as? Content.Live ?: return
        val platforms = c.allLive.mapNotNull { it.platform }
        if (platforms.size < 2) return
        val next = platforms[(platforms.indexOf(c.entry.platform) + 1) % platforms.size]
        _currentPlatform.value = next
        saved[KEY_PLATFORM] = next
        PlatformPreference.entries.firstOrNull { it.name.equals(next, ignoreCase = true) }?.let { pref ->
            graph.settings.launchUpdate(viewModelScope) { it.copy(preferredPlatform = pref) }
        }
    }

    fun togglePlayerType() {
        val embed = content.value.embed ?: return
        val key = embed.hash
        val usingWeb = _playback.value is Playback.Web
        if (settings.value.nativePlayer) {
            if (usingWeb && key !in _forceWeb.value) {
                load(embed, native = true)
                return
            }
            setForceWeb(if (usingWeb) _forceWeb.value - key else _forceWeb.value + key)
        } else {
            graph.settings.launchUpdate(viewModelScope) { it.copy(nativePlayer = true) }
            setForceWeb(_forceWeb.value - key)
        }
    }

    fun reload() {
        val embed = content.value.embed?.takeUnless { blockedReason.value != null }
        val native = _playback.value !is Playback.Web && settings.value.nativePlayer && embed?.hash !in _forceWeb.value
        load(embed, native)
    }

    private fun checkpointFor(embed: Embed?): PlaybackCheckpoint? = when (val playing = _playback.value) {
        is Playback.Native -> if (playing.embed != embed) null else PlaybackCheckpoint(
            player.player.currentPosition.takeUnless { playing.stream.isLive }, player.player.playWhenReady,
        )
        is Playback.Web -> webMedia?.takeIf { it.generation == playing.generation && embed == playing.embed }?.let {
            PlaybackCheckpoint(it.positionMs.takeUnless { content.value is Content.Live || playing.embed.platform in LIVE_CHANNELS }, it.playing)
        }
        else -> null
    }

    fun onWebMedia(state: WebMediaState?) {
        val previous = webMedia
        webMedia = state
        val web = _playback.value as? Playback.Web
        _webVod.value = web != null && checkpointFor(web.embed)?.positionMs != null
        if (state != null && (previous?.positionMs?.div(10_000) != state.positionMs?.div(10_000) ||
                previous?.playing != state.playing || previous.ended != state.ended || previous.title != state.title)) savePosition()
    }

    fun savePosition() {
        val playback = _playback.value
        val embed = when (playback) {
            is Playback.Native -> playback.embed
            is Playback.Web -> playback.embed
            else -> return
        }
        if (embed == historyClearedFor) return
        val pos = checkpointFor(embed)?.positionMs ?: return
        val native = playback is Playback.Native
        val title = if (native) player.current.value?.title ?: titleFor(embed) else webMedia?.title ?: titleFor(embed)
        val ended = if (native) player.player.playbackState == Player.STATE_ENDED else webMedia?.ended == true
        history.record(embed, title, pos, ended)
    }

    fun resumeVod(vod: RecentVod) {
        val embed = vod.embed
        val active = (_playback.value as? Playback.Native)?.embed
        if (active?.matches(embed.platform, embed.id) == true) {
            linkJob?.cancel()
            player.player.seekTo(vod.positionMs)
            player.resume()
        } else setEmbed(embed)
    }

    fun clearHistory() {
        historyClearedFor = content.value.embed
        history.clear()
    }

    override fun onCleared() {
        savePosition()
        player.player.removeListener(historyListener)
        super.onCleared()
    }

    fun momentUrl(): String? {
        val embed = content.value.embed ?: return null
        val position = checkpointFor(embed)?.positionMs ?: return null
        return embed.copy(startSeconds = (position.coerceAtLeast(0) / 1000).toInt()).bigscreenUrl
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private val LIVE_CHANNELS = setOf("kick", "twitch", "angelthump")
        private const val TAG = "Bigscreen"
        private const val KEY_EMBED_PLATFORM = "embedPlatform"
        private const val KEY_EMBED_ID = "embedId"
        private const val KEY_EMBED_START = "embedStart"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_FORCE_WEB = "forceWeb"
        private const val KEY_RESUME = "resume"
        internal fun selectLive(streams: List<StreamEntry>, current: String?, pref: PlatformPreference, failed: Set<Embed>): StreamEntry {
            val available = if (pref == PlatformPreference.Auto) streams.filter { it.liveEmbed() !in failed }.ifEmpty { streams } else streams
            val order = if (pref == PlatformPreference.Auto) AUTO_ORDER else listOf(pref.name.lowercase(), current)
            return order.firstNotNullOfOrNull { platform -> available.firstOrNull { it.platform == platform } } ?: available.first()
        }

        /** Automatic choice favours platforms that play natively without ads. */
        val AUTO_ORDER = listOf("kick", "youtube", "twitch", "rumble", "facebook")
    }
}
