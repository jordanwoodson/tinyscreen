package io.github.jordanwoodson.tinyscreen.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.jordanwoodson.tinyscreen.TinyscreenApp
import io.github.jordanwoodson.tinyscreen.player.ResolvedStream
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference
import io.github.jordanwoodson.tinyscreen.stream.Embed
import io.github.jordanwoodson.tinyscreen.stream.Hosting
import io.github.jordanwoodson.tinyscreen.stream.LinkParser
import io.github.jordanwoodson.tinyscreen.stream.StreamEntry
import io.github.jordanwoodson.tinyscreen.stream.TopEmbed
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

class BigscreenViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TinyscreenApp).graph
    private val live = graph.live
    val settings = graph.settings.settings
    val player = graph.player

    private val _hashEmbed = MutableStateFlow<Embed?>(null)
    val hashEmbed: StateFlow<Embed?> = _hashEmbed.asStateFlow()

    /** Platform chosen for Destiny's own stream this session (Change Platform). */
    private val _currentPlatform = MutableStateFlow<String?>(null)

    /** Embeds the user flipped to the web player (or back) by hand. */
    private val _forceWeb = MutableStateFlow<Set<String>>(emptySet())

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

    private fun load(embed: Embed?, native: Boolean) {
        resolveJob?.cancel()
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
                    // "Block ads" off: play Twitch playlists untouched
                    player.play(if (settings.value.adblock) stream else stream.copy(rewriter = null))
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

    // --- Actions ---

    fun setEmbed(embed: Embed?) {
        _hashEmbed.value = embed
    }

    /** Opens a link from chat, a share intent or a deep link if it names something playable. */
    fun openLink(link: String): Boolean {
        val embed = LinkParser.parse(link) ?: return false
        setEmbed(embed)
        return true
    }

    fun closeEmbed() = setEmbed(null)

    fun changePlatform() {
        val c = content.value as? Content.Live ?: return
        val platforms = c.allLive.mapNotNull { it.platform }
        if (platforms.size < 2) return
        val next = platforms[(platforms.indexOf(c.entry.platform) + 1) % platforms.size]
        _currentPlatform.value = next
        PlatformPreference.entries.firstOrNull { it.name.equals(next, ignoreCase = true) }?.let { pref ->
            graph.settings.launchUpdate(viewModelScope) { it.copy(preferredPlatform = pref) }
        }
    }

    fun togglePlayerType() {
        val embed = content.value.embed ?: return
        val key = embed.hash
        val usingWeb = _playback.value is Playback.Web
        if (settings.value.nativePlayer) {
            _forceWeb.value = if (usingWeb) _forceWeb.value - key else _forceWeb.value + key
        } else {
            graph.settings.launchUpdate(viewModelScope) { it.copy(nativePlayer = true) }
            _forceWeb.value = _forceWeb.value - key
        }
    }

    fun reload() {
        val embed = content.value.embed?.takeUnless { blockedReason.value != null }
        load(embed, settings.value.nativePlayer && embed?.hash !in _forceWeb.value)
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        private const val TAG = "Bigscreen"
        /** Automatic choice favours platforms that play natively without ads. */
        val AUTO_ORDER = listOf("kick", "youtube", "twitch", "rumble", "facebook")
    }
}
