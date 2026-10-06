package io.github.jordanwoodson.tinyscreen.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

data class QualityOption(val label: String, val height: Int, val bitrate: Int, val groupIndex: Int, val trackIndex: Int)

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

    fun play(stream: ResolvedStream, playWhenReady: Boolean = true) {
        retryJob?.cancel()
        retries = 0
        AdBreakMonitor.set(false)
        currentHeaders = stream.headers
        currentRewriter = stream.rewriter
        _current.value = stream
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
                val fresh = runCatching { stream.refresh.invoke() }.getOrNull()
                if (fresh != null) {
                    val keep = retries
                    val position = player.currentPosition
                    play(if (fresh.isLive) fresh else fresh.copy(startPositionMs = position))
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
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                options += QualityOption(qualityLabel(f), f.height, f.bitrate, groupIndex, i)
            }
        }
        return options.distinctBy { it.label }.sortedWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.bitrate })
    }

    private fun qualityLabel(f: Format): String {
        val h = if (f.height > 0) f.height else return f.label ?: "${f.bitrate / 1000} kbps"
        val fps = if (f.frameRate > 0) f.frameRate.toInt() else 0
        return if (fps >= 50) "${h}p$fps" else "${h}p"
    }

    fun selectQuality(option: QualityOption?) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (option != null) {
            val group = player.currentTracks.groups.getOrNull(option.groupIndex)
            if (group != null) builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
        }
        player.trackSelectionParameters = builder.build()
        _selectedQuality.value = option
    }

    /** Caps automatic quality ("auto", "1080", "720", "480", "360"). */
    fun applyPreferredQuality(pref: String) {
        val maxHeight = pref.toIntOrNull() ?: Int.MAX_VALUE
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            .build()
    }

    fun setAudioOnly(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, enabled)
            .build()
        _audioOnly.value = enabled
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
}
