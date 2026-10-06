package io.github.jordanwoodson.tinyscreen.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistTracker
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import io.github.jordanwoodson.tinyscreen.net.Http
import io.github.jordanwoodson.tinyscreen.settings.Quality
import io.github.jordanwoodson.tinyscreen.stream.resolvers.StreamOfflineException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class StreamKind { Hls, Dash, Progressive }

/** A directly playable stream produced by one of the platform resolvers. */
data class ResolvedStream(
    val url: String,
    val kind: StreamKind,
    val isLive: Boolean,
    val title: String? = null,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Separate audio track for progressive video-only streams (YouTube adaptive formats). */
    val audioUrl: String? = null,
    val startPositionMs: Long = C.TIME_UNSET,
    /** Rewrites playlists for this stream (Twitch ad stripping). */
    val rewriter: PlaylistRewriter? = null,
    /** How far behind the live edge to sit in low-latency mode, at least ([LiveHoldBack] may add more). */
    val liveTargetOffsetMs: Long = 4_000,
    /** Re-resolves the stream (fresh tokens) when playback hits an unrecoverable error. */
    val refresh: (suspend () -> ResolvedStream)? = null,
    /** Waits for something better to play (Twitch: full quality once a preroll is over); null if nothing comes. */
    val upgrade: (suspend () -> ResolvedStream?)? = null,
)

data class QualityOption(val label: String, val height: Int, val bitrate: Int, val group: TrackGroup, val trackIndex: Int)

/** What automatic recovery is doing about the current playback error. */
enum class Recovery {
    None,

    /** Trying again by itself; the player shows a spinner rather than the error. */
    Retrying,

    /** Offline: tries again as soon as the phone has a network. */
    WaitingForNetwork,

    /** The channel went offline. */
    Offline,

    /** Out of attempts: waits for the Retry button. */
    GaveUp,
}

/** [online] is the phone's network state ([io.github.jordanwoodson.tinyscreen.net.NetworkMonitor.online]). */
@OptIn(UnstableApi::class)
class PlayerHolder(context: Context, private val scope: CoroutineScope, private val online: StateFlow<Boolean>) {
    private val appContext = context.applicationContext

    @Volatile private var currentHeaders: Map<String, String> = emptyMap()
    @Volatile private var currentRewriter: PlaylistRewriter? = null

    private val httpFactory = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.MOBILE_UA)

    private val dataSourceFactory = PlaylistRewritingDataSource.Factory(
        ResolvingDataSource.Factory(DefaultDataSource.Factory(appContext, httpFactory)) { spec ->
            val headers = currentHeaders
            boundedRange(if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers))
        },
    ) { currentRewriter }

    /**
     * YouTube refuses open-ended ranges on the files its Android VR client hands out, and those carry
     * their length (`clen`), so ask for the rest of the file explicitly.
     */
    private fun boundedRange(spec: DataSpec): DataSpec {
        if (spec.length != C.LENGTH_UNSET.toLong() || spec.uri.host?.endsWith("googlevideo.com") != true) return spec
        val remaining = (spec.uri.getQueryParameter("clen")?.toLongOrNull() ?: return spec) - spec.position
        return if (remaining > 0) spec.subrange(0, remaining) else spec
    }

    val trackSelector = DefaultTrackSelector(appContext).apply {
        // YouTube masters mix H.264 and VP9; stick to H.264 so quality switches stay seamless
        setParameters(buildUponParameters().setPreferredVideoMimeType(MimeTypes.VIDEO_H264))
    }

    val player: ExoPlayer = ExoPlayer.Builder(appContext, DefaultRenderersFactory(appContext).setEnableDecoderFallback(true))
        .setTrackSelector(trackSelector)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(12_000, 50_000, 1_000, 2_000)
                .build(),
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val _current = MutableStateFlow<ResolvedStream?>(null)
    val current: StateFlow<ResolvedStream?> = _current.asStateFlow()

    private val _qualities = MutableStateFlow<List<QualityOption>>(emptyList())
    val qualities: StateFlow<List<QualityOption>> = _qualities.asStateFlow()

    private val _selectedQuality = MutableStateFlow<QualityOption?>(null)
    val selectedQuality: StateFlow<QualityOption?> = _selectedQuality.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    /** The [Quality] preference in effect while no rendition is picked by hand. */
    private val _activeQuality = MutableStateFlow(Quality.AUTO)
    val activeQuality: StateFlow<String> = _activeQuality.asStateFlow()

    /** Picks from the quality menu, as [Quality] preferences, for "Remember my choice". */
    private val _qualityPicks = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val qualityPicks: SharedFlow<String> = _qualityPicks

    private val _recovery = MutableStateFlow(Recovery.None)
    val recovery: StateFlow<Recovery> = _recovery.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var defaultQuality = Quality.AUTO
    private var qualityPickedByHand = false

    var lowLatency: Boolean = true

    /** "Block ads": whether streams' playlist filters (Twitch ad stripping) run, refreshes included. */
    var blockAds: Boolean = true
        set(value) {
            field = value
            _current.value?.let {
                currentRewriter = rewriterFor(it)
                AdBreakMonitor.claim(adFilterFor(it))
            }
        }

    private var retryJob: Job? = null
    private var upgradeJob: Job? = null
    private var stallJob: Job? = null
    private var retries = 0
    private var behindLiveRetried = false

    init {
        if (io.github.jordanwoodson.tinyscreen.BuildConfig.DEBUG) {
            player.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("TinyPlayer"))
        }
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                _qualities.value = videoQualities(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                recover(error)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retries = 0
                    behindLiveRetried = false
                    _recovery.value = Recovery.None
                }
                stallJob?.cancel()
                if (playbackState == Player.STATE_BUFFERING) stallJob = scope.launch { watchForGapStall() }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
        })
        // A playback that ran out of attempts while the network was flaky gets a fresh start when it's back
        scope.launch {
            online.collect { isOnline ->
                val error = player.playerError
                if (isOnline && error != null && _recovery.value == Recovery.GaveUp) {
                    retries = 0
                    recover(error)
                }
            }
        }
    }

    /**
     * A Twitch ad break the filter couldn't swap out plays as EXT-X-GAP segments. Media3 waits at the
     * start of the gap until real segments follow, with gap "data" buffered ahead of it, and stops
     * loading once that reaches 50 s, so a long break would leave it waiting for good. Keep it at the
     * live edge instead; once the break ends, playback carries on from there.
     */
    private suspend fun watchForGapStall() {
        while (true) {
            delay(GAP_STALL_MS)
            if (player.playbackState != Player.STATE_BUFFERING) return
            val ahead = player.bufferedPosition - player.currentPosition
            if (player.isCurrentMediaItemLive && player.playWhenReady && ahead >= GAP_AHEAD_MS) {
                player.seekToDefaultPosition()
            }
        }
    }

    private fun adFilterFor(stream: ResolvedStream): PlaylistRewriter? = stream.rewriter?.takeIf { blockAds }

    private fun rewriterFor(stream: ResolvedStream): PlaylistRewriter? {
        val adFilter = adFilterFor(stream)
        val holdBack = if (lowLatency && stream.isLive && stream.kind == StreamKind.Hls) LiveHoldBack(stream.liveTargetOffsetMs) else null
        return if (adFilter != null && holdBack != null) ChainedRewriter(listOf(adFilter, holdBack)) else adFilter ?: holdBack
    }

    /** [keepQuality] keeps a hand-picked quality, for refreshes of the same stream. */
    fun play(stream: ResolvedStream, playWhenReady: Boolean = true, keepQuality: Boolean = false) {
        retryJob?.cancel()
        upgradeJob?.cancel()
        retries = 0
        behindLiveRetried = false
        _recovery.value = Recovery.None
        currentHeaders = stream.headers
        currentRewriter = rewriterFor(stream)
        AdBreakMonitor.claim(adFilterFor(stream))
        _current.value = stream
        if (!keepQuality) {
            qualityPickedByHand = false
            _selectedQuality.value = null
            applyQuality(defaultQuality)
        }
        val source = buildSource(stream)
        player.setMediaSource(source, stream.startPositionMs)
        player.prepare()
        player.playWhenReady = playWhenReady
        stream.upgrade?.let { upgrade -> upgradeJob = scope.launch { upgrade(stream, upgrade) } }
    }

    private suspend fun upgrade(stream: ResolvedStream, upgrade: suspend () -> ResolvedStream?) {
        val better = try {
            withContext(Dispatchers.IO) { upgrade() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "upgrade failed: ${e.message}")
            null
        } ?: return
        if (_current.value !== stream) return
        // Preferences carry over; a rendition picked by hand belongs to the old master
        play(better.copy(title = better.title ?: stream.title), player.playWhenReady, keepQuality = _selectedQuality.value == null)
    }

    fun stop() {
        retryJob?.cancel()
        upgradeJob?.cancel()
        _recovery.value = Recovery.None
        AdBreakMonitor.claim(null)
        player.stop()
        player.clearMediaItems()
        _current.value = null
        currentRewriter = null
        currentHeaders = emptyMap()
    }

    private fun buildSource(stream: ResolvedStream): MediaSource {
        val metadata = MediaMetadata.Builder()
            .setTitle(stream.title ?: "Tinyscreen")
            .setArtist(stream.subtitle)
            .setArtworkUri(stream.artworkUrl?.let(Uri::parse))
            .setIsPlayable(true)
            .build()
        val liveConfig = MediaItem.LiveConfiguration.Builder()
            // HLS gets its low-latency target from the HOLD-BACK that LiveHoldBack writes into each playlist
            .setTargetOffsetMs(if (lowLatency && stream.kind != StreamKind.Hls) stream.liveTargetOffsetMs else C.TIME_UNSET)
            .setMinPlaybackSpeed(0.97f)
            .setMaxPlaybackSpeed(1.04f)
            .build()
        val item = MediaItem.Builder()
            .setUri(stream.url)
            .setMediaMetadata(metadata)
            .setLiveConfiguration(liveConfig)
            .apply {
                when (stream.kind) {
                    StreamKind.Hls -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    StreamKind.Dash -> setMimeType(MimeTypes.APPLICATION_MPD)
                    StreamKind.Progressive -> Unit
                }
            }
            .build()
        return when (stream.kind) {
            StreamKind.Hls -> HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .setPlaylistTrackerFactory { factory, policy, parserFactory, cmcd, executor ->
                    DefaultHlsPlaylistTracker(
                        factory, policy, parserFactory, cmcd,
                        // Swapping between Twitch backup playlists can briefly repeat one; elsewhere
                        // a frozen playlist should be retried promptly
                        if (adFilterFor(stream) != null) 10.0 else DefaultHlsPlaylistTracker.DEFAULT_PLAYLIST_STUCK_TARGET_DURATION_COEFFICIENT,
                        executor,
                    )
                }
                .createMediaSource(item)

            StreamKind.Dash -> DashMediaSource.Factory(dataSourceFactory).createMediaSource(item)
            StreamKind.Progressive -> {
                val video = ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
                val audio = stream.audioUrl?.let {
                    ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(it))
                }
                if (audio != null) MergingMediaSource(true, video, audio) else video
            }
        }
    }

    private fun recover(error: PlaybackException) {
        val stream = _current.value ?: return
        retryJob?.cancel()
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && stream.isLive && !behindLiveRetried) {
            // Falling behind the live window isn't a failure: back to the live edge straight away
            behindLiveRetried = true
            player.seekToDefaultPosition()
            player.prepare()
            return
        }
        val network = isNetworkError(error)
        if (network && !online.value) {
            _recovery.value = Recovery.WaitingForNetwork
            retryJob = scope.launch {
                online.first { it }
                retry(stream, error)
            }
            return
        }
        // Live streams keep trying through a dead zone; anything else gets a few attempts
        if (!(network && stream.isLive) && retries >= MAX_RETRIES) {
            _recovery.value = Recovery.GaveUp
            return
        }
        retries++
        _recovery.value = Recovery.Retrying
        val wait = if (stream.isLive) (1_000L shl (retries - 1).coerceAtMost(5)).coerceAtMost(30_000) else 1_000L * retries
        retryJob = scope.launch {
            // Go straight away if the network drops and comes back in the meantime
            withTimeoutOrNull(wait) {
                online.first { !it }
                online.first { it }
            }
            retry(stream, error)
        }
    }

    private suspend fun retry(stream: ResolvedStream, error: PlaybackException) {
        if (_current.value !== stream) return
        val stuck = error.cause is HlsPlaylistTracker.PlaylistStuckException
        val httpError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        // Tokens expire (Twitch after 20 minutes, Kick after 10), and a long outage can outlive them
        if ((httpError || stuck || retries > 2) && stream.refresh != null) {
            val fresh = try {
                withContext(Dispatchers.IO) { stream.refresh.invoke() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamOfflineException) {
                _recovery.value = Recovery.Offline
                return
            } catch (e: Exception) {
                Log.w(TAG, "refresh failed: ${e.message}")
                null
            }
            // Another stream may have started while the refresh ran
            if (_current.value !== stream) return
            if (fresh != null) {
                val keep = retries
                val position = player.currentPosition
                val titled = fresh.copy(title = fresh.title ?: stream.title)
                // Paused (or stopped by the sleep timer) stays that way
                play(if (fresh.isLive) titled else titled.copy(startPositionMs = position), player.playWhenReady, keepQuality = true)
                retries = keep
                _recovery.value = Recovery.Retrying
                return
            }
        }
        // Back at the live edge after an outage, and paused stays paused
        if (stream.isLive) player.seekToDefaultPosition()
        player.prepare()
    }

    private fun isNetworkError(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
        -> true
        else -> false
    }

    /** Play from the controls, picking up again after the sleep timer stopped playback. */
    fun resume() {
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                if (_current.value?.isLive == true) player.seekToDefaultPosition()
                player.prepare()
            }
            Player.STATE_ENDED -> player.seekToDefaultPosition()
            else -> Unit
        }
        player.play()
    }

    // --- Quality ---

    private fun videoQualities(tracks: Tracks): List<QualityOption> {
        val options = mutableListOf<QualityOption>()
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                options += QualityOption(qualityLabel(f), f.height, f.bitrate, group.mediaTrackGroup, i)
            }
        }
        return options.distinctBy { it.label }.sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.bitrate })
    }

    private fun qualityLabel(f: Format): String {
        val h = if (f.height > 0) f.height else return f.label ?: "${f.bitrate / 1000} kbps"
        val fps = if (f.frameRate > 0) f.frameRate.toInt() else 0
        return if (fps >= 50) "${h}p$fps" else "${h}p"
    }

    /** The label of the rendition playing now, e.g. "1080p60". */
    fun playingQualityLabel(): String? = player.videoFormat?.takeIf { it.height > 0 }?.let(::qualityLabel)

    /** A pick from the quality menu (null is Auto). It holds until the next stream. */
    fun selectQuality(option: QualityOption?) {
        qualityPickedByHand = true
        if (option == null) {
            applyQuality(Quality.AUTO)
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .addOverride(TrackSelectionOverride(option.group, option.trackIndex))
                .build()
            _audioOnly.value = false
        }
        _selectedQuality.value = option
        val pick = if (option == null) Quality.AUTO else option.height.takeIf { it > 0 }?.toString()
        if (pick != null) _qualityPicks.tryEmit(pick)
    }

    /** The [Quality] new streams start at, from the Wi-Fi or mobile setting. */
    fun setDefaultQuality(pref: String) {
        defaultQuality = pref
        if (!qualityPickedByHand) applyQuality(pref)
    }

    private fun applyQuality(pref: String) {
        val audio = pref == Quality.AUDIO
        // Kick and Twitch mux audio into every rendition, so turning video off still downloads
        // whichever one was playing. Audio only plays the smallest rendition instead (hidden); a
        // progressive file with its own audio track can stop loading video altogether.
        val stopVideo = audio && _current.value?.kind == StreamKind.Progressive
        // A box rather than a height, so portrait streams are capped like landscape ones. The slack
        // lets 854×480 through a 480p cap.
        val maxSide = pref.toIntOrNull()?.let { it * 16 / 9 + 2 } ?: Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .setMaxVideoSize(maxSide, maxSide)
            // No rendition fits a 1 bps cap, so the selector falls back to the smallest one
            .setMaxVideoBitrate(if (audio) 1 else Int.MAX_VALUE)
            // Anything but Auto plays the best rendition within the cap instead of adapting
            .setForceHighestSupportedBitrate(pref != Quality.AUTO && !audio)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, stopVideo)
            .build()
        _audioOnly.value = audio
        _activeQuality.value = pref
    }

    /** The audio-only switch, which counts as a quality pick. Turning it off goes back to Auto. */
    fun setAudioOnly(enabled: Boolean) {
        val pref = if (enabled) Quality.AUDIO else Quality.AUTO
        qualityPickedByHand = true
        _selectedQuality.value = null
        applyQuality(pref)
        _qualityPicks.tryEmit(pref)
    }

    // --- Sleep timer ---

    private val _sleepTimerEndsAt = MutableStateFlow<Long?>(null)
    val sleepTimerEndsAt: StateFlow<Long?> = _sleepTimerEndsAt.asStateFlow()
    private var sleepJob: Job? = null

    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerEndsAt.value = null
            return
        }
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _sleepTimerEndsAt.value = endsAt
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            player.pause()
            _sleepTimerEndsAt.value = null
        }
    }

    // --- Live helpers ---

    fun goLive() {
        player.seekToDefaultPosition()
        resume()
    }

    private companion object {
        const val TAG = "PlayerHolder"
        const val MAX_RETRIES = 6
        const val GAP_STALL_MS = 6_000L
        const val GAP_AHEAD_MS = 4_000L
    }
}
