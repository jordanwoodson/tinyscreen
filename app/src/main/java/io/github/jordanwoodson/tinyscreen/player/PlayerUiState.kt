package io.github.jordanwoodson.tinyscreen.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import kotlinx.coroutines.delay

/** Compose-observable snapshot of the player, refreshed by listener events plus a 500 ms ticker. */
@Stable
class PlayerUiState(val player: Player) {
    var isPlaying by mutableStateOf(player.isPlaying)
    var playWhenReady by mutableStateOf(player.playWhenReady)
    var playbackState by mutableIntStateOf(player.playbackState)
    var isLive by mutableStateOf(player.isCurrentMediaItemLive)
    var isSeekable by mutableStateOf(player.isCurrentMediaItemSeekable)
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(C.TIME_UNSET)
    var bufferedMs by mutableLongStateOf(0L)
    var liveOffsetMs by mutableLongStateOf(C.TIME_UNSET)
    var error by mutableStateOf<PlaybackException?>(player.playerError)
    var videoSize by mutableStateOf(player.videoSize)
    var speed by mutableFloatStateOf(player.playbackParameters.speed)
    var volume by mutableFloatStateOf(player.volume)
    var hasMedia by mutableStateOf(player.mediaItemCount > 0)

    val isBuffering get() = playbackState == Player.STATE_BUFFERING && playWhenReady
    val isEnded get() = playbackState == Player.STATE_ENDED
    val atLiveEdge get() = isLive && (liveOffsetMs == C.TIME_UNSET || liveOffsetMs < 12_000)
    val canSeek get() = isSeekable && durationMs != C.TIME_UNSET && durationMs > 0

    fun refreshProgress() {
        positionMs = player.currentPosition.coerceAtLeast(0)
        durationMs = player.duration
        bufferedMs = player.bufferedPosition
        liveOffsetMs = if (player.isCurrentMediaItemLive) player.currentLiveOffset else C.TIME_UNSET
        isLive = player.isCurrentMediaItemLive
        isSeekable = player.isCurrentMediaItemSeekable
    }

    val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            isPlaying = player.isPlaying
            playWhenReady = player.playWhenReady
            playbackState = player.playbackState
            error = player.playerError
            hasMedia = player.mediaItemCount > 0
            volume = player.volume
            refreshProgress()
        }

        override fun onVideoSizeChanged(size: VideoSize) {
            videoSize = size
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speed = playbackParameters.speed
        }
    }
}

@Composable
fun rememberPlayerUiState(player: Player): PlayerUiState {
    val state = remember(player) { PlayerUiState(player) }
    DisposableEffect(player) {
        player.addListener(state.listener)
        state.refreshProgress()
        onDispose { player.removeListener(state.listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            state.refreshProgress()
            delay(500)
        }
    }
    return state
}

fun formatDuration(ms: Long): String {
    if (ms == C.TIME_UNSET || ms < 0) return "0:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
