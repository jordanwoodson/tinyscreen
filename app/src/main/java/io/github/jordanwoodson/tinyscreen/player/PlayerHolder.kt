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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** How far behind the live edge to sit in low-latency mode. */
    val liveTargetOffsetMs: Long = 4_000,
    /** Re-resolves the stream (fresh tokens) when playback hits an unrecoverable error. */
    val refresh: (suspend () -> ResolvedStream)? = null,
)

data class QualityOption(val label: String, val height: Int, val bitrate: Int, val group: TrackGroup, val trackIndex: Int)

@OptIn(UnstableApi::class)
class PlayerHolder(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext

    @Volatile private var currentHeaders: Map<String, String> = emptyMap()
    @Volatile private var currentRewriter: PlaylistRewriter? = null

    private val httpFactory = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.MOBILE_UA)

    private val dataSourceFactory = PlaylistRewritingDataSource.Factory(
        ResolvingDataSource.Factory(DefaultDataSource.Factory(appContext, httpFactory)) { spec ->
            val headers = currentHeaders
            if (headers.isEmpty()) spec else spec.withAdditionalHeaders(headers)
        },
    ) { currentRewriter }

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

    private var defaultQuality = Quality.AUTO
    private var qualityPickedByHand = false

    var lowLatency: Boolean = true
    private var retryJob: Job? = null
    private var retries = 0

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
                if (playbackState == Player.STATE_READY) retries = 0
            }
        })
    }

    /** [keepQuality] keeps a hand-picked quality, for refreshes of the same stream. */
    fun play(stream: ResolvedStream, playWhenReady: Boolean = true, keepQuality: Boolean = false) {
        retryJob?.cancel()
        retries = 0
        AdBreakMonitor.set(false)
        currentHeaders = stream.headers
        currentRewriter = stream.rewriter
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
    }

    fun stop() {
        retryJob?.cancel()
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
            .setTargetOffsetMs(if (lowLatency) stream.liveTargetOffsetMs else C.TIME_UNSET)
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
                        // Stitched-ad stripping can hold a live playlist still for a whole ad break
                        40.0,
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
        if (retries >= 6) return
        retries++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(1_000L * retries)
            val behindLive = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
            val stuck = error.cause is HlsPlaylistTracker.PlaylistStuckException
            val httpError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            if ((httpError || stuck) && stream.refresh != null) {
                val fresh = try {
                    withContext(Dispatchers.IO) { stream.refresh.invoke() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "refresh failed: ${e.message}")
                    null
                }
                // Another stream may have started while the refresh ran
                if (_current.value !== stream) return@launch
                if (fresh != null) {
                    val keep = retries
                    val position = player.currentPosition
                    play(if (fresh.isLive) fresh else fresh.copy(startPositionMs = position), keepQuality = true)
                    retries = keep
                    return@launch
                }
            }
            if (behindLive) player.seekToDefaultPosition()
            player.prepare()
            player.play()
        }
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

    fun isAtLiveEdge(): Boolean {
        if (!player.isCurrentMediaItemLive) return false
        val offset = player.currentLiveOffset
        return offset == C.TIME_UNSET || offset < 12_000
    }

    fun goLive() {
        player.seekToDefaultPosition()
        player.play()
    }

    private companion object {
        const val TAG = "PlayerHolder"
    }
}
