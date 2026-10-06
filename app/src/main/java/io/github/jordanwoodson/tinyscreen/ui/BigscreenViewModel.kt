package io.github.jordanwoodson.tinyscreen.ui

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Hosting
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import io.github.jordanwoodson.tinyscreen.stream.StreamEntry
import io.github.jordanwoodson.tinyscreen.stream.TopEmbed
import io.github.jordanwoodson.tinyscreen.stream.resolvers.RumbleResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.StreamOfflineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    data class Web(val embed: Embed, val url: String, val note: String? = null) : Playback
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

    private val _hashEmbed = MutableStateFlow(savedEmbed())
    val hashEmbed: StateFlow<Embed?> = _hashEmbed.asStateFlow()

    /** Platform chosen for Destiny's own stream this session (Change Platform). */
    private val _currentPlatform = MutableStateFlow(saved.get<String>(KEY_PLATFORM))

    /** Embeds the user flipped to the web player (or back) by hand. */
    private val _forceWeb = MutableStateFlow(saved.get<ArrayList<String>>(KEY_FORCE_WEB)?.toSet() ?: emptySet())

    /** Where the VOD was when Android closed the app, for when that embed loads again. */
    private var resumeAfterRestore: Pair<String, Long>? = saved.get<Bundle>(KEY_RESUME)?.let { b ->
        b.getString("embed")?.let { it to b.getLong("position") }
    }

    val streamInfo = live.streamInfo
    val embeds = live.embeds
    val latestVod = live.latestVod

    val content: StateFlow<Content> = combine(
        _hashEmbed, live.streamInfo, live.hosting, live.embeds, combine(_currentPlatform, settings) { a, b -> a to b },
    ) { hash, info, hosting, embeds, (current, s) ->
        val streams = info.live
        when {
            hash != null -> Content.Embedded(hash, embeds.firstOrNull { hash.matches(it.platform, it.mediaItem?.identifier?.mediaId ?: it.id) })
            streams.isEmpty() && !hosting?.id.isNullOrBlank() && hosting?.platform != null ->
                Content.Host(hosting, Embed(hosting.platform, hosting.id!!))
            streams.isNotEmpty() -> {
                val chosen = pickPlatform(streams, current, s.preferredPlatform)
                val entry = streams.first { it.platform == chosen }
                val id = entry.id
                if (id.isNullOrBlank()) Content.Offline else Content.Live(entry, streams, Embed(chosen, id))
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
        saved.setSavedStateProvider(KEY_RESUME) {
            val playing = _playback.value as? Playback.Native
            if (playing == null || playing.stream.isLive) {
                bundleOf()
            } else {
                bundleOf("embed" to playing.embed.hash, "position" to player.player.currentPosition)
            }
        }
        live.start()
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

    private fun pickPlatform(streams: List<StreamEntry>, current: String?, pref: PlatformPreference): String {
        val livePlatforms = streams.mapNotNull { it.platform }
        if (current != null && current in livePlatforms) return current
        val preferred = when (pref) {
            PlatformPreference.Auto -> AUTO_ORDER.firstOrNull { it in livePlatforms }
            else -> pref.name.lowercase().takeIf { it in livePlatforms }
        }
        return preferred ?: livePlatforms.first()
    }

    /** [resumeAt] picks a VOD up where it was (Retry, Reload). */
    private fun load(embed: Embed?, native: Boolean, resumeAt: Long = C.TIME_UNSET) {
        resolveJob?.cancel()
        // Only the first embed to load after a restore; Destiny's cached stream info may come first
        val restored = if (embed != null) resumeAfterRestore?.takeIf { it.first == embed.hash }?.second else null
        if (embed != null) resumeAfterRestore = null
        val resumeAtMs = if (resumeAt == C.TIME_UNSET && restored != null && restored > 0) restored else resumeAt
        if (embed == null) {
            player.stop()
            _playback.value = Playback.Idle
            return
        }
        val web = embed.webEmbedUrl()
        if (!native && web != null) {
            player.stop()
            _playback.value = Playback.Web(embed, web)
            return
        }
        val resolving = Playback.Resolving(embed)
        _playback.value = resolving
        resolveJob = viewModelScope.launch {
            val result = try {
                Result.success(graph.resolvers.resolve(embed))
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
                    player.play(if (titled.isLive || resumeAtMs == C.TIME_UNSET) titled else titled.copy(startPositionMs = resumeAtMs))
                    _playback.value = Playback.Native(embed, stream)
                }
                result.exceptionOrNull() is StreamOfflineException -> {
                    // The channel's own page would only show its offline banner
                    player.stop()
                    _playback.value = Playback.Failed(embed, "${embed.displayId} is offline on ${embed.platformName}")
                }
                web != null -> {
                    result.exceptionOrNull()?.let { Log.w(TAG, "native playback failed for ${embed.hash}", it) }
                    player.stop()
                    val note = if (result.isFailure) "Native player couldn't open this stream — using the web player" else null
                    _playback.value = Playback.Web(embed, web, note)
                }
                else -> {
                    player.stop()
                    _playback.value = Playback.Failed(embed, result.exceptionOrNull()?.message ?: "This stream can't be played")
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
        viewModelScope.launch {
            try {
                setEmbed(Embed("rumble", RumbleResolver.embedIdForPage(page), start))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "no embed for $page", e)
                _message.value = "Couldn't open that Rumble video"
            }
        }
        return true
    }

    fun closeEmbed() = setEmbed(null)

    fun changePlatform() {
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
            setForceWeb(if (usingWeb) _forceWeb.value - key else _forceWeb.value + key)
        } else {
            graph.settings.launchUpdate(viewModelScope) { it.copy(nativePlayer = true) }
            setForceWeb(_forceWeb.value - key)
        }
    }

    fun reload() {
        val embed = content.value.embed?.takeUnless { blockedReason.value != null }
        val playing = _playback.value as? Playback.Native
        val position = player.player.currentPosition
        val resumeAt = if (playing?.embed == embed && playing?.stream?.isLive == false && position > 0) position else C.TIME_UNSET
        load(embed, settings.value.nativePlayer && embed?.hash !in _forceWeb.value, resumeAt)
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val TAG = "Bigscreen"
        private const val KEY_EMBED_PLATFORM = "embedPlatform"
        private const val KEY_EMBED_ID = "embedId"
        private const val KEY_EMBED_START = "embedStart"
        private const val KEY_PLATFORM = "platform"
        private const val KEY_FORCE_WEB = "forceWeb"
        private const val KEY_RESUME = "resume"
        /** Automatic choice favours platforms that play natively without ads. */
        val AUTO_ORDER = listOf("kick", "youtube", "twitch", "rumble", "facebook")
    }
}
